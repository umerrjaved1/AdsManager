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

internal class ResumeAdManager(
    private val application: Application,
    private val adController: AdController
) {
    private var appResumeAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var loadTime: Long = 0

    companion object {
        private const val TAG = "AdsManager_AppOpen_Resume"
        private const val AD_EXPIRATION_TIME_MS = 4 * 60 * 60 * 1000L // 4 hours
    }

    fun loadAd(context: Context, onAdLoaded: ((Boolean) -> Unit)? = null) {
        Log.e(TAG, "ResumeAdManager: loadAd requested")
        if (isAdAvailable() || isLoadingAd) {
            Log.e(TAG, "ResumeAdManager: loadAd skipped (already available or loading)")
            onAdLoaded?.invoke(false)
            return
        }

        isLoadingAd = true
        val request = AdRequest.Builder().build()
        
        AppOpenAd.load(
            context,
            adController.appOpenAdResumeId,
            request,
            createLoadCallback(onAdLoaded)
        )
    }

    fun showAd(
        activity: Activity?,
        onShowAdCompleteListener: ((Boolean) -> Unit)? = null,
        onStateChange: (Boolean) -> Unit
    ) {
        Log.e(TAG, "ResumeAdManager: showAd requested")
        if (activity == null || !isAdAvailable()) {
            Log.e(TAG, "ResumeAdManager: showAd skipped (activity null or ad not available)")
            onShowAdCompleteListener?.invoke(false)
            return
        }

        setupAdCallbacks(onShowAdCompleteListener, onStateChange)
        onStateChange(true)
        appResumeAd?.show(activity)
    }

    fun isAdAvailable(): Boolean = appResumeAd != null && !isAdExpired()

    fun destroy() {
        Log.e(TAG, "ResumeAdManager: destroy called")
        appResumeAd = null
        isLoadingAd = false
        loadTime = 0
    }

    private fun isAdExpired(): Boolean = System.currentTimeMillis() - loadTime > AD_EXPIRATION_TIME_MS

    private fun createLoadCallback(onAdLoaded: ((Boolean) -> Unit)?) = 
        object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                appResumeAd = ad
                isLoadingAd = false
                loadTime = System.currentTimeMillis()
                Log.e(TAG, "ResumeAdManager: onAdLoaded successfully")
                onAdLoaded?.invoke(true)
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                isLoadingAd = false
                Log.e(TAG, "ResumeAdManager: onAdFailedToLoad error=${loadAdError.message}")
                onAdLoaded?.invoke(false)
            }
        }

    private fun setupAdCallbacks(
        onShowAdCompleteListener: ((Boolean) -> Unit)?,
        onStateChange: (Boolean) -> Unit
    ) {
        appResumeAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appResumeAd = null
                onStateChange(false)
                Log.e(TAG, "ResumeAdManager: onAdDismissedFullScreenContent")
                AnalyticsManager.getInstance(application).sendAnalytics(AD_DISMISSED, "OpenAd_Resume")
                onShowAdCompleteListener?.invoke(true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appResumeAd = null
                onStateChange(false)
                Log.e(TAG, "ResumeAdManager: onAdFailedToShowFullScreenContent error=${adError.message}")
                onShowAdCompleteListener?.invoke(false)
            }

            override fun onAdShowedFullScreenContent() {
                AnalyticsManager.getInstance(application).sendAnalytics(AD_SHOWN, "OpenAd_Resume")
                Log.e(TAG, "ResumeAdManager: onAdShowedFullScreenContent")
            }
        }

        appResumeAd?.setOnPaidEventListener { adValue ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AdsAnalytics.logAppsFlyerRevenue(
                        appResumeAd?.adUnitId ?: "",
                        "OpenAd_Resume",
                        adValue,
                        application
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to log resume ad revenue", e)
                }
            }
        }
    }
}