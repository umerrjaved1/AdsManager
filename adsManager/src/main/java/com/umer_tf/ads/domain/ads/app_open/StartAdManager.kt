package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_DISMISSED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_SHOWN
import com.umer_tf.ads.domain.utils.AnalyticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class StartAdManager(
    private val application: Application,
    private val adController: AdController
) {
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var loadTime: Long = 0

    companion object {
        private const val TAG = "AdsManager_AppOpen_Start"
        private const val AD_EXPIRATION_TIME_MS = 4 * 60 * 60 * 1000L // 4 hours
    }

    fun loadAd(context: Context, onAdLoaded: ((Boolean) -> Unit)? = null) {
        Log.e(TAG, "StartAdManager: loadAd requested")
        if (isAdAvailable() || isLoadingAd) {
            Log.e(TAG, "StartAdManager: loadAd skipped (already available or loading)")
            onAdLoaded?.invoke(false)
            return
        }

        isLoadingAd = true
        val request = AdRequest.Builder().build()

        AppOpenAd.load(
            context,
            adController.appOpenAdStartId,
            request,
            createLoadCallback(onAdLoaded)
        )
    }

    fun showAd(
        activity: Activity?,
        onShowAdCompleteListener: ((Boolean) -> Unit)? = null,
        onStateChange: (Boolean) -> Unit
    ) {
        Log.e(TAG, "StartAdManager: showAd requested")
        if (activity == null || !isAdAvailable()) {
            Log.e(TAG, "StartAdManager: showAd skipped (activity$activity  isAdAvailable() ${isAdAvailable()})")
            onShowAdCompleteListener?.invoke(false)
            return
        }

        setupAdCallbacks(onShowAdCompleteListener, onStateChange)
        onStateChange(true)
        appOpenAd?.show(activity)
    }

    fun isAdAvailable(): Boolean = appOpenAd != null && !isAdExpired()

    fun destroy() {
        Log.e(TAG, "StartAdManager: destroy called")
        appOpenAd = null
        isLoadingAd = false
        loadTime = 0
    }

    private fun isAdExpired(): Boolean =
        System.currentTimeMillis() - loadTime > AD_EXPIRATION_TIME_MS

    private fun createLoadCallback(onAdLoaded: ((Boolean) -> Unit)?) =
        object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                appOpenAd = ad
                isLoadingAd = false
                loadTime = System.currentTimeMillis()
                Log.e(TAG, "StartAdManager: onAdLoaded successfully")
                onAdLoaded?.invoke(true)
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                isLoadingAd = false
                Log.e(TAG, "StartAdManager: onAdFailedToLoad error=${loadAdError.message}")
                onAdLoaded?.invoke(false)
            }
        }

    private fun setupAdCallbacks(
        onShowAdCompleteListener: ((Boolean) -> Unit)?,
        onStateChange: (Boolean) -> Unit
    ) {
        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                onStateChange(false)
                Log.e(TAG, "StartAdManager: onAdDismissedFullScreenContent")
                AnalyticsManager.getInstance(application)
                    .sendAnalytics(AD_DISMISSED, "OpenAd_Start")
                onShowAdCompleteListener?.invoke(true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appOpenAd = null
                onStateChange(false)
                Log.e(TAG, "StartAdManager: onAdFailedToShowFullScreenContent error=${adError.message}")
                onShowAdCompleteListener?.invoke(false)
            }

            override fun onAdShowedFullScreenContent() {
                AnalyticsManager.getInstance(application).sendAnalytics(AD_SHOWN, "OpenAd_Start")
                Log.e(TAG, "StartAdManager: onAdShowedFullScreenContent")
            }
        }

        appOpenAd?.setOnPaidEventListener { adValue ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AdsAnalytics.logAppsFlyerRevenue(
                        appOpenAd?.adUnitId ?: "",
                        "OpenAd_Start",
                        adValue,
                        application
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to log start ad revenue", e)
                }
            }
        }
    }
}