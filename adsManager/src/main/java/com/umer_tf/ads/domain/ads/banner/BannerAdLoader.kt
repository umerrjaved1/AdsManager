package com.umer_tf.ads.domain.ads.banner

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.analytics.AdLoadFailure
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
 *
 * `AdViewModel.bindBanner` forwards the lifecycle for you; prefer it over calling this loader directly.
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
    ) = showBanner(activity, adUnitId, frameLayout, shimmerFrameLayout, BannerAdType.ADAPTIVE)

    @MainThread
    override fun showMemRecBanner(
        activity: Activity,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
        @ValidateAdUnitId adUnitId: String
    ) = showBanner(activity, adUnitId, frameLayout, shimmerFrameLayout, BannerAdType.MEDIUM_RECTANGLE)

    @MainThread
    override fun showCollapsableBanner(
        activity: Activity,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
        @ValidateAdUnitId adUnitId: String,
        isTop: Boolean
    ) = showBanner(
        activity,
        adUnitId,
        frameLayout,
        shimmerFrameLayout,
        if (isTop) BannerAdType.COLLAPSIBLE_TOP else BannerAdType.COLLAPSIBLE_BOTTOM
    )

    /**
     * Loads and shows a banner of [type] into [frameLayout], reporting the outcome to [onResult].
     *
     * The three `show*Banner` methods above are thin wrappers over this. Unlike them, this one tells
     * the caller what happened, which is what `AdViewModel` needs in order to publish banner state -
     * a banner that silently fails is indistinguishable from one still loading.
     *
     * @param shimmer Either a [ShimmerFrameLayout] to drive directly, or an **empty container** the
     *   library inflates the shape-appropriate shimmer into (see [BannerShimmer]). Null for no
     *   placeholder. Whichever it is, the library shows, sizes, stops and hides it - the caller never
     *   touches it.
     * @param onResult Invoked exactly once on the main thread: `(true, null)` on success,
     *   `(false, failure)` on any refusal or load error.
     */
    @MainThread
    @JvmOverloads
    fun showBanner(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        frameLayout: FrameLayout,
        shimmer: ViewGroup? = null,
        type: BannerAdType = BannerAdType.ADAPTIVE,
        onResult: ((Boolean, AdLoadFailure?) -> Unit)? = null
    ) {
        AdsLog.d(TAG, "BannerAdLoader: showBanner type=$type requested for adUnitId=$adUnitId")

        var delivered = false
        val deliverOnce = { success: Boolean, failure: AdLoadFailure? ->
            if (!delivered) {
                delivered = true
                onResult?.invoke(success, failure)
            }
        }

        // Gate before touching the placeholder. Attaching first and hiding on refusal is what makes a
        // premium or consent-blocked user see the shimmer flash for a frame before the slot collapses.
        val refuseBeforeAttach = { reason: String ->
            val failure = AdLoadFailure(AdLoadFailure.CODE_LIBRARY, reason)
            shimmer?.visibility = View.GONE
            (shimmer as? ShimmerFrameLayout)?.stopShimmer()
            frameLayout.visibility = View.GONE
            AdEvents.failedToLoad(activity, adUnitId, type.adType, failure)
            deliverOnce(false, failure)
        }

        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            refuseBeforeAttach("Invalid ad unit id \"$adUnitId\"")
            return
        }
        if (!shouldShowAd(activity)) {
            refuseBeforeAttach("Request blocked: premium user, offline, or consent not granted")
            return
        }

        // The placeholder can live inside the ad container itself, so a caller only has to declare one
        // FrameLayout. That ordering is load-bearing: the AdView has to go in first (attachAdView
        // clears the container, and an AdView must be in the hierarchy to size and render), then the
        // shimmer is layered on top of it and simply hidden once the ad arrives.
        val inContainer = shimmer === frameLayout

        // Declared out here so the catch below can still collapse them.
        var shimmerFrameLayout: ShimmerFrameLayout? = null
        var shimmerHost: ViewGroup? = null

        try {
            // Visible from the start when it hosts the placeholder; otherwise it stays collapsed
            // until the ad actually arrives.
            frameLayout.visibility = if (inContainer) View.VISIBLE else View.GONE

            val adSize = when (type) {
                BannerAdType.MEDIUM_RECTANGLE -> AdSize.MEDIUM_RECTANGLE
                else -> getAdSize(activity)
            }

            val adView = attachAdView(activity, frameLayout, adUnitId)
            adView.setAdSize(adSize)

            // A caller's own ShimmerFrameLayout is used as-is; anything else is an empty host we fill
            // with the shimmer matching this banner shape.
            shimmerFrameLayout = when (shimmer) {
                null -> null
                is ShimmerFrameLayout -> shimmer
                else -> BannerShimmer.attachTo(
                    shimmer,
                    type,
                    NativeAdTheme.auto(activity),
                    clearHost = !inContainer
                )
            }
            // The wrapper to collapse alongside the shimmer - hiding only the inner view leaves its
            // height, padding and margins as a gap. Never the ad container: that holds the banner.
            shimmerHost = shimmer.takeIf { it !== shimmerFrameLayout && it !== frameLayout }

            if (type == BannerAdType.MEDIUM_RECTANGLE) {
                prepareBannerShimmer(activity, shimmerFrameLayout, targetHeightInDp = 250)
            } else {
                prepareBannerShimmer(activity, shimmerFrameLayout, adSize = adSize)
            }

            adView.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    super.onAdLoaded()
                    AdsLog.d(TAG, "BannerAdLoader: $type onAdLoaded successfully")
                    // The host is hidden on success too, not just on failure: leaving it visible
                    // behind the loaded banner keeps its height reserved as blank space.
                    stopShimmer(shimmerFrameLayout, shimmerHost)
                    frameLayout.visibility = View.VISIBLE
                    AdEvents.loaded(activity, adUnitId, type.adType)
                    deliverOnce(true, null)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    super.onAdFailedToLoad(adError)
                    AdsLog.e(TAG, "BannerAdLoader: $type onAdFailedToLoad error=${adError.message}")
                    hideBanner(shimmerFrameLayout, shimmerHost, frameLayout)
                    AdEvents.failedToLoad(activity, adUnitId, type.adType, adError)
                    deliverOnce(
                        false,
                        AdLoadFailure(adError.code, adError.message.orEmpty(), adError.domain)
                    )
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    AdsLog.d(TAG, "BannerAdLoader: $type onAdClicked")
                    AdEvents.clicked(activity, adUnitId, type.adType)
                }

                override fun onAdImpression() {
                    super.onAdImpression()
                    AdsLog.d(TAG, "BannerAdLoader: $type onAdImpression")
                    AdEvents.impression(activity, adUnitId, type.adType)
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdEvents.revenue(activity.application, adUnitId, type.adType, adValue)
                }
            }

            adView.loadAd(buildRequest(type))
        } catch (e: Exception) {
            AdsLog.e(TAG, "BannerAdLoader: $type exception=${e.message}")
            hideBanner(shimmerFrameLayout, shimmerHost, frameLayout)
            deliverOnce(false, AdLoadFailure(AdLoadFailure.CODE_LIBRARY, e.message ?: "Banner failed"))
        }
    }

    private fun buildRequest(type: BannerAdType): AdRequest {
        if (!type.isCollapsible) return AdRequest.Builder().build()
        val extras = Bundle().apply {
            putString("collapsible", if (type == BannerAdType.COLLAPSIBLE_TOP) "top" else "bottom")
        }
        return AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build()
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

    /** Stops the placeholder and collapses both it and any wrapper we inflated it into. */
    private fun stopShimmer(shimmerFrameLayout: ShimmerFrameLayout?, shimmerHost: ViewGroup?) {
        shimmerFrameLayout?.stopShimmer()
        shimmerFrameLayout?.visibility = View.GONE
        shimmerHost?.visibility = View.GONE
    }

    private fun hideBanner(
        shimmerFrameLayout: ShimmerFrameLayout?,
        shimmerHost: ViewGroup?,
        frameLayout: FrameLayout
    ) {
        stopShimmer(shimmerFrameLayout, shimmerHost)
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
