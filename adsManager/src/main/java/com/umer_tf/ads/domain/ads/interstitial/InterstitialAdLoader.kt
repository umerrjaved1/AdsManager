package com.umer_tf.ads.domain.ads.interstitial

import android.app.Activity
import android.content.Context
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
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AdLoadingDialogConfig
import com.umer_tf.ads.domain.utils.AdsLog
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
        set(value) {
            field = value
            // Stamped on assignment so every load path gets expiry tracking for free.
            loadTimeMs = if (value == null) 0L else System.currentTimeMillis()
        }
    private var loadTimeMs: Long = 0L
    private var mInterstitialAdCounter: Int = 0
    private var loadingDialogUtil: LoadingDialogUtil? = null

    // Recreated by destroy() instead of being cancelled for good: cancelling a CoroutineScope kills
    // it permanently, which used to make every later timeout/paid-event launch a silent no-op.
    private var coroutineScope = newScope()
    private var job: Job? = null
    private var showJob: Job? = null

    /**
     * Delay between the interstitial finishing loading and it being shown, i.e. how long the loading
     * dialog remains visible. Defaults to [AdController.interstitialDialogDelayMs] (1.5s).
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
        @ValidateAdUnitId adUnitId: String,
        onAdLoaded: ((Boolean) -> Unit)?
    ) {
        AdsLog.d(TAG, "InterstitialAdLoader: loadAd requested for adUnitId=$adUnitId")
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            onAdLoaded?.invoke(false)
            return
        }
        if (isAdLoaded()) {
            AdsLog.d(TAG, "InterstitialAdLoader: loadAd already loaded for adUnitId=$adUnitId")
            onAdLoaded?.invoke(true)
            return
        }
        if (!shouldShowAd(context)) {
            AdsLog.e(TAG, "InterstitialAdLoader: loadAd skipped (shouldShowAd returns false)")
            onAdLoaded?.invoke(false)
            return
        }
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
                AdsLog.d(TAG, "InterstitialAdLoader: onAdLoaded successfully for adUnitId=$adUnitId")
                onAdLoaded?.invoke(true)
                AdEvents.loaded(context, adUnitId, AdType.INTERSTITIAL)
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                AdsLog.e(TAG, "InterstitialAdLoader: onAdFailedToLoad error=${error.message}")
                onAdLoaded?.invoke(false)
                AdEvents.failedToLoad(context, adUnitId, AdType.INTERSTITIAL, error)
            }
        })
    }

    @MainThread
    override fun loadAdWithTimeOut(
        @ValidateAdUnitId adUnitId: String,
        timeOut: Long,
        onAdLoaded: ((Boolean) -> Unit)?
    ) {
        AdsLog.d(TAG, "InterstitialAdLoader: loadAdWithTimeOut requested for adUnitId=$adUnitId timeout=$timeOut")
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            onAdLoaded?.invoke(false)
            return
        }
        var mOnAdLoaded: ((Boolean) -> Unit)? = onAdLoaded
        if (!shouldShowAd(context)) {
            AdsLog.e(TAG, "InterstitialAdLoader: loadAdWithTimeOut skipped (shouldShowAd returns false)")
            mOnAdLoaded?.invoke(false)
            return
        }
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
                AdsLog.d(TAG, "InterstitialAdLoader: onAdLoaded successfully for adUnitId=$adUnitId")
                mOnAdLoaded?.invoke(true)
                mOnAdLoaded = null
                AdEvents.loaded(context, adUnitId, AdType.INTERSTITIAL)
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                AdsLog.e(TAG, "InterstitialAdLoader: onAdFailedToLoad error=${error.message}")
                mOnAdLoaded?.invoke(false)
                mOnAdLoaded = null
                AdEvents.failedToLoad(context, adUnitId, AdType.INTERSTITIAL, error)
            }
        })
        job = coroutineScope.launch {
            delay(timeOut)
            if (interstitialAd == null) {
                AdsLog.d(TAG, "InterstitialAdLoader: loadAdWithTimeOut timed out after ${timeOut}ms")
                mOnAdLoaded?.invoke(false)
                mOnAdLoaded = null
            }
        }
    }

    override fun destroy() {
        AdsLog.d(TAG, "InterstitialAdLoader: destroy called")
        interstitialAd = null
        job = null
        showJob = null
        coroutineScope.cancel()
        coroutineScope = newScope()
        mInterstitialAdCounter = 0
        loadingDialogUtil?.destroy()
        loadingDialogUtil = null
    }

    /**
     * True when a cached ad is present and still fresh.
     *
     * An expired ad is discarded here rather than reported as available, so the next `loadAd` fetches a
     * replacement instead of the user's tap landing on a show failure.
     */
    override fun isAdLoaded(): Boolean {
        if (interstitialAd == null) return false
        if (isAdExpired()) {
            AdsLog.d(TAG, "InterstitialAdLoader: cached ad expired after ${adController.interstitialAdTtlMs}ms, discarding")
            interstitialAd = null
            return false
        }
        return true
    }

    private fun isAdExpired(): Boolean {
        if (loadTimeMs == 0L) return false
        return System.currentTimeMillis() - loadTimeMs > adController.interstitialAdTtlMs
    }

    @MainThread
    override fun showAd(
        activity: Activity,
        adUnitId: String,
        onAdDismissed: (() -> Unit)?,
        onAdFailedToShow: ((String) -> Unit)?
    ) {
        AdsLog.d(TAG, "InterstitialAdLoader: showAd requested for adUnitId=$adUnitId")
        if (!shouldShowAd(context) || activity.isFinishing || activity.isDestroyed) {
            AdsLog.e(TAG, "InterstitialAdLoader: showAd skipped (activity finishing/destroyed or shouldShowAd false)")
            onAdFailedToShow?.invoke("Activity finishing/destroyed or shouldShowAd false")
            return
        }
        val ad = if (isAdLoaded()) interstitialAd else null
        if (ad != null) {
            // A cached ad used to appear the instant this was called, so a tap could land straight on
            // the ad. It now gets the same loading-dialog beat as a freshly loaded one.
            presentAfterDialog(
                activity = activity,
                onAborted = { reason -> onAdFailedToShow?.invoke(reason) }
            ) {
                showCachedAd(activity, adUnitId, ad, onAdDismissed, onAdFailedToShow)
            }
        } else {
            AdsLog.e(TAG, "InterstitialAdLoader: showAd failed because interstitialAd is null")
            onAdFailedToShow?.invoke("Ad is null")
        }
    }

    /**
     * Shows the loading dialog for [adShowDelay], then runs [present] - unless the Activity went away
     * in the meantime, in which case [onAborted] runs instead.
     *
     * Shared by [showAd] and the cached-ad path of [loadAndShowAdWithDialog] so a full-screen ad is
     * always preceded by the same pause, whether it was cached or just fetched.
     */
    @MainThread
    private fun presentAfterDialog(
        activity: Activity,
        showDialog: Boolean = showLoadingDialog,
        dialogConfig: AdLoadingDialogConfig? = null,
        onAborted: (String) -> Unit,
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
                AdsLog.e(TAG, "InterstitialAdLoader: aborted, activity gone during ${adShowDelay}ms delay")
                onAborted("Activity finished during the pre-show delay")
                return@launch
            }
            present()
        }
    }

    @MainThread
    private fun showCachedAd(
        activity: Activity,
        adUnitId: String,
        ad: InterstitialAd,
        onAdDismissed: (() -> Unit)?,
        onAdFailedToShow: ((String) -> Unit)?
    ) {
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                AdsLog.d(TAG, "InterstitialAdLoader: onAdDismissedFullScreenContent")
                TimeManager.getInstance().reset()
                mInterstitialAdCounter = 0
                adController.shouldShowOpenAd = true
                interstitialAd = null
                onAdDismissed?.invoke()
                AdEvents.dismissed(context, adUnitId, AdType.INTERSTITIAL)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                AdsLog.e(TAG, "InterstitialAdLoader: onAdFailedToShowFullScreenContent error=${adError.message}")
                interstitialAd = null
                AdEvents.failedToLoad(context, adUnitId, AdType.INTERSTITIAL, adError)
                onAdFailedToShow?.invoke(adError.message)
            }

            override fun onAdShowedFullScreenContent() {
                AdsLog.d(TAG, "InterstitialAdLoader: onAdShowedFullScreenContent")
                job?.cancel()
                adController.shouldShowOpenAd = false
                // showed(), matching loadAndShowAd. This path used to report ad_shown while the
                // other reported showing_ad for the identical event.
                AdEvents.showed(context, adUnitId, AdType.INTERSTITIAL)
            }

            override fun onAdClicked() {
                super.onAdClicked()
                AdsLog.d(TAG, "InterstitialAdLoader: onAdClicked")
                AdEvents.clicked(context, adUnitId, AdType.INTERSTITIAL)
            }
        }
        ad.setOnPaidEventListener { adValue ->
            coroutineScope.launch {
                AdEvents.revenue(activity.application, ad.adUnitId, AdType.INTERSTITIAL, adValue)
            }
        }
        ad.show(activity)
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
            AdsLog.e(TAG, "InterstitialAdLoader: showAdWithTimeAndCounter conditions not met")
            onAdFailedToShow?.invoke("Counter or time condition not met")
            return
        }
        AdsLog.d(TAG, "InterstitialAdLoader: showAdWithTimeAndCounter counter=$mInterstitialAdCounter")
        if (isAdLoaded()) {
            showAd(activity, adUnitId, onAdDismissed, onAdFailedToShow)
        } else {
            AdsLog.d(TAG, "InterstitialAdLoader: showAdWithTimeAndCounter ad not loaded, loading now")
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
        val baseConfig = adController.loadingDialogConfig ?: LoadingDialogUtil.globalConfig
        val config = customLoadingLayoutResId?.let { baseConfig.copy(layoutResId = it) }
            ?: adController.loadingDialogLayoutResId?.let { baseConfig.copy(layoutResId = it) }
            ?: baseConfig
        loadAndShowAdWithDialog(activity, adUnitId, showDialog, config, onAdLoaded, onAdDismissed)
    }

    /**
     * Loads an interstitial behind a loading dialog and shows it once loaded.
     *
     * The dialog stays up for [adShowDelay] (1.5s by default) after the ad resolves so the ad never
     * lands under a finger that is still mid-tap. Pass [dialogConfig] to restyle or fully replace the
     * dialog - see [AdLoadingDialogConfig].
     *
     * [onAdDismissed] is invoked exactly once for every outcome that returns control to the app,
     * including a failure to show, so navigation gated on this callback can never stall.
     */
    @MainThread
    @JvmOverloads
    fun loadAndShowAdWithDialog(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean = true,
        dialogConfig: AdLoadingDialogConfig? = null,
        onAdLoaded: ((Boolean) -> Unit)? = null,
        onAdDismissed: (() -> Unit)? = null
    ) {
        AdsLog.d(TAG, "InterstitialAdLoader: loadAndShowAd requested for adUnitId=$adUnitId")

        // Guarantees onAdDismissed runs once and only once, whatever path we exit through.
        var dismissed = false
        val dismissOnce = {
            if (!dismissed) {
                dismissed = true
                onAdDismissed?.invoke()
            }
        }

        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) ||
            !shouldShowAd(context) || activity.isFinishing || activity.isDestroyed
        ) {
            AdsLog.e(TAG, "InterstitialAdLoader: loadAndShowAd skipped (invalid id, activity finishing/destroyed, or shouldShowAd false)")
            onAdLoaded?.invoke(false)
            dismissOnce()
            return
        }

        // An already-cached ad is shown behind the same dialog instead of being thrown away and
        // re-requested, which is what this used to do - wasting a paid fill on every call.
        if (isAdLoaded()) {
            val cached = interstitialAd
            if (cached != null) {
                AdsLog.d(TAG, "InterstitialAdLoader: loadAndShowAd using the cached ad")
                onAdLoaded?.invoke(true)
                presentAfterDialog(
                    activity = activity,
                    showDialog = showDialog,
                    dialogConfig = dialogConfig,
                    onAborted = { dismissOnce() }
                ) {
                    showCachedAd(
                        activity = activity,
                        adUnitId = adUnitId,
                        ad = cached,
                        onAdDismissed = { dismissOnce() },
                        onAdFailedToShow = { dismissOnce() }
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
            InterstitialAd.load(activity, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    AdsLog.d(TAG, "InterstitialAdLoader: loadAndShowAd onAdLoaded successfully for adUnitId=$adUnitId")
                    onAdLoaded?.invoke(true)
                    AdEvents.loaded(context, adUnitId, AdType.INTERSTITIAL)

                    // Coroutine rather than a bare Handler so destroy() / a second call can cancel it;
                    // the old Handler kept a dead Activity alive and showed the ad on it.
                    showJob = coroutineScope.launch {
                        delay(adShowDelay)
                        loadingDialogUtil?.hideLoadingDialog()
                        if (activity.isFinishing || activity.isDestroyed) {
                            AdsLog.d(TAG, "InterstitialAdLoader: loadAndShowAd aborted, activity gone during ${adShowDelay}ms delay")
                            interstitialAd = ad // keep it for the next screen instead of wasting the fill
                            dismissOnce()
                            return@launch
                        }
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                AdsLog.d(TAG, "InterstitialAdLoader: loadAndShowAd onAdDismissedFullScreenContent")
                                TimeManager.getInstance().reset()
                                mInterstitialAdCounter = 0
                                adController.shouldShowOpenAd = true
                                interstitialAd = null
                                dismissOnce()
                                AdEvents.dismissed(context, adUnitId, AdType.INTERSTITIAL)
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                interstitialAd = null
                                adController.shouldShowOpenAd = true
                                AdsLog.e(TAG, "InterstitialAdLoader: loadAndShowAd onAdFailedToShowFullScreenContent error=${adError.message}")
                                // Without this the caller waits on a dismissal that never arrives.
                                dismissOnce()
                                AdEvents.failedToLoad(context, adUnitId, AdType.INTERSTITIAL, adError)
                            }

                            override fun onAdShowedFullScreenContent() {
                                adController.shouldShowOpenAd = false
                                interstitialAd = null
                                AdsLog.d(TAG, "InterstitialAdLoader: loadAndShowAd onAdShowedFullScreenContent")
                                AdEvents.showed(context, adUnitId, AdType.INTERSTITIAL)
                            }

                            override fun onAdClicked() {
                                super.onAdClicked()
                                AdsLog.d(TAG, "InterstitialAdLoader: loadAndShowAd onAdClicked")
                                AdEvents.clicked(context, adUnitId, AdType.INTERSTITIAL)
                            }
                        }
                        ad.setOnPaidEventListener { adValue ->
                            coroutineScope.launch {
                                AdEvents.revenue(activity.application, ad.adUnitId, AdType.INTERSTITIAL, adValue)
                            }
                        }
                        ad.show(activity)
                    }
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    AdsLog.e(TAG, "InterstitialAdLoader: loadAndShowAd onAdFailedToLoad error=${loadAdError.message}")
                    loadingDialogUtil?.hideLoadingDialog()
                    onAdLoaded?.invoke(false)
                    dismissOnce()
                    AdEvents.failedToLoad(context, adUnitId, AdType.INTERSTITIAL, loadAdError)
                }
            })
        }.getOrElse {
            AdsLog.e(TAG, "InterstitialAdLoader: loadAndShowAd Exception-> $it")
            loadingDialogUtil?.hideLoadingDialog()
            onAdLoaded?.invoke(false)
            dismissOnce()
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
        AdsLog.d(TAG, "InterstitialAdLoader: shouldShowInterstitialAd counter=$mInterstitialAdCounter, elapsed=$elapsedTime, counterMet=$adCounterMet, minMet=$minTimeMet, maxMet=$maxTimeMet")
        return (adCounterMet && minTimeMet) || maxTimeMet
    }
}
