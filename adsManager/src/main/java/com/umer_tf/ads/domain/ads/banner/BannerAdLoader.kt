package com.umer_tf.ads.domain.ads.banner

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.analytics.AdLoadFailure
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_CLICKED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.Utilities.getAdSize
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import com.umer_tf.ads.domain.utils.onMainThread
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
     * The Next-Gen `AdView` has no `pause()`/`resume()` - it ties refreshing to its own attachment
     * and visibility instead, so the pause half of (2) is now the SDK's job. Destroying the replaced
     * and the destroyed-screen AdView is still ours, and is still what stops (1).
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
        // One observer per frame, always acting on whichever AdView is current. Only onDestroy is
        // handled now: the Next-Gen AdView exposes no pause/resume and suspends its own refresh
        // while detached or hidden, so the old onPause/onResume forwarding has nothing to call.
        owner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                adViewsByFrame.remove(frameLayout)?.destroy()
                observedFrames.remove(frameLayout)
                owner.lifecycle.removeObserver(this)
            }
        })
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
        try {
            // A false return means "skip this request": strictMode has already thrown if the host
            // wanted a malformed id to be fatal. Folded into the same refusal as a blocked request
            // so the placeholder is hidden on both paths.
            if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(activity)) {
                shimmerFrameLayout?.visibility = View.GONE
                return
            }
            frameLayout.visibility = View.GONE
            shimmerFrameLayout?.startShimmer()
            shimmerFrameLayout?.visibility = View.VISIBLE

            val adView = AdView(activity)
            attachAdView(activity, frameLayout, adView)

            // Unit id and size are both request properties now: AdView has neither an adUnitId
            // property nor setAdSize().
            val adSize = getAdSize(activity)
            val adRequest = BannerAdRequest.Builder(adUnitId, adSize).build()

            // Load outcome comes from the load callback; click, impression and revenue come from the
            // loaded BannerAd's event callback. Every body hops to Main - these fire on a background
            // thread and touch the shimmer and the ad container.
            adView.loadAd(adRequest, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) = onMainThread {
                    Log.d(TAG, "Monetization :- onBannerAdLoaded:")

                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED,"banner_ad")

                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdClicked() = onMainThread {
                            AnalyticsManager.getInstance(activity)
                                .sendAnalytics(AD_CLICKED, "banner_ad")
                        }

                        override fun onAdPaid(value: AdValue) {
                            coroutineScope.launch {
                                AdsAnalytics.logAppsFlyerRevenue(
                                    adUnitId,
                                    "Banner",
                                    value,
                                    activity.application
                                )
                            }
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                    Log.d(TAG, "Monetization :- onBannerAdFailed: ${adError.message}")
                    if (frameLayout.visibility == View.VISIBLE) {
                        frameLayout.visibility = View.GONE
                    }
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_ad")
                }
            })
        } catch (e: Exception) {
            Log.d(TAG, "Monetization :- loadBanner: ${e.message}")
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
        try {
            // A false return means "skip this request": strictMode has already thrown if the host
            // wanted a malformed id to be fatal. Folded into the same refusal as a blocked request
            // so the placeholder is hidden on both paths.
            if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(activity)) {
                shimmerFrameLayout?.visibility = View.GONE
                return
            }
            frameLayout.visibility = View.GONE
            shimmerFrameLayout?.startShimmer()
            shimmerFrameLayout?.visibility = View.VISIBLE


            val adView = AdView(activity)
            attachAdView(activity, frameLayout, adView)

            val adRequest = BannerAdRequest.Builder(adUnitId, AdSize.MEDIUM_RECTANGLE).build()

            adView.loadAd(adRequest, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) = onMainThread {
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    Log.d(TAG, "Monetization :- onMediumRectangleAdLoaded")
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED, "banner_memrec_ad")

                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdClicked() = onMainThread {
                            AnalyticsManager.getInstance(activity)
                                .sendAnalytics(AD_CLICKED, "banner_collapsable_ad")
                        }

                        override fun onAdPaid(value: AdValue) {
                            coroutineScope.launch {
                                AdsAnalytics.logAppsFlyerRevenue(
                                    adUnitId,
                                    "MemRecBanner",
                                    value,
                                    activity.application
                                )
                            }
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                    frameLayout.visibility = View.GONE
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    Log.d(TAG, "Monetization :- onMediumRectangleAdFailed: ${adError.message}")
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_memrec_ad")
                }
            })
        } catch (e: Exception) {
            shimmerFrameLayout?.stopShimmer()
            shimmerFrameLayout?.visibility = View.GONE
            frameLayout.visibility = View.GONE
            Log.d(TAG, "loadMediumRectangle: ${e.message}")
        }
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
        try {
            // A false return means "skip this request": strictMode has already thrown if the host
            // wanted a malformed id to be fatal. Folded into the same refusal as a blocked request
            // so the placeholder is hidden on both paths.
            if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(activity)) {
                shimmerFrameLayout?.visibility = View.GONE
                return
            }
            frameLayout.visibility = View.GONE
            shimmerFrameLayout?.startShimmer()
            shimmerFrameLayout?.visibility = View.VISIBLE

            val adView = AdView(activity)
            attachAdView(activity, frameLayout, adView)
            if (frameLayout.visibility == View.GONE) frameLayout.visibility = View.VISIBLE
            val adSize = getAdSize(activity)
            val extras = Bundle()
            if (isTop) extras.putString("collapsible", "top")
            else extras.putString("collapsible", "bottom")
            // addNetworkExtrasBundle(AdMobAdapter::class.java, ...) is gone; extras destined for
            // Google itself go through setGoogleExtrasBundle, so the AdMobAdapter class reference is
            // no longer needed. The "collapsible" key is unchanged.
            val adRequest = BannerAdRequest.Builder(adUnitId, adSize)
                .setGoogleExtrasBundle(extras)
                .build()

            adView.loadAd(adRequest, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) = onMainThread {
                    Log.d(TAG, "Monetization :- Collapsible Banner Ad - onAdLoaded")

                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED, "banner_collapsable_ad")

                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdClicked() = onMainThread {
                            AnalyticsManager.getInstance(activity)
                                .sendAnalytics(AD_CLICKED, "banner_collapsable_ad")
                        }

                        override fun onAdPaid(value: AdValue) {
                            coroutineScope.launch {
                                AdsAnalytics.logAppsFlyerRevenue(
                                    adUnitId,
                                    "CollapsableBanner",
                                    value,
                                    activity.application
                                )
                            }
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                    Log.d(TAG, "Monetization :- Collapsible Banner Ad - onAdFailedToLoad: ${adError.message}")

                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.GONE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_collapsable_ad")
                }
            })
        } catch (e: Exception) {

            shimmerFrameLayout?.stopShimmer()
            shimmerFrameLayout?.visibility = View.GONE
            frameLayout.visibility = View.GONE
            Log.d(TAG, "loadMediumRectangle: ${e.message}")
        }
    }
    
    /**
     * Loads and shows a banner of any [BannerAdType] into [container], reporting the outcome once.
     *
     * The data-driven form of the three `show*Banner` methods above, which differ only in ad size and
     * request extras. Describing a banner as `adUnitId` + shape is what lets [AdViewModel] drive one
     * without holding a View, and the outcome callback is what gives it a state to publish - the
     * older methods report nothing, so a caller cannot tell a fill from a no-fill.
     *
     * @param shimmerHost Where the placeholder goes. Defaults to [container], so one FrameLayout is
     *   enough; pass a separate container to put it elsewhere, an existing [ShimmerFrameLayout] to
     *   keep your own, or null for no placeholder. The library starts and stops it either way.
     * @param onOutcome Invoked exactly once, on the main thread, on every terminal path - fill,
     *   no-fill, malformed id, blocked request, exception.
     */
    @MainThread
    @JvmOverloads
    fun showBanner(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        container: FrameLayout,
        shimmerHost: ViewGroup? = container,
        type: BannerAdType = BannerAdType.ADAPTIVE,
        onOutcome: ((Boolean, AdLoadFailure?) -> Unit)? = null
    ) {
        val adType = type.adType

        // Every refusal below is decided before anything is attached: starting the shimmer first and
        // then discovering the request is blocked shows a one-frame flash in a slot that will stay
        // empty. A false return means "skip": strictMode has already thrown if the host wanted
        // a malformed id to be fatal, so here it reports through onOutcome instead.
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            failBanner(activity, container, shimmerHost, null, adUnitId, adType,
                "Malformed ad unit id \"$adUnitId\"", onOutcome)
            return
        }
        if (!shouldShowAd(activity)) {
            failBanner(activity, container, shimmerHost, null, adUnitId, adType,
                "Request blocked: premium, offline or consent-gated", onOutcome)
            return
        }

        try {
            val adView = AdView(activity)
            // The AdView has to be in the hierarchy before it can size itself, and attachAdView
            // clears the container - so it goes in first and the shimmer is layered over it, rather
            // than the native case's replace-then-swap.
            attachAdView(activity, container, adView)
            container.visibility = View.VISIBLE

            val shimmer = resolveShimmer(shimmerHost, container, type)

            val adSize = if (type == BannerAdType.MEDIUM_RECTANGLE) {
                AdSize.MEDIUM_RECTANGLE
            } else {
                getAdSize(activity)
            }
            val requestBuilder = BannerAdRequest.Builder(adUnitId, adSize)
            if (type.isCollapsible) {
                requestBuilder.setGoogleExtrasBundle(Bundle().apply {
                    putString("collapsible", if (type == BannerAdType.COLLAPSIBLE_TOP) "top" else "bottom")
                })
            }

            adView.loadAd(requestBuilder.build(), object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) = onMainThread {
                    hideShimmer(shimmer)
                    container.visibility = View.VISIBLE
                    AdEvents.loaded(activity, adUnitId, adType)

                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdImpression() = onMainThread {
                            AdEvents.impression(activity, adUnitId, adType)
                        }

                        override fun onAdClicked() = onMainThread {
                            AdEvents.clicked(activity, adUnitId, adType)
                        }

                        override fun onAdPaid(value: AdValue) {
                            AdEvents.revenue(activity.applicationContext, adUnitId, adType, value)
                        }
                    }
                    onOutcome?.invoke(true, null)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                    hideShimmer(shimmer)
                    container.visibility = View.GONE
                    AdEvents.failedToLoad(activity, adUnitId, adType, adError)
                    onOutcome?.invoke(false, AdLoadFailure(adError.code.value, adError.message))
                }
            })
        } catch (e: Exception) {
            failBanner(activity, container, shimmerHost, null, adUnitId, adType,
                "showBanner threw: ${e.message}", onOutcome)
        }
    }

    /**
     * Resolves the placeholder for a slot: a caller's own [ShimmerFrameLayout] is used as-is, any
     * other container has the shimmer for [type] inflated into it.
     *
     * When the host *is* the ad container it already holds the AdView, so the shimmer is added on top
     * instead of replacing it.
     */
    private fun resolveShimmer(
        shimmerHost: ViewGroup?,
        container: FrameLayout,
        type: BannerAdType
    ): ShimmerFrameLayout? {
        if (shimmerHost == null) return null
        if (shimmerHost is ShimmerFrameLayout) {
            shimmerHost.visibility = View.VISIBLE
            shimmerHost.startShimmer()
            return shimmerHost
        }
        return BannerShimmer.attachTo(shimmerHost, type, clearHost = shimmerHost !== container)
    }

    private fun hideShimmer(shimmer: ShimmerFrameLayout?) {
        shimmer ?: return
        shimmer.stopShimmer()
        shimmer.visibility = View.GONE
    }

    /** One terminal failure path: collapse the slot, report it, settle the caller. */
    private fun failBanner(
        activity: Activity,
        container: FrameLayout,
        shimmerHost: ViewGroup?,
        shimmer: ShimmerFrameLayout?,
        adUnitId: String,
        adType: AdType,
        reason: String,
        onOutcome: ((Boolean, AdLoadFailure?) -> Unit)?
    ) {
        Log.d(TAG, "Monetization :- showBanner refused: $reason")
        hideShimmer(shimmer ?: shimmerHost as? ShimmerFrameLayout)
        // A separate shimmer wrapper keeps its height and margins when merely emptied, leaving a gap
        // where the ad would have been.
        if (shimmerHost !== container) shimmerHost?.visibility = View.GONE
        container.visibility = View.GONE
        AdEvents.failedToLoad(activity, adUnitId, adType, reason)
        onOutcome?.invoke(false, AdLoadFailure(AdLoadFailure.CODE_LIBRARY, reason))
    }

    /**
     * Destroys the banner held by [container] and empties it.
     *
     * The per-frame Activity observer already does this on `onDestroy`; this is for a slot that goes
     * away while its Activity lives on, which the observer never sees.
     */
    @MainThread
    fun destroyFor(container: FrameLayout) {
        adViewsByFrame.remove(container)?.destroy()
        container.removeAllViews()
        container.visibility = View.GONE
    }

    /**
     * Cleans up resources and cancels all coroutines.
     */
    fun destroy() {
        adViewsByFrame.values.forEach { runCatching { it.destroy() } }
        adViewsByFrame.clear()
        coroutineScope.cancel()
    }
}