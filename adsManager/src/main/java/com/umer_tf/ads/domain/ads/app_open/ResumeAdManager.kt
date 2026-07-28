package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AdsLog
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
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
    }

    fun loadAd(context: Context, onAdLoaded: ((Boolean) -> Unit)? = null) {
        AdsLog.d(TAG, "ResumeAdManager: loadAd requested")
        if (isAdAvailable() || isLoadingAd) {
            AdsLog.e(TAG, "ResumeAdManager: loadAd skipped (already available or loading)")
            onAdLoaded?.invoke(false)
            return
        }
        // App open ads used to bypass this gate entirely, so they still requested for premium users,
        // offline, and before consent was gathered.
        if (!shouldShowAd(context)) {
            AdsLog.e(TAG, "ResumeAdManager: loadAd skipped (shouldShowAd returns false)")
            onAdLoaded?.invoke(false)
            return
        }
        if (!AdUnitIdValidator.validateAdUnitId(adController.appOpenAdResumeId)) {
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
        AdsLog.d(TAG, "ResumeAdManager: showAd requested")
        if (activity == null || !isAdAvailable()) {
            AdsLog.e(TAG, "ResumeAdManager: showAd skipped (activity null or ad not available)")
            onShowAdCompleteListener?.invoke(false)
            return
        }

        setupAdCallbacks(onShowAdCompleteListener, onStateChange)
        onStateChange(true)
        appResumeAd?.show(activity)
    }

    fun isAdAvailable(): Boolean = appResumeAd != null && !isAdExpired()

    fun destroy() {
        AdsLog.d(TAG, "ResumeAdManager: destroy called")
        appResumeAd = null
        isLoadingAd = false
        loadTime = 0
    }

    // TTL lives on AdController so it can be tuned instead of being a private constant.
    // Default is unchanged at 4 hours.
    private fun isAdExpired(): Boolean =
        System.currentTimeMillis() - loadTime > adController.appOpenAdTtlMs

    private fun createLoadCallback(onAdLoaded: ((Boolean) -> Unit)?) = 
        object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                appResumeAd = ad
                isLoadingAd = false
                loadTime = System.currentTimeMillis()
                AdsLog.d(TAG, "ResumeAdManager: onAdLoaded successfully")
                AdEvents.loaded(application, adController.appOpenAdResumeId, AdType.APP_OPEN_RESUME)
                onAdLoaded?.invoke(true)
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                isLoadingAd = false
                AdsLog.e(TAG, "ResumeAdManager: onAdFailedToLoad error=${loadAdError.message}")
                AdEvents.failedToLoad(application, adController.appOpenAdResumeId, AdType.APP_OPEN_RESUME, loadAdError)
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
                AdsLog.d(TAG, "ResumeAdManager: onAdDismissedFullScreenContent")
                AdEvents.dismissed(application, adController.appOpenAdResumeId, AdType.APP_OPEN_RESUME)
                onShowAdCompleteListener?.invoke(true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appResumeAd = null
                onStateChange(false)
                AdsLog.e(TAG, "ResumeAdManager: onAdFailedToShowFullScreenContent error=${adError.message}")
                AdEvents.failedToLoad(application, adController.appOpenAdResumeId, AdType.APP_OPEN_RESUME, adError)
                onShowAdCompleteListener?.invoke(false)
            }

            override fun onAdShowedFullScreenContent() {
                AdEvents.showed(application, adController.appOpenAdResumeId, AdType.APP_OPEN_RESUME)
                AdsLog.d(TAG, "ResumeAdManager: onAdShowedFullScreenContent")
            }

            override fun onAdClicked() {
                super.onAdClicked()
                AdsLog.d(TAG, "ResumeAdManager: onAdClicked")
                AdEvents.clicked(application, adController.appOpenAdResumeId, AdType.APP_OPEN_RESUME)
            }
        }

        appResumeAd?.setOnPaidEventListener { adValue ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AdEvents.revenue(application, appResumeAd?.adUnitId ?: "", AdType.APP_OPEN_RESUME, adValue)
                } catch (e: Exception) {
                    AdsLog.e(TAG, "Failed to log resume ad revenue", e)
                }
            }
        }
    }
}