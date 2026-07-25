package com.umer_tf.ads.domain.ads.interstitial

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.LayoutRes
import androidx.annotation.MainThread
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
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
    private val TAG = "AdsManager_Interstitial"
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
        onAdLoaded: ((Boolean) -> Unit)?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "InterstitialAdLoader: loadAd requested for adUnitId=$adUnitId")
        if (interstitialAd != null) {
            Log.e(TAG, "InterstitialAdLoader: loadAd already loaded for adUnitId=$adUnitId")
            onAdLoaded?.invoke(true)
            return
        }
        if (!shouldShowAd(context)) {
            Log.e(TAG, "InterstitialAdLoader: loadAd skipped (shouldShowAd returns false)")
            onAdLoaded?.invoke(false)
            return
        }
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
                Log.e(TAG, "InterstitialAdLoader: onAdLoaded successfully for adUnitId=$adUnitId")
                onAdLoaded?.invoke(true)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "Interstitial_ad")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                Log.e(TAG, "InterstitialAdLoader: onAdFailedToLoad error=${error.message}")
                onAdLoaded?.invoke(false)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "Interstitial_ad")
            }
        })
    }

    @MainThread
    override fun loadAdWithTimeOut(
        @ValidateAdUnitId adUnitId: String,
        timeOut: Long,
        onAdLoaded: ((Boolean) -> Unit)?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "InterstitialAdLoader: loadAdWithTimeOut requested for adUnitId=$adUnitId timeout=$timeOut")
        var mOnAdLoaded: ((Boolean) -> Unit)? = onAdLoaded
        if (!shouldShowAd(context)) {
            Log.e(TAG, "InterstitialAdLoader: loadAdWithTimeOut skipped (shouldShowAd returns false)")
            mOnAdLoaded?.invoke(false)
            return
        }
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
                Log.e(TAG, "InterstitialAdLoader: onAdLoaded successfully for adUnitId=$adUnitId")
                mOnAdLoaded?.invoke(true)
                mOnAdLoaded = null
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "Interstitial_ad")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                Log.e(TAG, "InterstitialAdLoader: onAdFailedToLoad error=${error.message}")
                mOnAdLoaded?.invoke(false)
                mOnAdLoaded = null
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "Interstitial_ad")
            }
        })
        job = coroutineScope.launch {
            delay(timeOut)
            if (interstitialAd == null) {
                Log.e(TAG, "InterstitialAdLoader: loadAdWithTimeOut timed out after ${timeOut}ms")
                mOnAdLoaded?.invoke(false)
                mOnAdLoaded = null
            }
        }
    }

    override fun destroy() {
        Log.e(TAG, "InterstitialAdLoader: destroy called")
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
        onAdDismissed: (() -> Unit)?,
        onAdFailedToShow: ((String) -> Unit)?
    ) {
        Log.e(TAG, "InterstitialAdLoader: showAd requested for adUnitId=$adUnitId")
        if (!shouldShowAd(context) || activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "InterstitialAdLoader: showAd skipped (activity finishing/destroyed or shouldShowAd false)")
            onAdFailedToShow?.invoke("Activity finishing/destroyed or shouldShowAd false")
            return
        }
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    Log.e(TAG, "InterstitialAdLoader: onAdDismissedFullScreenContent")
                    TimeManager.getInstance().reset()
                    mInterstitialAdCounter = 0
                    adController.shouldShowOpenAd = true
                    interstitialAd = null
                    onAdDismissed?.invoke()
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_DISMISSED, "Interstitial_ad")
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.e(TAG, "InterstitialAdLoader: onAdFailedToShowFullScreenContent error=${adError.message}")
                    interstitialAd = null
                    onAdFailedToShow?.invoke(adError.message)
                }

                override fun onAdShowedFullScreenContent() {
                    Log.e(TAG, "InterstitialAdLoader: onAdShowedFullScreenContent")
                    job?.cancel()
                    adController.shouldShowOpenAd = false
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_SHOWN, "Interstitial_ad")
                }

                override fun onAdClicked() {
                    super.onAdClicked()
                    Log.e(TAG, "InterstitialAdLoader: onAdClicked")
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
            Log.e(TAG, "InterstitialAdLoader: showAd failed because interstitialAd is null")
            onAdFailedToShow?.invoke("Ad is null")
        }
    }

    override fun showAndLoadAd(
        activity: Activity,
        adUnitId: String,
        onAdDismissed: (() -> Unit)?,
        onAdFailedToShow: ((String) -> Unit)?
    ) {
        showAd(activity, adUnitId, onAdDismissed, onAdFailedToShow)
    }

    override fun showAdWithTimeAndCounter(
        activity: Activity,
        adUnitId: String,
        showForcefully: Boolean,
        onAdDismissed: (() -> Unit)?,
        onAdFailedToShow: ((String) -> Unit)?
    ) {
        if (!shouldShowInterstitialAd(showForcefully)) {
            Log.e(TAG, "InterstitialAdLoader: showAdWithTimeAndCounter conditions not met")
            onAdFailedToShow?.invoke("Counter or time condition not met")
            return
        }
        Log.e(TAG, "InterstitialAdLoader: showAdWithTimeAndCounter counter=$mInterstitialAdCounter")
        if (interstitialAd != null) {
            showAd(activity, adUnitId, onAdDismissed, onAdFailedToShow)
        } else {
            Log.e(TAG, "InterstitialAdLoader: showAdWithTimeAndCounter ad not loaded, loading now")
            onAdFailedToShow?.invoke("Ad not loaded")
            loadAd(adUnitId, null)
        }
    }

    @MainThread
    override fun loadAndShowAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean,
        onAdLoaded: ((Boolean) -> Unit)?,
        onAdDismissed: (() -> Unit)?
    ) {
        loadAndShowAd(activity, adUnitId, showDialog, adController.loadingDialogLayoutResId, onAdLoaded, onAdDismissed)
    }

    @MainThread
    override fun loadAndShowAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean,
        @LayoutRes customLoadingLayoutResId: Int?,
        onAdLoaded: ((Boolean) -> Unit)?,
        onAdDismissed: (() -> Unit)?
    ) {
        Log.e(TAG, "InterstitialAdLoader: loadAndShowAd requested for adUnitId=$adUnitId")
        if (!shouldShowAd(context) || activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "InterstitialAdLoader: loadAndShowAd skipped (activity finishing/destroyed or shouldShowAd false)")
            onAdLoaded?.invoke(false)
            return
        }
        kotlin.runCatching {
            loadingDialogUtil?.destroy()
            val targetLayoutResId = customLoadingLayoutResId ?: adController.loadingDialogLayoutResId
            loadingDialogUtil = LoadingDialogUtil.create(activity, targetLayoutResId)
            if (!activity.isFinishing && showDialog) {
                loadingDialogUtil?.showLoadingDialog(layoutResId = targetLayoutResId)
            }
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(activity, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.e(TAG, "InterstitialAdLoader: loadAndShowAd onAdLoaded successfully for adUnitId=$adUnitId")
                    onAdLoaded?.invoke(true)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!activity.isFinishing) loadingDialogUtil?.hideLoadingDialog()
                        if (!activity.isFinishing) {
                            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() {
                                    Log.e(TAG, "InterstitialAdLoader: loadAndShowAd onAdDismissedFullScreenContent")
                                    TimeManager.getInstance().reset()
                                    mInterstitialAdCounter = 0
                                    adController.shouldShowOpenAd = true
                                    interstitialAd = null
                                    onAdDismissed?.invoke()
                                    AnalyticsManager.getInstance(context).sendAnalytics(AD_DISMISSED, "Interstitial_ad")
                                }

                                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                    interstitialAd = null
                                    Log.e(TAG, "InterstitialAdLoader: loadAndShowAd onAdFailedToShowFullScreenContent error=${adError.message}")
                                }

                                override fun onAdShowedFullScreenContent() {
                                    adController.shouldShowOpenAd = false
                                    interstitialAd = null
                                    Log.e(TAG, "InterstitialAdLoader: loadAndShowAd onAdShowedFullScreenContent")
                                    AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "Interstitial_ad")
                                }

                                override fun onAdClicked() {
                                    super.onAdClicked()
                                    Log.e(TAG, "InterstitialAdLoader: loadAndShowAd onAdClicked")
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
                    Log.e(TAG, "InterstitialAdLoader: loadAndShowAd onAdFailedToLoad error=${loadAdError.message}")
                    onAdLoaded?.invoke(false)
                    if (!activity.isFinishing) {
                        Handler(Looper.getMainLooper()).post {
                            loadingDialogUtil?.hideLoadingDialog()
                        }
                    }
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "Interstitial_ad")
                }
            })
        }.getOrElse {
            Log.e(TAG, "InterstitialAdLoader: loadAndShowAd Exception-> $it")
            onAdLoaded?.invoke(false)
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
        Log.e(TAG, "InterstitialAdLoader: shouldShowInterstitialAd counter=$mInterstitialAdCounter, elapsed=$elapsedTime, counterMet=$adCounterMet, minMet=$minTimeMet, maxMet=$maxTimeMet")
        return (adCounterMet && minTimeMet) || maxTimeMet
    }
}
