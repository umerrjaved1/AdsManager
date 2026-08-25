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
import com.umer_tf.ads.domain.core.AdsInitializer
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.SHOWING_AD
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.LoadingDialogUtil
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import com.umer_tf.ads.domain.utils.onMain
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

        if (rewardedAd != null){
            return
        }

        AdsInitializer.runWhenInitialized(context) {
            RewardedAd.load(
                AdRequest.Builder(adUnitId).build(),
                object : AdLoadCallback<RewardedAd> {
                    override fun onAdFailedToLoad(adError: LoadAdError) = onMain {
                        Log.d(TAG, "Monetization :- onRewardAdFailed: ${adError.message}")
                        AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "rewarded_ad")
                    }

                    override fun onAdLoaded(ad: RewardedAd) = onMain {
                        rewardedAd = ad
                        Log.d(TAG, "Monetization :- onRewardAdLoaded")
                        AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "rewarded_ad")
                    }
                }
            )
        }
    }

    @MainThread
    override fun showAd(activity: Activity, onRewardEarned: OnSuccessListener<Boolean>?) {
        val ad = rewardedAd
        if (!shouldShowAd(context) || ad == null) {
            onRewardEarned?.onSuccess(false)
            return
        }
        // Written from the SDK's reward callback (background thread in Next-Gen) and read from
        // the dismissal callback, so it needs a memory barrier rather than a plain local.
        val rewardEarned = java.util.concurrent.atomic.AtomicBoolean(false)

        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() = onMain {
                adController.shouldShowOpenAd = false
                Log.d(TAG, "Monetization :- onAdShowedFullScreenContent")
                AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "rewarded_ad")
            }

            override fun onAdFailedToShowFullScreenContent(
                fullScreenContentError: FullScreenContentError
            ) = onMain {
                Log.d(
                    TAG,
                    "Monetization :- onAdFailedToShowFullScreenContent: ${fullScreenContentError.message}"
                )
                adController.shouldShowOpenAd = true
                rewardedAd = null
                ad.destroy()
                onRewardEarned?.onSuccess(false)
            }

            override fun onAdDismissedFullScreenContent() = onMain {
                Log.d(TAG, "Monetization :- onAdDismissedFullScreenContent")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                ad.destroy()
                onRewardEarned?.onSuccess(rewardEarned.get())
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

        ad.show(activity, object : OnUserEarnedRewardListener {
            override fun onUserEarnedReward(rewardItem: RewardItem) {
                Log.d(TAG, "Monetization :- The user earned the reward.")
                rewardEarned.set(true)
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
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        if (!shouldShowAd(context)) {
            onRewardEarned?.onSuccess(false)
            return
        }
        if (showDialog) loadingDialogUtil.showLoadingDialog()

        if (rewardedAd != null){
            showAd(activity, onRewardEarned)
            return
        }



        AdsInitializer.runWhenInitialized(context) {
            RewardedAd.load(
                AdRequest.Builder(adUnitId).build(),
                object : AdLoadCallback<RewardedAd> {
                    override fun onAdFailedToLoad(adError: LoadAdError) = onMain {
                        loadingDialogUtil.hideLoadingDialog()
                        Log.d(TAG, "Monetization :- onRewardAdFailed: ${adError.message}")
                        onRewardEarned?.onSuccess(false)
                        AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "rewarded_ad")
                    }

                    override fun onAdLoaded(ad: RewardedAd) = onMain {
                        rewardedAd = ad
                        Log.d(TAG, "Monetization :- onRewardAdLoaded")
                        loadingDialogUtil.hideLoadingDialog()
                        AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "rewarded_ad")
                        // showAd is @MainThread; reached only after the marshalling above.
                        showAd(activity, onRewardEarned)
                    }
                }
            )
        }
    }

    fun destroy() {
        coroutineScope.cancel()
        loadingDialogUtil.destroy()
        rewardedAd?.destroy()
        rewardedAd = null
    }

    override fun isAdLoaded(): Boolean = rewardedAd != null
}
