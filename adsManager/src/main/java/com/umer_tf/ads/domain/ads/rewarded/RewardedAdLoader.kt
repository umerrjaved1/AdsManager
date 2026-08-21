package com.umer_tf.ads.domain.ads.rewarded

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
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
import com.umer_tf.ads.domain.utils.onMainThread
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
    override fun loadAd(activity: Activity, @ValidateAdUnitId adUnitId: String) =
        loadAd(activity, adUnitId, null)

    /**
     * Preloads a rewarded ad and reports the outcome.
     *
     * The interface form reports nothing, which leaves a caller that tracks load state - such as
     * [com.umer_tf.ads.domain.viewmodel.AdViewModel] - stuck showing "loading" forever on a no-fill.
     *
     * @param onLoaded Invoked once on the main thread: true when an ad is cached (including one
     *   already held), false on a refused or failed request.
     */
    @MainThread
    fun loadAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        onLoaded: OnSuccessListener<Boolean>?
    ) {
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            onLoaded?.onSuccess(false)
            return
        }

        if (rewardedAd != null){
            onLoaded?.onSuccess(true)
            return
        }

        // The unit id is part of the request now, and load() takes neither an Activity nor a Context.
        val adRequest = AdRequest.Builder(adUnitId).build()
        RewardedAd.load(adRequest, object : AdLoadCallback<RewardedAd> {
            override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                Log.d(TAG, "Monetization :- onRewardAdFailed: ${adError.message}")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "rewarded_ad")
                onLoaded?.onSuccess(false)
            }

            // Assigning the cached ad on the main thread keeps it consistent with showAd, which is
            // @MainThread; Next-Gen would otherwise write it from a background thread.
            override fun onAdLoaded(ad: RewardedAd) = onMainThread {
                rewardedAd = ad
                Log.d(TAG, "Monetization :- onRewardAdLoaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "rewarded_ad")
                onLoaded?.onSuccess(true)
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

        // Lifecycle plus revenue in one callback object: onAdPaid replaced setOnPaidEventListener.
        // Next-Gen dispatches these on a background thread and every body here either flips
        // adController state or reports back to the host, so each hops to Main.
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() = onMainThread {
                adController.shouldShowOpenAd = false
                Log.d(TAG, "Monetization :- onAdShowedFullScreenContent")
                AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "rewarded_ad")
            }

            override fun onAdFailedToShowFullScreenContent(
                fullScreenContentError: FullScreenContentError
            ) = onMainThread {
                Log.d(TAG, "Monetization :- onAdFailedToShowFullScreenContent")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                onRewardEarned?.onSuccess(false)
            }

            override fun onAdDismissedFullScreenContent() = onMainThread {
                Log.d(TAG, "Monetization :- onAdDismissedFullScreenContent")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                onRewardEarned?.onSuccess(rewardEarned)
                AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "rewarded_ad")
            }

            override fun onAdPaid(value: AdValue) {
                coroutineScope.launch {
                    AdsAnalytics.logAppsFlyerRevenue(
                        ad.adUnitId,
                        "Rewarded",
                        value,
                        activity.application
                    )
                }
            }
        }

        // The reward listener is an interface rather than a lambda-friendly SAM parameter now.
        // The flag is set on the main thread for the same reason it is *read* there: both this and
        // onAdDismissedFullScreenContent arrive on background threads, so writing here and reading
        // from a posted dismissal would give no ordering guarantee - and losing that race hands the
        // user no reward for an ad they watched. Queueing both on Main preserves the SDK's order.
        ad.show(activity, object : OnUserEarnedRewardListener {
            override fun onUserEarnedReward(reward: RewardItem) = onMainThread {
                Log.d(TAG, "Monetization :- The user earned the reward.")
                rewardEarned = true
            }
        })
    }

    @MainThread
    override fun loadAndShowAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean,
        onRewardEarned: OnSuccessListener<Boolean>?
    ) {
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            onRewardEarned?.onSuccess(false)
            return
        }
        if (showDialog) loadingDialogUtil.showLoadingDialog()

        if (rewardedAd != null){
            showAd(activity, onRewardEarned)
            return
        }



        val adRequest = AdRequest.Builder(adUnitId).build()
        RewardedAd.load(adRequest, object : AdLoadCallback<RewardedAd> {
            // Both bodies dismiss a Dialog and, on success, show a full-screen ad - all strictly
            // main-thread work that Next-Gen would otherwise hand us on a background thread.
            override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                loadingDialogUtil.hideLoadingDialog()
                Log.d(TAG, "Monetization :- onRewardAdFailed: ${adError.message}")
                onRewardEarned?.onSuccess(false)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "rewarded_ad")
            }

            override fun onAdLoaded(ad: RewardedAd) = onMainThread {
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
