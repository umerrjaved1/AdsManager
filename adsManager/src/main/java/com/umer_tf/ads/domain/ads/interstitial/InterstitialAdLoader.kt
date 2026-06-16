package com.umer_tf.ads.domain.ads.interstitial

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_CLICKED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_DISMISSED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_SHOWN
import com.umer_tf.ads.domain.utils.AnalyticsConstants.SHOWING_AD
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.LoadingDialogUtil
import com.umer_tf.ads.domain.utils.TimeManager
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InterstitialAdLoader(
    private val context: Context,
    private val adController: AdController
) : IInterstitialAdLoader {
    private val TAG = "InterstitialAdLoader"
    private var interstitialAd: InterstitialAd? = null
    private var mInterstitialAdCounter: Int = 0
    private var loadingDialogUtil: LoadingDialogUtil? = null

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    @JvmField
    var adShowDelay: Long = 1000L

    @MainThread
    override fun loadAd(
        @ValidateAdUnitId adUnitId: String,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        if (interstitialAd != null) {
            onSuccessListener?.onSuccess(true)
            return
        }
        if (!shouldShowAd(context)) {
            onSuccessListener?.onSuccess(false)
            return
        }
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
                onSuccessListener?.onSuccess(true)
                Log.d(TAG, "Monetization :- onAdLoaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "Interstitial_ad")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                onSuccessListener?.onSuccess(false)
                Log.d(TAG, "Monetization :- onAdFailedToLoad: ${error.message}")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "Interstitial_ad")
            }
        })
    }

    @MainThread
    override fun loadAdWithTimeOut(
        @ValidateAdUnitId adUnitId: String,
        timeOut: Long,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        var mOnSuccessListener: OnSuccessListener<Boolean>? = onSuccessListener
        if (!shouldShowAd(context)) {
            mOnSuccessListener?.onSuccess(false)
            return
        }
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
                mOnSuccessListener?.onSuccess(true)
                mOnSuccessListener = null
                Log.d(TAG, "Monetization :- onAdLoaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "Interstitial_ad")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                mOnSuccessListener?.onSuccess(false)
                mOnSuccessListener = null
                Log.d(TAG, "Monetization :- onAdFailedToLoad: ${error.message}")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "Interstitial_ad")
            }
        })
        job = coroutineScope.launch {
            delay(timeOut)
            if (interstitialAd == null) {
                mOnSuccessListener?.onSuccess(false)
                mOnSuccessListener = null
            }
        }
    }

    override fun destroy() {
        interstitialAd = null
        coroutineScope.cancel()
        mInterstitialAdCounter = 0
        loadingDialogUtil?.destroy()
        loadingDialogUtil = null
    }

    override fun isAdLoaded(): Boolean = interstitialAd != null

    @MainThread
    override fun showAd(
        activity: Activity,
        adUnitId: String,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        if (!shouldShowAd(context) || activity.isFinishing || activity.isDestroyed) {
            onSuccessListener?.onSuccess(false)
            return
        }
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    TimeManager.getInstance().reset()
                    mInterstitialAdCounter = 0
                    adController.shouldShowOpenAd = true
                    interstitialAd = null
                    onSuccessListener?.onSuccess(true)
                    loadAd(adUnitId, null)
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_DISMISSED, "Interstitial_ad")
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    onSuccessListener?.onSuccess(false)
                }

                override fun onAdShowedFullScreenContent() {
                    job?.cancel()
                    adController.shouldShowOpenAd = false
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_SHOWN, "Interstitial_ad")
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_CLICKED, "Interstitial_ad")
                }
            }
            ad.setOnPaidEventListener { adValue ->
                coroutineScope.launch {
                    AdsAnalytics.logAppsFlyerRevenue(ad.adUnitId, "Interstitial", adValue, activity.application)
                }
            }
            ad.show(activity)
        } else {
            onSuccessListener?.onSuccess(false)
        }
    }

    override fun showAndLoadAd(
        activity: Activity,
        adUnitId: String,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        showAd(activity, adUnitId, onSuccessListener)
    }

    override fun showAdWithTimeAndCounter(
        activity: Activity,
        adUnitId: String,
        showForcefully: Boolean,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        if (!shouldShowInterstitialAd(showForcefully)) {
            onSuccessListener?.onSuccess(false)
            return
        }
        Log.d(TAG, "Monetization :- showAdWithTimeAndCounter: mInterstitialAdCounter: $mInterstitialAdCounter")
        if (interstitialAd != null) {
            showAd(activity, adUnitId, onSuccessListener)
        } else {
            onSuccessListener?.onSuccess(false)
            loadAd(adUnitId, null)
        }
    }

    @MainThread
    override fun loadAndShowAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        if (!shouldShowAd(context) || activity.isFinishing || activity.isDestroyed) {
            onSuccessListener?.onSuccess(false)
            return
        }
        kotlin.runCatching {
            loadingDialogUtil?.destroy()
            loadingDialogUtil = LoadingDialogUtil.create(activity)
            if (!activity.isFinishing && showDialog) {
                loadingDialogUtil?.showLoadingDialog()
            }
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(activity, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Monetization :- onAdLoaded")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!activity.isFinishing) loadingDialogUtil?.hideLoadingDialog()
                        if (!activity.isFinishing) {
                            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() {
                                    TimeManager.getInstance().reset()
                                    mInterstitialAdCounter = 0
                                    adController.shouldShowOpenAd = true
                                    interstitialAd = null
                                    onSuccessListener?.onSuccess(true)
                                    Log.d(TAG, "Monetization :- The ad was dismissed.")
                                    AnalyticsManager.getInstance(context).sendAnalytics(AD_DISMISSED, "Interstitial_ad")
                                }

                                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                    interstitialAd = null
                                    onSuccessListener?.onSuccess(false)
                                    Log.d(TAG, "Monetization :- onAdFailedToShowFullScreenContent")
                                }

                                override fun onAdShowedFullScreenContent() {
                                    adController.shouldShowOpenAd = false
                                    interstitialAd = null
                                    Log.d(TAG, "Monetization :- The ad was shown.")
                                    AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "Interstitial_ad")
                                }

                                override fun onAdClicked() {
                                    super.onAdClicked()
                                    AnalyticsManager.getInstance(context).sendAnalytics(AD_CLICKED, "Interstitial_ad")
                                }
                            }
                            ad.setOnPaidEventListener { adValue ->
                                coroutineScope.launch {
                                    AdsAnalytics.logAppsFlyerRevenue(ad.adUnitId, "Interstitial", adValue, activity.application)
                                }
                            }
                            ad.show(activity)
                        }
                    }, adShowDelay)
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.d(TAG, "Monetization :- onAdFailedToLoad: ${loadAdError.message}")
                    onSuccessListener?.onSuccess(false)
                    if (!activity.isFinishing) {
                        Handler(Looper.getMainLooper()).post {
                            loadingDialogUtil?.hideLoadingDialog()
                        }
                    }
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "Interstitial_ad")
                }
            })
        }.getOrElse {
            Log.e(TAG, "Monetization :- loadAndShowInterstitialAd: Exception-> $it")
            onSuccessListener?.onSuccess(false)
        }
    }

    @MainThread
    private fun shouldShowInterstitialAd(showForceFully: Boolean): Boolean {
        if (showForceFully) return true
        mInterstitialAdCounter++
        val elapsedTime = TimeManager.getInstance().getElapsedTimeInSecs()
        val adCounterMet = mInterstitialAdCounter >= adController.interstitialCounter
        val minTimeMet = elapsedTime >= adController.interstitialAdMinTime
        val maxTimeMet = elapsedTime >= adController.interstitialAdMaxTime
        Log.d(TAG, "Monetization :- shouldShowInterstitialAd: counter=$mInterstitialAdCounter, elapsed=$elapsedTime, counterMet=$adCounterMet, minMet=$minTimeMet, maxMet=$maxTimeMet")
        return (adCounterMet && minTimeMet) || maxTimeMet
    }
}
