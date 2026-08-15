package com.umer_tf.ads.domain.diagnostics

import android.util.Log
import com.umer_tf.ads.domain.core.AdSlotState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

/** Ad format a diagnostic record belongs to. */
enum class AdFormat {
    INTERSTITIAL,
    APP_OPEN_START,
    APP_OPEN_RESUME,
    NATIVE,
    BANNER,
    REWARDED,
}

/**
 * Every point at which an ad can be gained or lost.
 *
 * The set is chosen to answer one question from logs alone: of the ads AdMob matched, where did
 * the ones that never became impressions go? Each terminal outcome has its own event, so
 * "rejected because nothing was ready" is never confused with "rejected because the Activity was
 * gone" or "shown and then failed to display".
 */
enum class AdEvent {
    REQUEST_STARTED,
    REQUEST_JOINED,
    REQUEST_SKIPPED_CACHED,
    LOAD_SUCCESS,
    LOAD_FAILURE,
    READY,
    SHOW_REQUESTED,
    SHOW_REJECTED_NOT_READY,
    SHOW_REJECTED_INVALID_ACTIVITY,
    SHOW_REJECTED_ALREADY_SHOWING,
    SHOW_STARTED,
    SHOW_FAILED,
    DISMISSED,
    AD_EXPIRED,
    AD_DESTROYED,
    NEXT_PRELOAD_STARTED,
}

/**
 * One diagnostic record. [reason] carries the detail that distinguishes otherwise identical
 * events (which Activity, which error, which cap) and must never contain user data.
 */
data class AdEventRecord(
    val format: AdFormat,
    val adUnitId: String,
    val event: AdEvent,
    val state: AdSlotState,
    val reason: String?,
    val timestampMs: Long,
)

/**
 * Single emission point for ad lifecycle diagnostics.
 *
 * Hosts subscribe and forward to their own analytics. The SDK never calls Firebase itself from
 * here, so a host that already logs a placement's funnel cannot end up double-counting it.
 *
 * Counters are kept in-process as well, so the same question can be answered from a `dumpCounts()`
 * during a debug session without wiring analytics at all.
 */
object AdEventLog {

    private const val TAG = "AdEventLog"

    fun interface Listener {
        fun onAdEvent(record: AdEventRecord)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** Set false in release builds of the host if the logcat noise is unwanted. */
    @JvmStatic
    @Volatile
    var verboseLogging: Boolean = true

    @JvmStatic
    fun subscribe(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    @JvmStatic
    fun unsubscribe(listener: Listener) {
        listeners.remove(listener)
    }

    @JvmStatic
    @JvmOverloads
    fun emit(
        format: AdFormat,
        adUnitId: String,
        event: AdEvent,
        state: AdSlotState = AdSlotState.IDLE,
        reason: String? = null,
    ) {
        counts.getOrPut("${format.name}|${event.name}") { AtomicInteger() }.incrementAndGet()
        if (verboseLogging) {
            Log.d(TAG, "Monetization :- ${format.name} ${event.name} state=$state unit=$adUnitId${reason?.let { " reason=$it" } ?: ""}")
        }
        if (listeners.isEmpty()) return
        val record = AdEventRecord(
            format = format,
            adUnitId = adUnitId,
            event = event,
            state = state,
            reason = reason,
            timestampMs = System.currentTimeMillis(),
        )
        listeners.forEach { listener ->
            // A misbehaving host listener must never break ad delivery.
            runCatching { listener.onAdEvent(record) }
                .onFailure { Log.w(TAG, "Ad event listener threw", it) }
        }
    }

    /** Snapshot of `FORMAT|EVENT -> count` since process start. */
    @JvmStatic
    fun counts(): Map<String, Int> = counts.mapValues { it.value.get() }

    /** Dumps the funnel to logcat - the fastest way to see where matched ads are being lost. */
    @JvmStatic
    fun dumpCounts() {
        Log.d(TAG, "Monetization :- ===== ad funnel =====")
        counts().toSortedMap().forEach { (key, value) -> Log.d(TAG, "Monetization :- $key = $value") }
    }

    @JvmStatic
    fun resetCounts() {
        counts.clear()
    }
}
