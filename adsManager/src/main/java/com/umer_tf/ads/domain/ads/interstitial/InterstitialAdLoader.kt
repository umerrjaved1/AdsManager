package com.umer_tf.ads.domain.ads.interstitial

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.umer_tf.ads.domain.core.AdSlotState
import com.umer_tf.ads.domain.core.FullScreenGate
import com.umer_tf.ads.domain.diagnostics.AdEvent
import com.umer_tf.ads.domain.diagnostics.AdEventLog
import com.umer_tf.ads.domain.diagnostics.AdFormat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Interstitial loading and display, one independent slot per ad unit.
 *
 * Two properties of this class exist specifically to stop matched ads from going unshown:
 *
 * 1. **A caller giving up is not the ad giving up.** Timeouts and cancelled screens release the
 *    *callback*; the request keeps running and its fill lands in the slot, where the next caller
 *    for that unit picks it up for free. The previous single-field design dropped fills that
 *    arrived a moment after a splash timeout - those requests were matched, paid for by the
 *    advertiser, and never displayed.
 * 2. **A second request for a unit already loading joins the first.** Two AdMob requests can only
 *    ever produce one impression, so the second is pure loss.
 */
class InterstitialAdLoader(
    private val context: Context,
    private val adController: AdController
) : IInterstitialAdLoader {

    private val TAG = "InterstitialAdLoader"

    /**
     * Google documents an interstitial as usable for about an hour. Showing a staler one risks a
     * `SHOW_FAILED`, which costs the impression outright.
     */
    private val adExpiryMs = 55 * 60 * 1000L

    /** One slot per ad unit. Mutated on the main thread only, so it needs no locking. */
    private class Slot {
        var ad: InterstitialAd? = null
        var state: AdSlotState = AdSlotState.IDLE
        var loadedAtElapsed: Long = 0L
        val waiters = mutableListOf<OnSuccessListener<Boolean>>()
    }

    private val slots = mutableMapOf<String, Slot>()

    private var mInterstitialAdCounter: Int = 0
    private var loadingDialogUtil: LoadingDialogUtil? = null

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    @JvmField
    var adShowDelay: Long = 1000L

    // ---------------------------------------------------------------------------------------
    // Slot helpers
    // ---------------------------------------------------------------------------------------

    private fun slotFor(adUnitId: String): Slot = slots.getOrPut(adUnitId) { Slot() }

    private fun Slot.isExpired(): Boolean =
        loadedAtElapsed > 0L && SystemClock.elapsedRealtime() - loadedAtElapsed > adExpiryMs

    /** The displayable ad for [adUnitId], dropping it first if it has aged out. */
    private fun readyAd(adUnitId: String): InterstitialAd? {
        val slot = slots[adUnitId] ?: return null
        if (slot.state != AdSlotState.READY) return null
        if (slot.isExpired()) {
            Log.d(TAG, "Monetization :- cached interstitial for $adUnitId expired")
            emit(adUnitId, AdEvent.AD_EXPIRED, slot.state)
            slot.ad = null
            slot.state = AdSlotState.IDLE
            slot.loadedAtElapsed = 0L
            return null
        }
        return slot.ad
    }

    private fun emit(adUnitId: String, event: AdEvent, state: AdSlotState, reason: String? = null) {
        AdEventLog.emit(AdFormat.INTERSTITIAL, adUnitId, event, state, reason)
    }

    private fun settleWaiters(slot: Slot, success: Boolean) {
        if (slot.waiters.isEmpty()) return
        val pending = slot.waiters.toList()
        slot.waiters.clear()
        pending.forEach { it.onSuccess(success) }
    }

    /**
     * Starts (or joins) a request for [adUnitId]. [onResult] fires exactly once.
     *
     * Nothing here can abandon a fill: even when every waiter has been released the load callback
     * still writes the ad into the slot.
     */
    @MainThread
    private fun requestAd(adUnitId: String, onResult: OnSuccessListener<Boolean>?) {
        val slot = slotFor(adUnitId)

        // `onAdDismissedFullScreenContent` is not guaranteed - a process death behind the ad, or
        // an SDK that simply never calls back, would leave this slot stuck in SHOWING and the unit
        // could never load again for the rest of the session. The gate knows whether anything is
        // really on screen, so trust it over the slot.
        if (slot.state == AdSlotState.SHOWING && !FullScreenGate.isShowing()) {
            Log.w(TAG, "Monetization :- $adUnitId stuck in SHOWING with no ad on screen - resetting")
            slot.state = AdSlotState.IDLE
        }

        if (readyAd(adUnitId) != null) {
            emit(adUnitId, AdEvent.REQUEST_SKIPPED_CACHED, slot.state)
            onResult?.onSuccess(true)
            return
        }

        if (slot.state.isLoading) {
            emit(adUnitId, AdEvent.REQUEST_JOINED, slot.state)
            onResult?.let { slot.waiters.add(it) }
            return
        }

        if (!shouldShowAd(context)) {
            emit(adUnitId, AdEvent.LOAD_FAILURE, slot.state, "premium or offline")
            onResult?.onSuccess(false)
            return
        }

        slot.state = AdSlotState.LOADING
        onResult?.let { slot.waiters.add(it) }
        emit(adUnitId, AdEvent.REQUEST_STARTED, slot.state)

        InterstitialAd.load(
            context,
            adUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    slot.ad = ad
                    slot.state = AdSlotState.READY
                    slot.loadedAtElapsed = SystemClock.elapsedRealtime()
                    Log.d(TAG, "Monetization :- onAdLoaded ($adUnitId)")
                    emit(adUnitId, AdEvent.LOAD_SUCCESS, slot.state)
                    emit(adUnitId, AdEvent.READY, slot.state)
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "Interstitial_ad")
                    settleWaiters(slot, true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    slot.ad = null
                    slot.state = AdSlotState.FAILED
                    slot.loadedAtElapsed = 0L
                    Log.d(TAG, "Monetization :- onAdFailedToLoad ($adUnitId): ${error.message}")
                    emit(adUnitId, AdEvent.LOAD_FAILURE, slot.state, error.message)
                    AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "Interstitial_ad")
                    settleWaiters(slot, false)
                }
            }
        )
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    @MainThread
    override fun loadAd(
        @ValidateAdUnitId adUnitId: String,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        requestAd(adUnitId, onSuccessListener)
    }

    /**
     * As [loadAd], but reports `false` to the caller after [timeOut] when nothing has filled yet.
     *
     * The timeout bounds the *caller's* wait only. The request continues and a late fill is kept,
     * so the next placement on this unit gets it for free instead of paying for a second one.
     */
    @MainThread
    override fun loadAdWithTimeOut(
        @ValidateAdUnitId adUnitId: String,
        timeOut: Long,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        var settled = false
        val once = OnSuccessListener<Boolean> { success ->
            if (settled) return@OnSuccessListener
            settled = true
            onSuccessListener?.onSuccess(success)
        }
        requestAd(adUnitId, once)
        job = coroutineScope.launch {
            delay(timeOut)
            if (settled) return@launch
            // The caller's wait ended; the request is still running and a late fill is kept.
            // Emitting LOAD_FAILURE here made a timeout look like a no-fill in diagnostics.
            Log.d(TAG, "Monetization :- caller timeout after ${timeOut}ms for $adUnitId — request continues")
            once.onSuccess(false)
        }
    }

    // ---------------------------------------------------------------------------------------
    // State queries
    // ---------------------------------------------------------------------------------------

    override fun isAdLoaded(): Boolean = slots.keys.any { readyAd(it) != null }

    /** True when the cached interstitial for exactly [adUnitId] is loaded and unexpired. */
    fun isAdLoaded(adUnitId: String): Boolean = readyAd(adUnitId) != null

    /** True while a request for [adUnitId] is in flight. A second caller should join, not reload. */
    fun isLoading(adUnitId: String): Boolean = slots[adUnitId]?.state?.isLoading == true

    /** Current lifecycle state of [adUnitId]'s slot. */
    fun stateOf(adUnitId: String): AdSlotState = slots[adUnitId]?.state ?: AdSlotState.IDLE

    override fun destroy() {
        slots.values.forEach { it.waiters.clear() }
        slots.clear()
        job?.cancel()
        mInterstitialAdCounter = 0
        loadingDialogUtil?.destroy()
        loadingDialogUtil = null
    }

    // ---------------------------------------------------------------------------------------
    // Showing
    // ---------------------------------------------------------------------------------------

    /**
     * Wires the callbacks and displays [ad]. Returns false when the show was refused, in which
     * case [onSuccessListener] has already been told and the slot is untouched.
     */
    @MainThread
    private fun show(
        activity: Activity,
        adUnitId: String,
        ad: InterstitialAd,
        onDialogDismiss: (() -> Unit)? = null,
        onSuccessListener: OnSuccessListener<Boolean>?
    ): Boolean {
        val slot = slotFor(adUnitId)
        emit(adUnitId, AdEvent.SHOW_REQUESTED, slot.state)

        if (activity.isFinishing || activity.isDestroyed) {
            emit(adUnitId, AdEvent.SHOW_REJECTED_INVALID_ACTIVITY, slot.state, activity.javaClass.simpleName)
            onSuccessListener?.onSuccess(false)
            return false
        }
        if (!FullScreenGate.acquire(GATE_OWNER)) {
            emit(adUnitId, AdEvent.SHOW_REJECTED_ALREADY_SHOWING, slot.state, FullScreenGate.currentOwner())
            onSuccessListener?.onSuccess(false)
            return false
        }

        var released = false
        fun releaseGate() {
            if (released) return
            released = true
            FullScreenGate.release(GATE_OWNER)
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                job?.cancel()
                adController.shouldShowOpenAd = false
                // Consumed: an interstitial displays exactly once.
                slot.ad = null
                slot.state = AdSlotState.SHOWING
                slot.loadedAtElapsed = 0L
                emit(adUnitId, AdEvent.SHOW_STARTED, slot.state)
                Log.d(TAG, "Monetization :- shown on ${activity.javaClass.simpleName}")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_SHOWN, "Interstitial_ad")
                AnalyticsManager.getInstance(context).sendAnalytics(SHOWING_AD, "Interstitial_ad")
            }

            override fun onAdDismissedFullScreenContent() {
                releaseGate()
                TimeManager.getInstance().reset()
                mInterstitialAdCounter = 0
                adController.shouldShowOpenAd = true
                slot.state = AdSlotState.IDLE
                emit(adUnitId, AdEvent.DISMISSED, slot.state)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_DISMISSED, "Interstitial_ad")
                onDialogDismiss?.invoke()
                onSuccessListener?.onSuccess(true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                releaseGate()
                slot.ad = null
                slot.state = AdSlotState.IDLE
                slot.loadedAtElapsed = 0L
                emit(adUnitId, AdEvent.SHOW_FAILED, slot.state, adError.message)
                Log.d(TAG, "Monetization :- onAdFailedToShowFullScreenContent: ${adError.message}")
                onDialogDismiss?.invoke()
                onSuccessListener?.onSuccess(false)
            }

            override fun onAdClicked() {
                super.onAdClicked()
                AnalyticsManager.getInstance(context).sendAnalytics(AD_CLICKED, "Interstitial_ad")
            }
        }

        ad.setOnPaidEventListener { adValue ->
            coroutineScope.launch {
                AdsAnalytics.logAppsFlyerRevenue(
                    ad.adUnitId,
                    "Interstitial",
                    adValue,
                    activity.application
                )
            }
        }

        // If show() throws, no FullScreenContentCallback will ever fire, so nothing else would
        // release the gate. The stale-holder watchdog would eventually recover it, but only after
        // five minutes of every full-screen ad in the app being refused.
        return try {
            ad.show(activity)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Monetization :- show() threw for $adUnitId", t)
            releaseGate()
            slot.ad = null
            slot.state = AdSlotState.IDLE
            slot.loadedAtElapsed = 0L
            emit(adUnitId, AdEvent.SHOW_FAILED, slot.state, "show() threw: ${t.javaClass.simpleName}")
            onSuccessListener?.onSuccess(false)
            false
        }
    }

    @MainThread
    override fun showAd(
        activity: Activity,
        adUnitId: String,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        if (!shouldShowAd(context)) {
            emit(adUnitId, AdEvent.SHOW_REJECTED_NOT_READY, stateOf(adUnitId), "premium or offline")
            onSuccessListener?.onSuccess(false)
            return
        }
        val ad = readyAd(adUnitId)
        if (ad == null) {
            emit(adUnitId, AdEvent.SHOW_REJECTED_NOT_READY, stateOf(adUnitId))
            onSuccessListener?.onSuccess(false)
            return
        }
        show(activity, adUnitId, ad, onSuccessListener = onSuccessListener)
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
        Log.d(
            TAG,
            "Monetization :- showAdWithTimeAndCounter: mInterstitialAdCounter: $mInterstitialAdCounter"
        )
        if (readyAd(adUnitId) != null) {
            showAd(activity, adUnitId, onSuccessListener)
        } else {
            onSuccessListener?.onSuccess(false)
            // Warm this unit so the next eligible trigger is not a cold load.
            emit(adUnitId, AdEvent.NEXT_PRELOAD_STARTED, stateOf(adUnitId))
            requestAd(adUnitId, null)
        }
    }

    /**
     * Loads (or reuses) and then shows, optionally behind a loading dialog.
     *
     * The fresh-load branch used to hand the ad straight to a `postDelayed` block and keep no
     * reference to it; if the Activity finished inside that window the ad was orphaned - not even
     * cached for the next attempt. It now goes through the slot like every other path, so the
     * worst case is a deferred impression rather than a lost one.
     */
    @MainThread
    override fun loadAndShowAd(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        if (!shouldShowAd(context) || activity.isFinishing || activity.isDestroyed) {
            emit(adUnitId, AdEvent.SHOW_REJECTED_INVALID_ACTIVITY, stateOf(adUnitId))
            onSuccessListener?.onSuccess(false)
            return
        }

        kotlin.runCatching {
            loadingDialogUtil?.destroy()
            loadingDialogUtil = LoadingDialogUtil.create(activity)

            // Terminal for this flow: hide the dialog and drop it. Holding the util (and through
            // it a Dialog built on this Activity) in a singleton field until some future
            // loadAndShowAd call is what kept destroyed Activities alive.
            val hideDialog = {
                loadingDialogUtil?.destroy()
                loadingDialogUtil = null
                Unit
            }

            readyAd(adUnitId)?.let { cached ->
                if (showDialog) loadingDialogUtil?.showLoadingDialog()
                if (!show(activity, adUnitId, cached, hideDialog, onSuccessListener)) hideDialog()
                return@runCatching
            }

            if (showDialog) loadingDialogUtil?.showLoadingDialog()

            requestAd(adUnitId) { loaded ->
                if (loaded != true) {
                    hideDialog()
                    onSuccessListener?.onSuccess(false)
                    return@requestAd
                }
                // The ad is safely in the slot now; this delay can only postpone the impression.
                Handler(Looper.getMainLooper()).postDelayed({
                    val ad = readyAd(adUnitId)
                    if (ad == null || activity.isFinishing || activity.isDestroyed) {
                        emit(
                            adUnitId,
                            AdEvent.SHOW_REJECTED_INVALID_ACTIVITY,
                            stateOf(adUnitId),
                            "activity gone during adShowDelay - ad kept for next request"
                        )
                        hideDialog()
                        onSuccessListener?.onSuccess(false)
                        return@postDelayed
                    }
                    if (!show(activity, adUnitId, ad, hideDialog, onSuccessListener)) hideDialog()
                }, adShowDelay)
            }
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
        Log.d(
            TAG,
            "Monetization :- shouldShowInterstitialAd: counter=$mInterstitialAdCounter, elapsed=$elapsedTime, counterMet=$adCounterMet, minMet=$minTimeMet, maxMet=$maxTimeMet"
        )
        return (adCounterMet && minTimeMet) || maxTimeMet
    }

    private companion object {
        const val GATE_OWNER = "interstitial"
    }
}
