package com.umer_tf.ads.domain.ads.rewarded

import android.app.Activity
import android.content.Context
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
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AdLoadingDialogConfig
import com.umer_tf.ads.domain.utils.AdsLog
import com.umer_tf.ads.domain.utils.LoadingDialogUtil
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RewardedAdLoader(
    private val context: Context,
    private val adController: AdController
) : IRewardedAdLoader {
    private val TAG = "AdsManager_Rewarded"
    private var rewardedAd: RewardedAd? = null
        set(value) {
            field = value
            // Stamped on assignment so every load path gets expiry tracking for free.
            loadTimeMs = if (value == null) 0L else System.currentTimeMillis()
        }
    private var loadTimeMs: Long = 0L
    private var loadingDialogUtil: LoadingDialogUtil? = null

    // Recreated by destroy() - a cancelled scope stays cancelled and would silently drop later work.
    private var coroutineScope = newScope()
    private var showJob: Job? = null

    /**
     * Delay between the rewarded ad finishing loading and it being shown, i.e. how long the loading
     * dialog remains visible. Shares [AdController.interstitialDialogDelayMs] (1.5s by default) so the
     * pacing is consistent across full-screen formats.
     */
    var adShowDelay: Long
        get() = adController.interstitialDialogDelayMs
        set(value) {
            adController.interstitialDialogDelayMs = value
        }

    /**
     * Whether [showAd] puts the loading dialog up during [adShowDelay].
     *
     * On by default so a cached ad is presented exactly like a freshly loaded one. Set to false to keep
     * the delay but drop the dialog, or set [adShowDelay] to 0 as well to show cached ads instantly.
     */
    @JvmField
    var showLoadingDialog: Boolean = true

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @MainThread
    override fun loadAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        onAdLoaded: ((Boolean) -> Unit)?
    ) {
        AdsLog.d(TAG, "RewardedAdLoader: loadAd requested for adUnitId=$adUnitId")
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            onAdLoaded?.invoke(false)
            return
        }
        if (!shouldShowAd(context)) {
            AdsLog.e(TAG, "RewardedAdLoader: loadAd skipped (shouldShowAd returns false)")
            onAdLoaded?.invoke(false)
            return
        }

        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(activity, adUnitId, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                AdsLog.e(TAG, "RewardedAdLoader: onAdFailedToLoad error=${adError.message}")
                onAdLoaded?.invoke(false)
                AdEvents.failedToLoad(context, adUnitId, AdType.REWARDED, adError)
            }

            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                AdsLog.d(TAG, "RewardedAdLoader: onAdLoaded successfully for adUnitId=$adUnitId")
                onAdLoaded?.invoke(true)
                AdEvents.loaded(context, adUnitId, AdType.REWARDED)
            }
        })
    }

    @MainThread
    override fun showAd(
        activity: Activity,
        onRewardEarned: ((Boolean) -> Unit)?,
        onAdDismissed: (() -> Unit)?
    ) {
        val ad = if (isAdLoaded()) rewardedAd else null
        AdsLog.d(TAG, "RewardedAdLoader: showAd requested")
        if (!shouldShowAd(context) || ad == null || activity.isFinishing || activity.isDestroyed) {
            AdsLog.e(TAG, "RewardedAdLoader: showAd skipped (ad is null or shouldShowAd returns false or activity finishing)")
            onRewardEarned?.invoke(false)
            onAdDismissed?.invoke()
            return
        }
        // A cached ad used to appear the instant this was called, so a tap could land straight on the
        // ad. It now gets the same loading-dialog beat as a freshly loaded one.
        presentAfterDialog(
            activity = activity,
            onAborted = {
                onRewardEarned?.invoke(false)
                onAdDismissed?.invoke()
            }
        ) {
            showCachedAd(activity, ad, onRewardEarned, onAdDismissed)
        }
    }

    /**
     * Shows the loading dialog for [adShowDelay], then runs [present] - unless the Activity went away
     * in the meantime, in which case [onAborted] runs instead.
     *
     * Shared by [showAd] and the cached-ad path of [loadAndShowAdWithDialog] so a rewarded ad is always
     * preceded by the same pause, whether it was cached or just fetched.
     */
    @MainThread
    private fun presentAfterDialog(
        activity: Activity,
        showDialog: Boolean = showLoadingDialog,
        dialogConfig: AdLoadingDialogConfig? = null,
        onAborted: () -> Unit,
        present: () -> Unit
    ) {
        showJob?.cancel()
        if (adShowDelay <= 0L && !showDialog) {
            present()
            return
        }
        loadingDialogUtil?.destroy()
        loadingDialogUtil = LoadingDialogUtil.create(
            activity,
            dialogConfig ?: adController.loadingDialogConfig ?: LoadingDialogUtil.globalConfig
        )
        if (showDialog) loadingDialogUtil?.showLoadingDialog()

        showJob = coroutineScope.launch {
            delay(adShowDelay)
            loadingDialogUtil?.hideLoadingDialog()
            if (activity.isFinishing || activity.isDestroyed) {
                AdsLog.e(TAG, "RewardedAdLoader: aborted, activity gone during ${adShowDelay}ms delay")
                onAborted()
                return@launch
            }
            present()
        }
    }

    @MainThread
    private fun showCachedAd(
        activity: Activity,
        ad: RewardedAd,
        onRewardEarned: ((Boolean) -> Unit)?,
        onAdDismissed: (() -> Unit)?
    ) {
        var rewardEarned = false

        // showAd has no adUnitId parameter, so it comes off the ad itself.
        val shownAdUnitId = ad.adUnitId

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                adController.shouldShowOpenAd = false
                AdsLog.d(TAG, "RewardedAdLoader: onAdShowedFullScreenContent")
                AdEvents.showed(context, shownAdUnitId, AdType.REWARDED)
            }

            override fun onAdClicked() {
                super.onAdClicked()
                AdsLog.d(TAG, "RewardedAdLoader: onAdClicked")
                AdEvents.clicked(context, shownAdUnitId, AdType.REWARDED)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                AdsLog.e(TAG, "RewardedAdLoader: onAdFailedToShowFullScreenContent error=${adError.message}")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                AdEvents.failedToLoad(context, shownAdUnitId, AdType.REWARDED, adError)
                onRewardEarned?.invoke(false)
                onAdDismissed?.invoke()
            }

            override fun onAdDismissedFullScreenContent() {
                AdsLog.d(TAG, "RewardedAdLoader: onAdDismissedFullScreenContent rewardEarned=$rewardEarned")
                adController.shouldShowOpenAd = true
                rewardedAd = null
                onRewardEarned?.invoke(rewardEarned)
                onAdDismissed?.invoke()
                // Was reported as "showing_ad" - a dismissal logged as a show, which inflated show
                // counts and gave the app no dismissal signal at all.
                AdEvents.dismissed(context, shownAdUnitId, AdType.REWARDED)
            }
        }

        ad.setOnPaidEventListener { adValue ->
            coroutineScope.launch {
                AdEvents.revenue(activity.application, ad.adUnitId, AdType.REWARDED, adValue)
            }
        }

        ad.show(activity) { rewardItem ->
            AdsLog.d(TAG, "RewardedAdLoader: User earned reward amount=${rewardItem.amount} type=${rewardItem.type}")
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
        val baseConfig = adController.loadingDialogConfig ?: LoadingDialogUtil.globalConfig
        val config = adController.loadingDialogLayoutResId?.let { baseConfig.copy(layoutResId = it) }
            ?: baseConfig
        loadAndShowAdWithDialog(activity, adUnitId, showDialog, config, onRewardEarned, onAdDismissed)
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
        val baseConfig = adController.loadingDialogConfig ?: LoadingDialogUtil.globalConfig
        val config = customLoadingLayoutResId?.let { baseConfig.copy(layoutResId = it) }
            ?: adController.loadingDialogLayoutResId?.let { baseConfig.copy(layoutResId = it) }
            ?: baseConfig
        loadAndShowAdWithDialog(activity, adUnitId, showDialog, config, onRewardEarned, onAdDismissed)
    }

    /**
     * Loads a rewarded ad behind a loading dialog and shows it once loaded.
     *
     * Pass [dialogConfig] to restyle or fully replace the dialog - see [AdLoadingDialogConfig].
     * [onRewardEarned] and [onAdDismissed] are each invoked exactly once, on every exit path.
     */
    @MainThread
    @JvmOverloads
    fun loadAndShowAdWithDialog(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean = true,
        dialogConfig: AdLoadingDialogConfig? = null,
        onRewardEarned: ((Boolean) -> Unit)? = null,
        onAdDismissed: (() -> Unit)? = null
    ) {
        AdsLog.d(TAG, "RewardedAdLoader: loadAndShowAd requested for adUnitId=$adUnitId")

        var finished = false
        val finishOnce = { rewarded: Boolean ->
            if (!finished) {
                finished = true
                onRewardEarned?.invoke(rewarded)
                onAdDismissed?.invoke()
            }
        }

        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) ||
            !shouldShowAd(context) || activity.isFinishing || activity.isDestroyed
        ) {
            AdsLog.e(TAG, "RewardedAdLoader: loadAndShowAd skipped (invalid id, shouldShowAd false, or activity finishing)")
            finishOnce(false)
            return
        }

        // An already-cached ad is shown behind the same dialog instead of being thrown away and
        // re-requested, which is what this used to do - wasting a paid fill on every call.
        if (isAdLoaded()) {
            val cached = rewardedAd
            if (cached != null) {
                AdsLog.d(TAG, "RewardedAdLoader: loadAndShowAd using the cached ad")
                presentAfterDialog(
                    activity = activity,
                    showDialog = showDialog,
                    dialogConfig = dialogConfig,
                    onAborted = { finishOnce(false) }
                ) {
                    showCachedAd(
                        activity = activity,
                        ad = cached,
                        onRewardEarned = { rewarded -> finishOnce(rewarded) },
                        onAdDismissed = null
                    )
                }
                return
            }
        }

        kotlin.runCatching {
            showJob?.cancel()
            loadingDialogUtil?.destroy()
            loadingDialogUtil = LoadingDialogUtil.create(
                activity,
                dialogConfig ?: adController.loadingDialogConfig ?: LoadingDialogUtil.globalConfig
            )
            if (showDialog) {
                loadingDialogUtil?.showLoadingDialog()
            }

            val adRequest = AdRequest.Builder().build()
            RewardedAd.load(activity, adUnitId, adRequest, object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    AdsLog.e(TAG, "RewardedAdLoader: loadAndShowAd onAdFailedToLoad error=${adError.message}")
                    loadingDialogUtil?.hideLoadingDialog()
                    finishOnce(false)
                    AdEvents.failedToLoad(context, adUnitId, AdType.REWARDED, adError)
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    AdsLog.d(TAG, "RewardedAdLoader: loadAndShowAd onAdLoaded successfully for adUnitId=$adUnitId")
                    AdEvents.loaded(context, adUnitId, AdType.REWARDED)

                    // Coroutine rather than a bare Handler so destroy() can cancel it and a dead
                    // Activity is not retained for the length of the delay.
                    showJob = coroutineScope.launch {
                        delay(adShowDelay)
                        loadingDialogUtil?.hideLoadingDialog()
                        if (activity.isFinishing || activity.isDestroyed) {
                            // Previously neither callback fired here, stranding the caller.
                            AdsLog.d(TAG, "RewardedAdLoader: aborted, activity gone during ${adShowDelay}ms delay")
                            finishOnce(false)
                            return@launch
                        }
                        // showCachedAd, not showAd: showAd now runs its own dialog + delay, so calling
                        // it here would make the user wait through the pause twice.
                        showCachedAd(
                            activity = activity,
                            ad = ad,
                            onRewardEarned = { rewarded -> finishOnce(rewarded) },
                            onAdDismissed = null
                        )
                    }
                }
            })
        }.getOrElse {
            AdsLog.e(TAG, "RewardedAdLoader: loadAndShowAd Exception-> $it")
            loadingDialogUtil?.hideLoadingDialog()
            finishOnce(false)
        }
    }

    fun destroy() {
        AdsLog.d(TAG, "RewardedAdLoader: destroy called")
        showJob = null
        coroutineScope.cancel()
        coroutineScope = newScope()
        loadingDialogUtil?.destroy()
        loadingDialogUtil = null
        rewardedAd = null
    }

    /**
     * True when a cached ad is present and still fresh; an expired ad is discarded rather than
     * reported as available, so it gets replaced instead of failing at show time.
     */
    override fun isAdLoaded(): Boolean {
        if (rewardedAd == null) return false
        if (loadTimeMs != 0L && System.currentTimeMillis() - loadTimeMs > adController.rewardedAdTtlMs) {
            AdsLog.d(TAG, "RewardedAdLoader: cached ad expired after ${adController.rewardedAdTtlMs}ms, discarding")
            rewardedAd = null
            return false
        }
        return true
    }
}
