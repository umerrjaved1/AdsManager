package com.umer_tf.ads.domain.ads.banner

import android.app.Activity
import android.os.Bundle
import android.util.Log
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
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_CLICKED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.Utilities.getAdSize
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * BannerAdLoader is responsible for loading and displaying different types of banner ads
 * with dynamic shimmer layouts formatted to match each ad size.
 */
class BannerAdLoader : IBannerAdLoader {
    private val TAG = "AdsManager_Banner"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @MainThread
    override fun showAdaptiveBanner(
        activity: Activity,
        shimmerFrameLayout: ShimmerFrameLayout?,
        frameLayout: FrameLayout,
        @ValidateAdUnitId adUnitId: String
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "BannerAdLoader: showAdaptiveBanner requested for adUnitId=$adUnitId")
        try {
            if (!shouldShowAd(activity)) {
                shimmerFrameLayout?.stopShimmer()
                shimmerFrameLayout?.visibility = View.GONE
                frameLayout.visibility = View.GONE
                return
            }
            frameLayout.visibility = View.GONE
            val adSize = getAdSize(activity)

            prepareBannerShimmer(activity, shimmerFrameLayout, adSize = adSize)

            val adView = AdView(activity)
            adView.adUnitId = adUnitId
            frameLayout.removeAllViews()
            frameLayout.addView(adView)
            adView.setAdSize(adSize)

            val adRequest = AdRequest.Builder().build()

            adView.adListener = object : AdListener() {
                override fun onAdClicked() {
                    super.onAdClicked()
                    Log.e(TAG, "BannerAdLoader: AdaptiveBanner onAdClicked")
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_CLICKED, "banner_ad")
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    super.onAdFailedToLoad(adError)
                    Log.e(TAG, "BannerAdLoader: AdaptiveBanner onAdFailedToLoad error=${adError.message}")
                    frameLayout.visibility = View.GONE
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_ad")
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()
                    Log.e(TAG, "BannerAdLoader: AdaptiveBanner onAdLoaded successfully")
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED, "banner_ad")
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdsAnalytics.logAppsFlyerRevenue(adView.adUnitId, "Banner", adValue, activity.application)
                }
            }

            adView.loadAd(adRequest)
        } catch (e: Exception) {
            Log.e(TAG, "BannerAdLoader: AdaptiveBanner exception=${e.message}")
            shimmerFrameLayout?.stopShimmer()
            shimmerFrameLayout?.visibility = View.GONE
            frameLayout.visibility = View.GONE
        }
    }

    @MainThread
    override fun showMemRecBanner(
        activity: Activity,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?,
        @ValidateAdUnitId adUnitId: String
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "BannerAdLoader: showMemRecBanner requested for adUnitId=$adUnitId")
        try {
            if (!shouldShowAd(activity)) {
                shimmerFrameLayout?.stopShimmer()
                shimmerFrameLayout?.visibility = View.GONE
                frameLayout.visibility = View.GONE
                return
            }
            frameLayout.visibility = View.GONE
            
            // Medium Rectangle size is 300dp x 250dp
            prepareBannerShimmer(activity, shimmerFrameLayout, targetHeightInDp = 250)

            val adView = AdView(activity)
            adView.adUnitId = adUnitId
            frameLayout.removeAllViews()
            frameLayout.addView(adView)
            adView.setAdSize(AdSize.MEDIUM_RECTANGLE)

            val adRequest = AdRequest.Builder().build()

            adView.adListener = object : AdListener() {
                override fun onAdClicked() {
                    super.onAdClicked()
                    Log.e(TAG, "BannerAdLoader: MemRecBanner onAdClicked")
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_CLICKED, "banner_memrec_ad")
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    super.onAdFailedToLoad(adError)
                    Log.e(TAG, "BannerAdLoader: MemRecBanner onAdFailedToLoad error=${adError.message}")
                    frameLayout.visibility = View.GONE
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_memrec_ad")
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()
                    Log.e(TAG, "BannerAdLoader: MemRecBanner onAdLoaded successfully")
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED, "banner_memrec_ad")
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdsAnalytics.logAppsFlyerRevenue(adView.adUnitId, "MemRecBanner", adValue, activity.application)
                }
            }
            adView.loadAd(adRequest)
        } catch (e: Exception) {
            Log.e(TAG, "BannerAdLoader: MemRecBanner exception=${e.message}")
            shimmerFrameLayout?.stopShimmer()
            shimmerFrameLayout?.visibility = View.GONE
            frameLayout.visibility = View.GONE
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
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "BannerAdLoader: showCollapsableBanner requested for adUnitId=$adUnitId, isTop=$isTop")
        try {
            if (!shouldShowAd(activity)) {
                shimmerFrameLayout?.stopShimmer()
                shimmerFrameLayout?.visibility = View.GONE
                frameLayout.visibility = View.GONE
                return
            }
            frameLayout.visibility = View.GONE
            val adSize = getAdSize(activity)

            prepareBannerShimmer(activity, shimmerFrameLayout, adSize = adSize)

            val adView = AdView(activity)
            adView.adUnitId = adUnitId
            frameLayout.removeAllViews()
            frameLayout.addView(adView)
            adView.setAdSize(adSize)

            val extras = Bundle()
            if (isTop) extras.putString("collapsible", "top")
            else extras.putString("collapsible", "bottom")
            val adRequest = AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build()

            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    super.onAdFailedToLoad(loadAdError)
                    Log.e(TAG, "BannerAdLoader: CollapsableBanner onAdFailedToLoad error=${loadAdError.message}")
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.GONE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_collapsable_ad")
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()
                    Log.e(TAG, "BannerAdLoader: CollapsableBanner onAdLoaded successfully")
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED, "banner_collapsable_ad")
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    Log.e(TAG, "BannerAdLoader: CollapsableBanner onAdClicked")
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_CLICKED, "banner_collapsable_ad")
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdsAnalytics.logAppsFlyerRevenue(adView.adUnitId, "CollapsableBanner", adValue, activity.application)
                }
            }

            adView.loadAd(adRequest)
        } catch (e: Exception) {
            Log.e(TAG, "BannerAdLoader: CollapsableBanner exception=${e.message}")
            shimmerFrameLayout?.stopShimmer()
            shimmerFrameLayout?.visibility = View.GONE
            frameLayout.visibility = View.GONE
        }
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

        shimmerFrameLayout.layoutParams = shimmerFrameLayout.layoutParams?.apply {
            height = heightPx
        }
        shimmerFrameLayout.startShimmer()
        shimmerFrameLayout.visibility = View.VISIBLE
    }

    fun destroy() {
        Log.e(TAG, "BannerAdLoader: destroy called")
        coroutineScope.cancel()
    }
}