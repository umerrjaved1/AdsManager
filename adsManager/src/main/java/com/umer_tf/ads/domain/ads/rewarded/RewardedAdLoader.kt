package com.umer_tf.ads.domain.ads.rewarded

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
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.SHOWING_AD
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.LoadingDialogUtil
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RewardedAdLoader(
    private val context: Context,
    private val adController: AdController
) : IRewardedAdLoader {
    private val TAG = "AdsManager_Rewarded"
    private var rewardedAd: RewardedAd? = null
    private var loadingDialogUtil: LoadingDialogUtil? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @JvmField
    var adShowDelay: Long = 1000L

    @MainThread
    override fun loadAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        onAdLoaded: ((Boolean) -> Unit)?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "RewardedAdLoader: loadAd requested for adUnitId=$adUnitId")
        if (!shouldShowAd(context)) {
            Log.e(TAG, "RewardedAdLoader: loadAd skipped (shouldShowAd returns false)")
            onAdLoaded?.invoke(false)
            return
        }

        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(activity, adUnitId, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.e(TAG, "RewardedAdLoader: onAdFailedToLoad error=${adError.message}")
                onAdLoaded?.invoke(false)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "rewarded_ad")
            }

            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                Log.e(TAG, "RewardedAdLoader: onAdLoaded successfully for adUnitId=$adUnitId")
                onAdLoaded?.invoke(true)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "rewarded_ad")
            }
        })
    }

    @MainThread
    override fun showAd(
        activity: Activity,
        onRewardEarned: ((Boolean) -> Unit)?,
        onAdDismissed: (() -> Unit)?
    ) {
        val ad = rewardedAd
        Log.e(TAG, "RewardedAdLoader: showAd requested")
        if (!shouldShowAd(context) || ad == null || activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "RewardedAdLoader: showAd skipped (ad is null or shouldShowAd returns false or activity finishing)")
            onRewardEarned?.invoke(false)
            onAdDismissed?.invoke()
            return
        }
        var rewardEarned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                adController.shouldShowOpenAd = false
                Log.e(TAG, "RewardedAdLoader: onAdShowedFullScreenContent")
                AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "rewarded_ad")
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(TAG, "RewardedAdLoader: onAdFailedToShowFullScreenContent error=${adError.message}")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                onRewardEarned?.invoke(false)
                onAdDismissed?.invoke()
            }

            override fun onAdDismissedFullScreenContent() {
                Log.e(TAG, "RewardedAdLoader: onAdDismissedFullScreenContent rewardEarned=$rewardEarned")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                onRewardEarned?.invoke(rewardEarned)
                onAdDismissed?.invoke()
                AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "rewarded_ad")
            }
        }

        ad.setOnPaidEventListener { adValue ->
            coroutineScope.launch {
                AdsAnalytics.logAppsFlyerRevenue(ad.adUnitId, "Rewarded", adValue, activity.application)
            }
        }

        ad.show(activity) { rewardItem ->
            Log.e(TAG, "RewardedAdLoader: User earned reward amount=${rewardItem.amount} type=${rewardItem.type}")
            rewardEarned = true
        }
    }

    @MainThread
    override fun loadAndShowAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean,
        onRewardEarned: ((Boolean) -> Unit)?,
        onAdDismissed: (() -> Unit)?
    ) {
        loadAndShowAd(activity, adUnitId, showDialog, adController.loadingDialogLayoutResId, onRewardEarned, onAdDismissed)
    }

    @MainThread
    override fun loadAndShowAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean,
        @LayoutRes customLoadingLayoutResId: Int?,
        onRewardEarned: ((Boolean) -> Unit)?,
        onAdDismissed: (() -> Unit)?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "RewardedAdLoader: loadAndShowAd requested for adUnitId=$adUnitId")
        if (!shouldShowAd(context) || activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "RewardedAdLoader: loadAndShowAd skipped (shouldShowAd returns false or activity finishing)")
            onRewardEarned?.invoke(false)
            onAdDismissed?.invoke()
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
            RewardedAd.load(activity, adUnitId, adRequest, object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "RewardedAdLoader: loadAndShowAd onAdFailedToLoad error=${adError.message}")
                    if (!activity.isFinishing) {
                        Handler(Looper.getMainLooper()).post {
                            loadingDialogUtil?.hideLoadingDialog()
                        }
                    }
                    onRewardEarned?.invoke(false)
                    onAdDismissed?.invoke()
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "rewarded_ad")
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    Log.e(TAG, "RewardedAdLoader: loadAndShowAd onAdLoaded successfully for adUnitId=$adUnitId")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!activity.isFinishing) {
                            loadingDialogUtil?.hideLoadingDialog()
                        }
                        if (!activity.isFinishing) {
                            showAd(activity, onRewardEarned, onAdDismissed)
                        }
                    }, adShowDelay)
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "rewarded_ad")
                }
            })
        }.getOrElse {
            Log.e(TAG, "RewardedAdLoader: loadAndShowAd Exception-> $it")
            onRewardEarned?.invoke(false)
            onAdDismissed?.invoke()
        }
    }

    fun destroy() {
        Log.e(TAG, "RewardedAdLoader: destroy called")
        coroutineScope.cancel()
        loadingDialogUtil?.destroy()
        loadingDialogUtil = null
        rewardedAd = null
    }

    override fun isAdLoaded(): Boolean = rewardedAd != null
}
