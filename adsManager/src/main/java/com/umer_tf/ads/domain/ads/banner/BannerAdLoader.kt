package com.umer_tf.ads.domain.ads.banner

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.MainThread
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.core.AdsInitializer
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_CLICKED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.Utilities.getAdSize
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import com.umer_tf.ads.domain.utils.onMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * BannerAdLoader is responsible for loading and displaying different types of banner ads.
 */
class BannerAdLoader: IBannerAdLoader {
    val TAG = "BannerAdLoader"

    // Proper coroutine scope that cancels when loader is destroyed
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Live AdView per host frame, so the one being replaced can be destroyed. */
    private val adViewsByFrame = java.util.WeakHashMap<FrameLayout, AdView>()

    /** Frames already bound to their Activity lifecycle, to register the observer only once. */
    private val observedFrames = java.util.WeakHashMap<FrameLayout, Boolean>()

    /**
     * Puts [adView] in [frameLayout] and takes over the lifecycle of the banner it replaces.
     *
     * Two leaks used to come out of this and both cost show rate:
     *
     * 1. Every reload created a new AdView and only *detached* the old one. A detached AdView keeps
     *    its own refresh timer (AdMob "automatic refresh" on the unit), so each reload left behind
     *    another invisible AdView requesting ads forever - requests that can never be seen.
     * 2. Nothing ever called `pause()`, so the visible AdView also kept refreshing while its screen
     *    was paused or the app was in the background.
     *
     * A unit that requests constantly without displaying gets its fill rate throttled, which is why
     * this also shows up as a falling match rate.
     *
     * Under the Next-Gen SDK only half of that still needs doing here. `AdView` no longer exposes
     * `pause()` / `resume()`: refresh is tied to the Activity handed to
     * [AdView.registerBannerAd], so the SDK pauses it with that Activity. Destroying the AdView a
     * frame previously held is still ours to do, and is still what stops orphaned refresh loops.
     */
    private fun attachAdView(activity: Activity, frameLayout: FrameLayout, adView: AdView) {
        adViewsByFrame.put(frameLayout, adView)?.takeIf { it !== adView }?.let { previous ->
            Log.d(TAG, "Monetization :- destroying the banner this frame held before")
            previous.visibility = View.GONE
            previous.destroy()
        }
        frameLayout.removeAllViews()
        frameLayout.addView(adView)

        if (observedFrames[frameLayout] == true) return
        val owner = activity as? androidx.lifecycle.LifecycleOwner ?: return
        observedFrames[frameLayout] = true
        // One observer per frame, always acting on whichever AdView is current.
        owner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                adViewsByFrame.remove(frameLayout)?.destroy()
                observedFrames.remove(frameLayout)
                owner.lifecycle.removeObserver(this)
            }
        })
    }

    /**
     * Keep the host slot visible and laid out for the request. Setting it [View.GONE] before
     * [AdView.loadAd] is what produced hundreds of banner matches with almost no impressions:
     * AdMob fills a 0-height / hidden AdView, then console refresh keeps requesting it.
     */
    private fun prepareVisibleSlot(
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
    ): Boolean {
        if (!frameLayout.isAttachedToWindow) {
            Log.d(TAG, "Monetization :- banner skipped — frame not attached")
            shimmerFrameLayout?.stopShimmer()
            shimmerFrameLayout?.visibility = View.GONE
            return false
        }
        frameLayout.visibility = View.VISIBLE
        shimmerFrameLayout?.startShimmer()
        shimmerFrameLayout?.visibility = View.VISIBLE
        return true
    }

    /**
     * True while [adView] is still the banner this frame is showing.
     *
     * A frame can be rebound while a request is in flight — a reload, a tab switch, a rotation —
     * and the in-flight request's callback then arrives for an AdView that has already been
     * replaced and destroyed. Acting on that late callback would tear down the *live* banner, so
     * every callback checks this before touching the frame.
     */
    private fun isCurrentAdView(frameLayout: FrameLayout, adView: AdView): Boolean =
        adViewsByFrame[frameLayout] === adView

    private fun collapseFailedBanner(
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
    ) {
        shimmerFrameLayout?.stopShimmer()
        shimmerFrameLayout?.visibility = View.GONE
        adViewsByFrame.remove(frameLayout)?.destroy()
        frameLayout.removeAllViews()
        frameLayout.visibility = View.GONE
    }

    /**
     * Shows an adaptive banner ad.
     *
     * @param activity The activity context.
     * @param shimmerFrameLayout The shimmer layout to show while the ad is loading can be null.
     * @param frameLayout The layout to hold the ad view.
     * @param adUnitId The ad unit ID.
     */
    @MainThread
    override fun showAdaptiveBanner(
        activity: Activity,
        shimmerFrameLayout: ShimmerFrameLayout?,
        frameLayout: FrameLayout,
        @ValidateAdUnitId adUnitId: String
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        try {
            if (!shouldShowAd(activity)) {
                shimmerFrameLayout?.visibility = View.GONE
                return
            }
            val start = {
                if (activity.isFinishing || activity.isDestroyed) {
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                } else if (prepareVisibleSlot(frameLayout, shimmerFrameLayout)) {
                    loadAdaptiveBanner(activity, shimmerFrameLayout, frameLayout, adUnitId)
                }
            }
            if (frameLayout.width > 0) start() else frameLayout.post(start)
        } catch (e: Exception) {
            Log.d(TAG, "Monetization :- loadBanner: ${e.message}")
        }
    }

    private fun loadAdaptiveBanner(
        activity: Activity,
        shimmerFrameLayout: ShimmerFrameLayout?,
        frameLayout: FrameLayout,
        adUnitId: String,
    ) {
        val adView = AdView(activity)
        attachAdView(activity, frameLayout, adView)

        // Ad unit and size now live on the request, not on the AdView.
        val adRequest = BannerAdRequest.Builder(adUnitId, getAdSize(activity)).build()

        loadInto(
            activity = activity,
            adView = adView,
            frameLayout = frameLayout,
            shimmerFrameLayout = shimmerFrameLayout,
            adUnitId = adUnitId,
            request = adRequest,
            analyticsLabel = "banner_ad",
            revenueLabel = "Banner",
            failureLog = "onBannerAdFailed",
            successLog = "onBannerAdLoaded:",
        )
    }

    /**
     * Requests [request] into [adView] and wires up the callbacks shared by all three banner types.
     *
     * Next-Gen splits what `AdListener` used to do in one place: load outcomes arrive on an
     * [AdLoadCallback], while click / impression / paid events live on the [BannerAd]'s own
     * [BannerAdEventCallback], which can only be attached once the ad exists. Both arrive on a
     * background thread, so everything that touches the placeholder views is marshalled back.
     */
    private fun loadInto(
        activity: Activity,
        adView: AdView,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
        adUnitId: String,
        request: BannerAdRequest,
        analyticsLabel: String,
        revenueLabel: String,
        failureLog: String,
        successLog: String,
        // The medium-rectangle path has always reported its clicks under the collapsible label.
        // Kept as-is so the migration does not silently move existing analytics data.
        clickLabel: String = analyticsLabel,
    ) {
        // Initialization is asynchronous, so this widens the window in which the frame can be
        // rebound before the request is even sent - hence the staleness checks below.
        AdsInitializer.runWhenInitialized(activity) {
            if (activity.isFinishing || activity.isDestroyed) {
                collapseFailedBanner(frameLayout, shimmerFrameLayout)
                return@runWhenInitialized
            }
            if (!isCurrentAdView(frameLayout, adView)) return@runWhenInitialized
            adView.loadAd(request, object : AdLoadCallback<BannerAd> {
                override fun onAdFailedToLoad(adError: LoadAdError) = onMain {
                    Log.d(TAG, "Monetization :- $failureLog: ${adError.message}")
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, analyticsLabel)
                    // Only collapse the frame if this failure belongs to the banner it is still
                    // showing. Collapsing unconditionally destroyed whichever AdView had since
                    // replaced this one - killing a live banner because an older request failed.
                    if (!isCurrentAdView(frameLayout, adView)) return@onMain
                    collapseFailedBanner(frameLayout, shimmerFrameLayout)
                }

                override fun onAdLoaded(ad: BannerAd) = onMain {
                    Log.d(TAG, "Monetization :- $successLog")
                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdClicked() = onMain {
                            AnalyticsManager.getInstance(activity)
                                .sendAnalytics(AD_CLICKED, clickLabel)
                        }

                        override fun onAdPaid(value: AdValue) {
                            coroutineScope.launch {
                                AdsAnalytics.logAppsFlyerRevenue(
                                    adUnitId,
                                    revenueLabel,
                                    value,
                                    activity.application
                                )
                            }
                        }
                    }
                    // A loaded BannerAd is not on screen until it is registered with the AdView -
                    // the step that replaces the legacy SDK's implicit "loadAd renders it" flow.
                    // Registering against a dead Activity would be a match with no impression.
                    if (!isCurrentAdView(frameLayout, adView)) {
                        // This frame has moved on to another banner. Drop the fill and leave the
                        // frame alone — its current banner is somebody else's to manage. The
                        // AdView itself was already destroyed by whoever replaced it.
                        ad.destroy()
                        return@onMain
                    }
                    if (activity.isFinishing || activity.isDestroyed) {
                        collapseFailedBanner(frameLayout, shimmerFrameLayout)
                        ad.destroy()
                        return@onMain
                    }
                    adView.registerBannerAd(ad, activity)
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED, analyticsLabel)
                }
            })
        }
    }

    /**
     * Shows a medium rectangle banner ad.
     *
     * @param activity The activity context.
     * @param frameLayout The layout to hold the ad view.
     * @param adUnitId The ad unit ID.
     */
    @MainThread
    override fun showMemRecBanner(activity: Activity, frameLayout: FrameLayout,shimmerFrameLayout: ShimmerFrameLayout?, @ValidateAdUnitId adUnitId: String) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        try {
            if (!shouldShowAd(activity)) {
                shimmerFrameLayout?.visibility = View.GONE
                return
            }
            val start = {
                if (!activity.isFinishing && !activity.isDestroyed &&
                    prepareVisibleSlot(frameLayout, shimmerFrameLayout)
                ) {
                    loadMemRecBanner(activity, frameLayout, shimmerFrameLayout, adUnitId)
                }
            }
            if (frameLayout.width > 0) start() else frameLayout.post(start)
        } catch (e: Exception) {
            Log.d(TAG, "loadMediumRectangle: ${e.message}")
        }
    }

    private fun loadMemRecBanner(
        activity: Activity,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
        adUnitId: String,
    ) {
        val adView = AdView(activity)
        attachAdView(activity, frameLayout, adView)
        loadInto(
            activity = activity,
            adView = adView,
            frameLayout = frameLayout,
            shimmerFrameLayout = shimmerFrameLayout,
            adUnitId = adUnitId,
            request = BannerAdRequest.Builder(adUnitId, AdSize.MEDIUM_RECTANGLE).build(),
            analyticsLabel = "banner_memrec_ad",
            revenueLabel = "MemRecBanner",
            failureLog = "onMediumRectangleAdFailed",
            successLog = "onMediumRectangleAdLoaded",
            clickLabel = "banner_collapsable_ad",
        )
    }

    /**
     * Shows a collapsible banner ad.
     *
     * @param activity The activity context.
     * @param frameLayout The layout to hold the ad view.
     * @param adUnitId The ad unit ID.
     * @param isTop Boolean indicating if the banner is collapsible from the top.
     */
    @MainThread
    override fun showCollapsableBanner(activity: Activity, frameLayout: FrameLayout,shimmerFrameLayout: ShimmerFrameLayout?, @ValidateAdUnitId adUnitId: String, isTop: Boolean) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        try {
            if (!shouldShowAd(activity)) {
                shimmerFrameLayout?.visibility = View.GONE
                return
            }
            val start = {
                if (!activity.isFinishing && !activity.isDestroyed &&
                    prepareVisibleSlot(frameLayout, shimmerFrameLayout)
                ) {
                    val adView = AdView(activity)
                    attachAdView(activity, frameLayout, adView)
                    val extras = Bundle()
                    if (isTop) extras.putString("collapsible", "top")
                    else extras.putString("collapsible", "bottom")
                    // `addNetworkExtrasBundle(AdMobAdapter::class.java, ...)` is gone; the AdMob
                    // adapter's own extras now have a dedicated setter.
                    val adRequest = BannerAdRequest.Builder(adUnitId, getAdSize(activity))
                        .setGoogleExtrasBundle(extras)
                        .build()
                    loadInto(
                        activity = activity,
                        adView = adView,
                        frameLayout = frameLayout,
                        shimmerFrameLayout = shimmerFrameLayout,
                        adUnitId = adUnitId,
                        request = adRequest,
                        analyticsLabel = "banner_collapsable_ad",
                        revenueLabel = "CollapsableBanner",
                        failureLog = "Collapsible Banner Ad - onAdFailedToLoad",
                        successLog = "Collapsible Banner Ad - onAdLoaded",
                    )
                }
            }
            if (frameLayout.width > 0) start() else frameLayout.post(start)
        } catch (e: Exception) {

            shimmerFrameLayout?.stopShimmer()
            shimmerFrameLayout?.visibility = View.GONE
            frameLayout.visibility = View.GONE
            Log.d(TAG, "loadMediumRectangle: ${e.message}")
        }
    }
    
    /**
     * Cleans up resources and cancels all coroutines.
     */
    fun destroy() {
        coroutineScope.cancel()
        // Next-Gen banners hold their ad until the AdView is destroyed; leaving them attached to
        // dead frames keeps the refresh loop alive.
        adViewsByFrame.values.forEach { it.destroy() }
        adViewsByFrame.clear()
        observedFrames.clear()
    }
}