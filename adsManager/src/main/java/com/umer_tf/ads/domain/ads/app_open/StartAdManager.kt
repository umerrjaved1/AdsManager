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

internal class StartAdManager(
    private val application: Application,
    private val adController: AdController
) {
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var loadTime: Long = 0

    companion object {
        private const val TAG = "AdsManager_AppOpen_Start"
    }

    fun loadAd(context: Context, onAdLoaded: ((Boolean) -> Unit)? = null) {
        AdsLog.d(TAG, "StartAdManager: loadAd requested")
        if (isAdAvailable() || isLoadingAd) {
            AdsLog.e(TAG, "StartAdManager: loadAd skipped (already available or loading)")
            onAdLoaded?.invoke(false)
            return
        }
        // App open ads used to bypass this gate entirely, so they still requested for premium users,
        // offline, and before consent was gathered.
        if (!shouldShowAd(context)) {
            AdsLog.e(TAG, "StartAdManager: loadAd skipped (shouldShowAd returns false)")
            onAdLoaded?.invoke(false)
            return
        }
        if (!AdUnitIdValidator.validateAdUnitId(adController.appOpenAdStartId)) {
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
        AdsLog.d(TAG, "StartAdManager: showAd requested")
        if (activity == null || !isAdAvailable()) {
            AdsLog.e(TAG, "StartAdManager: showAd skipped (activity$activity  isAdAvailable() ${isAdAvailable()})")
            onShowAdCompleteListener?.invoke(false)
            return
        }

        setupAdCallbacks(onShowAdCompleteListener, onStateChange)
        onStateChange(true)
        appOpenAd?.show(activity)
    }

    fun isAdAvailable(): Boolean = appOpenAd != null && !isAdExpired()

    fun destroy() {
        AdsLog.d(TAG, "StartAdManager: destroy called")
        appOpenAd = null
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
                appOpenAd = ad
                isLoadingAd = false
                loadTime = System.currentTimeMillis()
                AdsLog.d(TAG, "StartAdManager: onAdLoaded successfully")
                AdEvents.loaded(application, adController.appOpenAdStartId, AdType.APP_OPEN_START)
                onAdLoaded?.invoke(true)
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                isLoadingAd = false
                AdsLog.e(TAG, "StartAdManager: onAdFailedToLoad error=${loadAdError.message}")
                AdEvents.failedToLoad(application, adController.appOpenAdStartId, AdType.APP_OPEN_START, loadAdError)
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
                AdsLog.d(TAG, "StartAdManager: onAdDismissedFullScreenContent")
                AdEvents.dismissed(application, adController.appOpenAdStartId, AdType.APP_OPEN_START)
                onShowAdCompleteListener?.invoke(true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appOpenAd = null
                onStateChange(false)
                AdsLog.e(TAG, "StartAdManager: onAdFailedToShowFullScreenContent error=${adError.message}")
                AdEvents.failedToLoad(application, adController.appOpenAdStartId, AdType.APP_OPEN_START, adError)
                onShowAdCompleteListener?.invoke(false)
            }

            override fun onAdShowedFullScreenContent() {
                AdEvents.showed(application, adController.appOpenAdStartId, AdType.APP_OPEN_START)
                AdsLog.d(TAG, "StartAdManager: onAdShowedFullScreenContent")
            }

            override fun onAdClicked() {
                super.onAdClicked()
                AdsLog.d(TAG, "StartAdManager: onAdClicked")
                AdEvents.clicked(application, adController.appOpenAdStartId, AdType.APP_OPEN_START)
            }
        }

        appOpenAd?.setOnPaidEventListener { adValue ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AdEvents.revenue(application, appOpenAd?.adUnitId ?: "", AdType.APP_OPEN_START, adValue)
                } catch (e: Exception) {
                    AdsLog.e(TAG, "Failed to log start ad revenue", e)
                }
            }
        }
    }
}