package com.umer_tf.ads.domain.ads.banner

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.MainThread
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.umer_tf.ads.domain.ads.native_ad.NativeAdTheme
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.utils.AdsLog
import com.umer_tf.ads.domain.utils.Utilities.getAdSize
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * BannerAdLoader is responsible for loading and displaying different types of banner ads
 * with dynamic shimmer layouts formatted to match each ad size.
 *
 * ### Lifecycle
 * A banner keeps auto-refreshing for as long as it is attached, so hosts must forward their own
 * lifecycle:
 *
 * ```kotlin
 * override fun onPause()   { bannerAdLoader.pause();   super.onPause() }
 * override fun onResume()  { super.onResume();         bannerAdLoader.resume() }
 * override fun onDestroy() { bannerAdLoader.destroy(); super.onDestroy() }
 * ```
 *
 * Without this the [AdView] refreshes while the screen is backgrounded - billing impressions nobody
 * sees - and holds its Activity alive.
 */
class BannerAdLoader : IBannerAdLoader {
    private val TAG = "AdsManager_Banner"

    // Recreated by destroy() - a cancelled scope stays cancelled and would drop later revenue events.
    private var coroutineScope = newScope()

    /**
     * Live AdViews keyed by the container they live in.
     *
     * Weak keys so a container from a destroyed screen cannot be leaked by this map alone; the
     * AdViews themselves are still destroyed explicitly, since the SDK holds its own references.
     */
    private val activeAdViews = WeakHashMap<FrameLayout, AdView>()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @MainThread
    override fun showAdaptiveBanner(
        activity: Activity,
        shimmerFrameLayout: ShimmerFrameLayout?,
        frameLayout: FrameLayout,
        @ValidateAdUnitId adUnitId: String
    ) {
        AdsLog.d(TAG, "BannerAdLoader: showAdaptiveBanner requested for adUnitId=$adUnitId")
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            hideBanner(shimmerFrameLayout, frameLayout)
            return
        }
        try {
            if (!shouldShowAd(activity)) {
                hideBanner(shimmerFrameLayout, frameLayout)
                return
            }
            frameLayout.visibility = View.GONE
            val adSize = getAdSize(activity)

            prepareBannerShimmer(activity, shimmerFrameLayout, adSize = adSize)

            val adView = attachAdView(activity, frameLayout, adUnitId)
            adView.setAdSize(adSize)

            val adRequest = AdRequest.Builder().build()

            adView.adListener = object : AdListener() {
                override fun onAdClicked() {
                    super.onAdClicked()
                    AdsLog.d(TAG, "BannerAdLoader: AdaptiveBanner onAdClicked")
                    AdEvents.clicked(activity, adUnitId, AdType.BANNER)
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    AdsLog.d(TAG, "BannerAdLoader: AdaptiveBanner onAdImpression")
                    AdEvents.impression(activity, adUnitId, AdType.BANNER)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    super.onAdFailedToLoad(adError)
                    AdsLog.e(TAG, "BannerAdLoader: AdaptiveBanner onAdFailedToLoad error=${adError.message}")
                    hideBanner(shimmerFrameLayout, frameLayout)
                    AdEvents.failedToLoad(activity, adUnitId, AdType.BANNER, adError)
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()
                    AdsLog.d(TAG, "BannerAdLoader: AdaptiveBanner onAdLoaded successfully")
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AdEvents.loaded(activity, adUnitId, AdType.BANNER)
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdEvents.revenue(activity.application, adView.adUnitId, AdType.BANNER, adValue)
                }
            }

            adView.loadAd(adRequest)
        } catch (e: Exception) {
            AdsLog.e(TAG, "BannerAdLoader: AdaptiveBanner exception=${e.message}")
            hideBanner(shimmerFrameLayout, frameLayout)
        }
    }

    @MainThread
    override fun showMemRecBanner(
        activity: Activity,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
        @ValidateAdUnitId adUnitId: String
    ) {
        AdsLog.d(TAG, "BannerAdLoader: showMemRecBanner requested for adUnitId=$adUnitId")
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            hideBanner(shimmerFrameLayout, frameLayout)
            return
        }
        try {
            if (!shouldShowAd(activity)) {
                hideBanner(shimmerFrameLayout, frameLayout)
                return
            }
            frameLayout.visibility = View.GONE

            // Medium Rectangle size is 300dp x 250dp
            prepareBannerShimmer(activity, shimmerFrameLayout, targetHeightInDp = 250)

            val adView = attachAdView(activity, frameLayout, adUnitId)
            adView.setAdSize(AdSize.MEDIUM_RECTANGLE)

            val adRequest = AdRequest.Builder().build()

            adView.adListener = object : AdListener() {
                override fun onAdClicked() {
                    super.onAdClicked()
                    AdsLog.d(TAG, "BannerAdLoader: MemRecBanner onAdClicked")
                    AdEvents.clicked(activity, adUnitId, AdType.BANNER_MEDIUM_RECTANGLE)
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    AdsLog.d(TAG, "BannerAdLoader: MemRecBanner onAdImpression")
                    AdEvents.impression(activity, adUnitId, AdType.BANNER_MEDIUM_RECTANGLE)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    super.onAdFailedToLoad(adError)
                    AdsLog.e(TAG, "BannerAdLoader: MemRecBanner onAdFailedToLoad error=${adError.message}")
                    hideBanner(shimmerFrameLayout, frameLayout)
                    AdEvents.failedToLoad(activity, adUnitId, AdType.BANNER_MEDIUM_RECTANGLE, adError)
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()
                    AdsLog.d(TAG, "BannerAdLoader: MemRecBanner onAdLoaded successfully")
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AdEvents.loaded(activity, adUnitId, AdType.BANNER_MEDIUM_RECTANGLE)
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdEvents.revenue(activity.application, adView.adUnitId, AdType.BANNER_MEDIUM_RECTANGLE, adValue)
                }
            }
            adView.loadAd(adRequest)
        } catch (e: Exception) {
            AdsLog.e(TAG, "BannerAdLoader: MemRecBanner exception=${e.message}")
            hideBanner(shimmerFrameLayout, frameLayout)
        }
    }

    @MainThread
    override fun showCollapsableBanner(
        activity: Activity,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
        @ValidateAdUnitId adUnitId: String,
        isTop: Boolean
    ) {
        AdsLog.d(TAG, "BannerAdLoader: showCollapsableBanner requested for adUnitId=$adUnitId, isTop=$isTop")
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            hideBanner(shimmerFrameLayout, frameLayout)
            return
        }
        try {
            if (!shouldShowAd(activity)) {
                hideBanner(shimmerFrameLayout, frameLayout)
                return
            }
            frameLayout.visibility = View.GONE
            val adSize = getAdSize(activity)

            prepareBannerShimmer(activity, shimmerFrameLayout, adSize = adSize)

            val adView = attachAdView(activity, frameLayout, adUnitId)
            adView.setAdSize(adSize)

            val extras = Bundle()
            if (isTop) extras.putString("collapsible", "top")
            else extras.putString("collapsible", "bottom")
            val adRequest = AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build()

            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    super.onAdFailedToLoad(loadAdError)
                    AdsLog.e(TAG, "BannerAdLoader: CollapsableBanner onAdFailedToLoad error=${loadAdError.message}")
                    hideBanner(shimmerFrameLayout, frameLayout)
                    AdEvents.failedToLoad(activity, adUnitId, AdType.BANNER_COLLAPSIBLE, loadAdError)
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()
                    AdsLog.d(TAG, "BannerAdLoader: CollapsableBanner onAdLoaded successfully")
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AdEvents.loaded(activity, adUnitId, AdType.BANNER_COLLAPSIBLE)
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    AdsLog.d(TAG, "BannerAdLoader: CollapsableBanner onAdClicked")
                    AdEvents.clicked(activity, adUnitId, AdType.BANNER_COLLAPSIBLE)
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    AdsLog.d(TAG, "BannerAdLoader: CollapsableBanner onAdImpression")
                    AdEvents.impression(activity, adUnitId, AdType.BANNER_COLLAPSIBLE)
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdEvents.revenue(activity.application, adView.adUnitId, AdType.BANNER_COLLAPSIBLE, adValue)
                }
            }

            adView.loadAd(adRequest)
        } catch (e: Exception) {
            AdsLog.e(TAG, "BannerAdLoader: CollapsableBanner exception=${e.message}")
            hideBanner(shimmerFrameLayout, frameLayout)
        }
    }

    /**
     * Creates the AdView for [frameLayout], destroying whatever was there before.
     *
     * Each show* call used to `new` an AdView and only detach the previous one from the container, so
     * every re-show left an undestroyed banner refreshing in the background.
     */
    @MainThread
    private fun attachAdView(
        activity: Activity,
        frameLayout: FrameLayout,
        adUnitId: String
    ): AdView {
        destroyFor(frameLayout)
        val adView = AdView(activity)
        adView.adUnitId = adUnitId
        frameLayout.removeAllViews()
        frameLayout.addView(adView)
        activeAdViews[frameLayout] = adView
        return adView
    }

    private fun hideBanner(shimmerFrameLayout: ShimmerFrameLayout?, frameLayout: FrameLayout) {
        shimmerFrameLayout?.stopShimmer()
        shimmerFrameLayout?.visibility = View.GONE
        frameLayout.visibility = View.GONE
    }

    private fun prepareBannerShimmer(
        activity: Activity,
        shimmerFrameLayout: ShimmerFrameLayout?,
        targetHeightInDp: Int? = null,
        adSize: AdSize? = null
    ) {
        if (shimmerFrameLayout == null) return
        val theme = NativeAdTheme.auto(activity)
        theme.applyShimmerTo(shimmerFrameLayout)

        val density = activity.resources.displayMetrics.density
        val heightPx = when {
            targetHeightInDp != null -> (targetHeightInDp * density).toInt()
            adSize != null -> adSize.getHeightInPixels(activity).takeIf { it > 0 } ?: (50 * density).toInt()
            else -> (50 * density).toInt()
        }

        // Assigning layoutParams?.apply{} wiped the params whenever they were still null (a shimmer
        // that has not been laid out yet), so guard instead of assigning null back.
        shimmerFrameLayout.layoutParams?.let { params ->
            params.height = heightPx
            shimmerFrameLayout.layoutParams = params
        } ?: run {
            shimmerFrameLayout.minimumHeight = heightPx
        }
        shimmerFrameLayout.startShimmer()
        shimmerFrameLayout.visibility = View.VISIBLE
    }

    /** Suspends refreshing for every live banner. Call from the host's `onPause`. */
    @MainThread
    fun pause() {
        activeAdViews.values.forEach { runCatching { it.pause() } }
    }

    /** Resumes refreshing for every live banner. Call from the host's `onResume`. */
    @MainThread
    fun resume() {
        activeAdViews.values.forEach { runCatching { it.resume() } }
    }

    /** Destroys the banner inside [frameLayout], if any. Use when a single slot goes away. */
    @MainThread
    fun destroyFor(frameLayout: FrameLayout) {
        activeAdViews.remove(frameLayout)?.let { adView ->
            runCatching {
                (adView.parent as? FrameLayout)?.removeView(adView)
                adView.destroy()
            }
        }
    }

    @MainThread
    fun destroy() {
        AdsLog.d(TAG, "BannerAdLoader: destroy called, releasing ${activeAdViews.size} AdView(s)")
        activeAdViews.values.forEach { adView ->
            runCatching {
                (adView.parent as? FrameLayout)?.removeView(adView)
                adView.destroy()
            }
        }
        activeAdViews.clear()
        coroutineScope.cancel()
        coroutineScope = newScope()
    }
}
