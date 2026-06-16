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
 * BannerAdLoader is responsible for loading and displaying different types of banner ads.
 */
class BannerAdLoader: IBannerAdLoader {
    val TAG = "BannerAdLoader"
    
    // Proper coroutine scope that cancels when loader is destroyed
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
            frameLayout.visibility = View.GONE
            shimmerFrameLayout?.startShimmer()
            shimmerFrameLayout?.visibility = View.VISIBLE

            val adView = AdView(activity)
            adView.adUnitId = adUnitId
            frameLayout.removeAllViews()
            frameLayout.addView(adView)

            val adSize = getAdSize(activity)
            adView.setAdSize(adSize)

            val adRequest = AdRequest.Builder().build()

            adView.adListener = object : AdListener() {
                override fun onAdClicked() {
                    super.onAdClicked()
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_CLICKED, "banner_ad")
                }

                override fun onAdClosed() {
                    super.onAdClosed()
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    super.onAdFailedToLoad(adError)
                    Log.d(TAG, "Monetization :- onBannerAdFailed: ${adError.message}")
                    if (frameLayout.visibility == View.VISIBLE) {
                        frameLayout.visibility = View.GONE
                    }
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_ad")
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()
                    Log.d(TAG, "Monetization :- onBannerAdLoaded:")


                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED,"banner_ad")
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdsAnalytics.logAppsFlyerRevenue(adView.adUnitId, "Banner", adValue, activity.application)
                }
            }

            // Start loading the ad in the background.
            adView.loadAd(adRequest)
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
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        try {
            if (!shouldShowAd(activity)) {
                shimmerFrameLayout?.visibility = View.GONE
                return
            }
            frameLayout.visibility = View.GONE
            shimmerFrameLayout?.startShimmer()
            shimmerFrameLayout?.visibility = View.VISIBLE


            val adView = AdView(activity)
            adView.adUnitId = adUnitId
            frameLayout.removeAllViews()
            frameLayout.addView(adView)

            adView.setAdSize(AdSize.MEDIUM_RECTANGLE)

            val adRequest = AdRequest.Builder().build()

            adView.adListener = object : AdListener() {
                override fun onAdClicked() {
                    super.onAdClicked()
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_CLICKED, "banner_collapsable_ad")
                }

                override fun onAdClosed() {
                    super.onAdClosed()
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    super.onAdFailedToLoad(adError)
                    frameLayout.visibility = View.GONE
                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    Log.d(TAG, "Monetization :- onMediumRectangleAdFailed: ${adError.message}")
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_memrec_ad")
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()

                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    Log.d(TAG, "Monetization :- onMediumRectangleAdLoaded")
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED, "banner_memrec_ad")
                }
            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdsAnalytics.logAppsFlyerRevenue(adView.adUnitId, "MemRecBanner", adValue, activity.application)
                }
            }
            // Start loading the ad in the background.
            adView.loadAd(adRequest)
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
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        try {
            if (!shouldShowAd(activity)) {
                shimmerFrameLayout?.visibility = View.GONE
                return
            }
            frameLayout.visibility = View.GONE
            shimmerFrameLayout?.startShimmer()
            shimmerFrameLayout?.visibility = View.VISIBLE

            val adView = AdView(activity)
            adView.adUnitId = adUnitId
            frameLayout.removeAllViews()
            frameLayout.addView(adView)
            if (frameLayout.visibility == View.GONE) frameLayout.visibility = View.VISIBLE
            val adSize = getAdSize(activity)
            adView.setAdSize(adSize)
            val extras = Bundle()
            if (isTop) extras.putString("collapsible", "top")
            else extras.putString("collapsible", "bottom")
            val adRequest =
                AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build()
            adView.loadAd(adRequest)
            adView.adListener = object : AdListener() {

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    super.onAdFailedToLoad(loadAdError)
                    Log.d(TAG, "Monetization :- Collapsible Banner Ad - onAdFailedToLoad: ${loadAdError.message}")

                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.GONE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_FAILED, "banner_collapsable_ad")
                }

                override fun onAdLoaded() {
                    super.onAdLoaded()
                    Log.d(TAG, "Monetization :- Collapsible Banner Ad - onAdLoaded")

                    shimmerFrameLayout?.stopShimmer()
                    shimmerFrameLayout?.visibility = View.GONE
                    frameLayout.visibility = View.VISIBLE
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_LOADED, "banner_collapsable_ad")
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    AnalyticsManager.getInstance(activity).sendAnalytics(AD_CLICKED, "banner_collapsable_ad")
                }

            }

            adView.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdsAnalytics.logAppsFlyerRevenue(adView.adUnitId, "CollapsableBanner", adValue, activity.application)
                }
            }
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
    }
}