package com.umer_tf.ads.domain.ads.rewarded

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
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
    private val TAG = "RewardedAdLoader"
    private var rewardedAd: RewardedAd? = null
    private val loadingDialogUtil = LoadingDialogUtil.create(context)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @MainThread
    override fun loadAd(activity: Activity, @ValidateAdUnitId adUnitId: String) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        if (!shouldShowAd(context)) return

        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(activity, adUnitId, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.d(TAG, "Monetization :- onRewardAdFailed: ${adError.message}")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "rewarded_ad")
            }

            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                Log.d(TAG, "Monetization :- onRewardAdLoaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "rewarded_ad")
            }
        })
    }

    @MainThread
    override fun showAd(activity: Activity, onRewardEarned: OnSuccessListener<Boolean>?) {
        val ad = rewardedAd
        if (!shouldShowAd(context) || ad == null) {
            onRewardEarned?.onSuccess(false)
            return
        }
        var rewardEarned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                adController.shouldShowOpenAd = false
                Log.d(TAG, "Monetization :- onAdShowedFullScreenContent")
                AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "rewarded_ad")
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.d(TAG, "Monetization :- onAdFailedToShowFullScreenContent")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                onRewardEarned?.onSuccess(false)
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Monetization :- onAdDismissedFullScreenContent")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                onRewardEarned?.onSuccess(rewardEarned)
                AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "rewarded_ad")
            }
        }

        ad.setOnPaidEventListener { adValue ->
            coroutineScope.launch {
                AdsAnalytics.logAppsFlyerRevenue(ad.adUnitId, "Rewarded", adValue, activity.application)
            }
        }

        ad.show(activity) {
            Log.d(TAG, "Monetization :- The user earned the reward.")
            rewardEarned = true
        }
    }

    @MainThread
    override fun loadAndShowAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean,
        onRewardEarned: OnSuccessListener<Boolean>?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        if (!shouldShowAd(context)) {
            onRewardEarned?.onSuccess(false)
            return
        }
        if (showDialog) loadingDialogUtil.showLoadingDialog()
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(activity, adUnitId, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                loadingDialogUtil.hideLoadingDialog()
                Log.d(TAG, "Monetization :- onRewardAdFailed: ${adError.message}")
                onRewardEarned?.onSuccess(false)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "rewarded_ad")
            }

            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                Log.d(TAG, "Monetization :- onRewardAdLoaded")
                loadingDialogUtil.hideLoadingDialog()
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "rewarded_ad")
                showAd(activity, onRewardEarned)
            }
        })
    }

    fun destroy() {
        coroutineScope.cancel()
        loadingDialogUtil.destroy()
        rewardedAd = null
    }

    override fun isAdLoaded(): Boolean = rewardedAd != null
}
