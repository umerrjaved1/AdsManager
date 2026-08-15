package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.core.AdSlotState
import com.umer_tf.ads.domain.core.FullScreenGate
import com.umer_tf.ads.domain.diagnostics.AdEvent
import com.umer_tf.ads.domain.diagnostics.AdEventLog
import com.umer_tf.ads.domain.diagnostics.AdFormat
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_DISMISSED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_SHOWN
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One app-open ad slot with an explicit state machine.
 *
 * Start and resume ads differ only in which unit they request and which analytics label they
 * carry, so they share everything else: single in-flight request, 4 h expiry, the full-screen
 * interlock, and the rule that a fill is only dropped once it has actually been shown.
 */
internal abstract class AppOpenSlotManager(
    private val application: Application,
    private val format: AdFormat,
    private val analyticsLabel: String,
    private val gateOwner: String,
) {

    private var appOpenAd: AppOpenAd? = null
    private var loadTimeElapsed: Long = 0L
    private val waiters = mutableListOf<OnSuccessListener<Boolean>>()

    var state: AdSlotState = AdSlotState.IDLE
        private set

    /** The ad unit for this slot, read at request time so remote config changes take effect. */
    protected abstract fun adUnitId(): String

    private val tag: String get() = gateOwner

    private fun emit(event: AdEvent, reason: String? = null) {
        AdEventLog.emit(format, adUnitId(), event, state, reason)
    }

    /**
     * Requests an ad unless one is cached or already in flight.
     *
     * Note the callback contract, which surprised two of the three host apps: a `false` here does
     * **not** mean no-fill. It also means "an ad is already available" or "a request is already
     * running". Callers deciding whether to show something must ask [isAdAvailable], never infer
     * it from this result.
     */
    fun loadAd(context: Context, onSuccessListener: OnSuccessListener<Boolean>?) {
        if (isAdAvailable()) {
            emit(AdEvent.REQUEST_SKIPPED_CACHED)
            onSuccessListener?.onSuccess(false)
            return
        }
        if (state.isLoading) {
            emit(AdEvent.REQUEST_JOINED)
            onSuccessListener?.let { waiters.add(it) }
            return
        }
        if (!shouldShowAd(context)) {
            emit(AdEvent.LOAD_FAILURE, "premium or offline")
            onSuccessListener?.onSuccess(false)
            return
        }

        val unitId = adUnitId()
        if (unitId.isBlank()) {
            // Configuring the slot is the host's job; requesting against "" can only fail and
            // would look like a no-fill in the reports.
            Log.w(tag, "Monetization :- $gateOwner load skipped - no ad unit configured")
            emit(AdEvent.LOAD_FAILURE, "ad unit id not configured")
            onSuccessListener?.onSuccess(false)
            return
        }

        state = AdSlotState.LOADING
        onSuccessListener?.let { waiters.add(it) }
        emit(AdEvent.REQUEST_STARTED)

        AppOpenAd.load(
            context,
            unitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    state = AdSlotState.READY
                    loadTimeElapsed = SystemClock.elapsedRealtime()
                    Log.d(tag, "Monetization :- $gateOwner - onAdLoaded.")
                    emit(AdEvent.LOAD_SUCCESS)
                    emit(AdEvent.READY)
                    settle(true)
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    state = AdSlotState.FAILED
                    Log.d(tag, "Monetization :- $gateOwner - onAdFailedToLoad: ${loadAdError.message}")
                    emit(AdEvent.LOAD_FAILURE, loadAdError.message)
                    settle(false)
                }
            }
        )
    }

    private fun settle(success: Boolean) {
        if (waiters.isEmpty()) return
        val pending = waiters.toList()
        waiters.clear()
        pending.forEach { it.onSuccess(success) }
    }

    /**
     * Shows the cached ad on [activity].
     *
     * Every refusal path reports a distinct diagnostic reason, because "the ad did not show" was
     * exactly the ambiguity that made a 12% show rate impossible to explain from logs.
     */
    fun showAd(
        activity: Activity?,
        onShowAdCompleteListener: OnSuccessListener<Boolean>,
        onStateChange: (Boolean) -> Unit
    ) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            emit(AdEvent.SHOW_REJECTED_INVALID_ACTIVITY, activity?.javaClass?.simpleName ?: "null")
            onShowAdCompleteListener.onSuccess(false)
            return
        }
        val ad = appOpenAd
        if (ad == null || !isAdAvailable()) {
            emit(AdEvent.SHOW_REJECTED_NOT_READY)
            onShowAdCompleteListener.onSuccess(false)
            return
        }
        emit(AdEvent.SHOW_REQUESTED, activity.javaClass.simpleName)
        if (!FullScreenGate.acquire(gateOwner)) {
            emit(AdEvent.SHOW_REJECTED_ALREADY_SHOWING, FullScreenGate.currentOwner())
            onShowAdCompleteListener.onSuccess(false)
            return
        }

        var released = false
        fun releaseGate() {
            if (released) return
            released = true
            FullScreenGate.release(gateOwner)
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                // Consumed at display time, not at dismissal: an app-open ad shows exactly once,
                // and leaving it cached invited a second show attempt against a dead ad.
                appOpenAd = null
                loadTimeElapsed = 0L
                state = AdSlotState.SHOWING
                emit(AdEvent.SHOW_STARTED)
                AnalyticsManager.getInstance(application).sendAnalytics(AD_SHOWN, analyticsLabel)
                Log.d(tag, "Monetization :- $gateOwner - onAdShowedFullScreenContent.")
            }

            override fun onAdDismissedFullScreenContent() {
                releaseGate()
                state = AdSlotState.IDLE
                onStateChange(false)
                emit(AdEvent.DISMISSED)
                AnalyticsManager.getInstance(application).sendAnalytics(AD_DISMISSED, analyticsLabel)
                onShowAdCompleteListener.onSuccess(true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                releaseGate()
                appOpenAd = null
                loadTimeElapsed = 0L
                state = AdSlotState.IDLE
                onStateChange(false)
                emit(AdEvent.SHOW_FAILED, adError.message)
                Log.d(tag, "Monetization :- $gateOwner - onAdFailedToShow: ${adError.message}")
                onShowAdCompleteListener.onSuccess(false)
            }
        }

        ad.setOnPaidEventListener { adValue ->
            val unitId = ad.adUnitId
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    AdsAnalytics.logAppsFlyerRevenue(unitId, analyticsLabel, adValue, application)
                }.onFailure { Log.e(tag, "Failed to log $gateOwner revenue", it) }
            }
        }

        onStateChange(true)
        ad.show(activity)
    }

    /** True when an unexpired ad is cached and ready to display. */
    fun isAdAvailable(): Boolean {
        if (appOpenAd == null) return false
        if (isAdExpired()) {
            Log.d(tag, "Monetization :- $gateOwner - cached ad expired")
            emit(AdEvent.AD_EXPIRED)
            appOpenAd = null
            loadTimeElapsed = 0L
            state = AdSlotState.IDLE
            return false
        }
        return true
    }

    fun destroy() {
        if (appOpenAd != null) emit(AdEvent.AD_DESTROYED)
        appOpenAd = null
        waiters.clear()
        loadTimeElapsed = 0L
        state = AdSlotState.IDLE
    }

    private fun isAdExpired(): Boolean =
        loadTimeElapsed > 0L && SystemClock.elapsedRealtime() - loadTimeElapsed > AD_EXPIRATION_TIME_MS

    private companion object {
        /** Google's documented validity window for an app-open ad. */
        const val AD_EXPIRATION_TIME_MS = 4 * 60 * 60 * 1000L
    }
}
