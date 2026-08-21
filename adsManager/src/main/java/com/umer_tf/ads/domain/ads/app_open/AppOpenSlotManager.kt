package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
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
import com.umer_tf.ads.domain.utils.onMainThread
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
     * Note the callback contract: `true` means an ad is ready (just loaded **or** already
     * cached). `false` means no-fill, premium/offline, or a join that has not settled yet.
     * Callers that only want to know whether something is cached should still ask
     * [isAdAvailable]; do not treat `false` as "definitely empty" while a request is in flight.
     */
    fun loadAd(context: Context, onSuccessListener: OnSuccessListener<Boolean>?) {
        if (isAdAvailable()) {
            emit(AdEvent.REQUEST_SKIPPED_CACHED)
            onSuccessListener?.onSuccess(true)
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

        // The ad unit id now lives on the request instead of being a separate load() argument, and
        // load() no longer takes a Context - the SDK uses the one it was initialized with.
        AppOpenAd.load(
            AdRequest.Builder(unitId).build(),
            object : AdLoadCallback<AppOpenAd> {
                // Both callbacks arrive on a background thread in the Next-Gen SDK. Slot state is
                // documented as main-thread-only and `settle` runs host callbacks that load more
                // ads and touch views, so neither may run where the SDK delivers it.
                override fun onAdLoaded(ad: AppOpenAd) = onMainThread {
                    appOpenAd = ad
                    state = AdSlotState.READY
                    loadTimeElapsed = SystemClock.elapsedRealtime()
                    Log.d(tag, "Monetization :- $gateOwner - onAdLoaded.")
                    emit(AdEvent.LOAD_SUCCESS)
                    emit(AdEvent.READY)
                    settle(true)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                    state = AdSlotState.FAILED
                    Log.d(tag, "Monetization :- $gateOwner - onAdFailedToLoad: ${adError.message}")
                    emit(AdEvent.LOAD_FAILURE, adError.message)
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

        // One callback object now covers the lifecycle *and* the paid event - the separate
        // OnPaidEventListener is gone, onAdPaid is part of AdEventCallback. Every body hops to the
        // main thread: the SDK delivers these on a background thread, and they mutate slot state,
        // release the full-screen gate, and call back into the host.
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() = onMainThread {
                // Consumed at display time, not at dismissal: an app-open ad shows exactly once,
                // and leaving it cached invited a second show attempt against a dead ad.
                appOpenAd = null
                loadTimeElapsed = 0L
                state = AdSlotState.SHOWING
                emit(AdEvent.SHOW_STARTED)
                AnalyticsManager.getInstance(application).sendAnalytics(AD_SHOWN, analyticsLabel)
                Log.d(tag, "Monetization :- $gateOwner - onAdShowedFullScreenContent.")
            }

            override fun onAdDismissedFullScreenContent() = onMainThread {
                releaseGate()
                state = AdSlotState.IDLE
                onStateChange(false)
                emit(AdEvent.DISMISSED)
                AnalyticsManager.getInstance(application).sendAnalytics(AD_DISMISSED, analyticsLabel)
                onShowAdCompleteListener.onSuccess(true)
            }

            override fun onAdFailedToShowFullScreenContent(
                fullScreenContentError: FullScreenContentError
            ) = onMainThread {
                releaseGate()
                appOpenAd = null
                loadTimeElapsed = 0L
                state = AdSlotState.IDLE
                onStateChange(false)
                emit(AdEvent.SHOW_FAILED, fullScreenContentError.message)
                Log.d(
                    tag,
                    "Monetization :- $gateOwner - onAdFailedToShow: ${fullScreenContentError.message}"
                )
                onShowAdCompleteListener.onSuccess(false)
            }

            override fun onAdPaid(value: AdValue) {
                // Already off the main thread, and this only reaches analytics, so no hop needed.
                val unitId = ad.adUnitId
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        AdsAnalytics.logAppsFlyerRevenue(unitId, analyticsLabel, value, application)
                    }.onFailure { Log.e(tag, "Failed to log $gateOwner revenue", it) }
                }
            }
        }

        onStateChange(true)
        try {
            ad.show(activity)
        } catch (t: Throwable) {
            // No callback will fire, so nothing else releases the gate or the showing flag.
            Log.e(tag, "Monetization :- $gateOwner show() threw", t)
            releaseGate()
            appOpenAd = null
            loadTimeElapsed = 0L
            state = AdSlotState.IDLE
            onStateChange(false)
            emit(AdEvent.SHOW_FAILED, "show() threw: ${t.javaClass.simpleName}")
            onShowAdCompleteListener.onSuccess(false)
        }
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
