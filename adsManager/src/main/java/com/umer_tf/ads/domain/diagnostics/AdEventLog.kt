package com.umer_tf.ads.domain.diagnostics

import android.os.SystemClock
import android.util.Log
import com.umer_tf.ads.domain.core.AdSlotState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Ad format a diagnostic record belongs to. */
enum class AdFormat {
    INTERSTITIAL,
    APP_OPEN_START,
    APP_OPEN_RESUME,
    NATIVE,
    BANNER,
    REWARDED;

    /** Short, fixed-width label so log lines stay in columns. */
    internal val short: String
        get() = when (this) {
            INTERSTITIAL -> "inter "
            APP_OPEN_START -> "open-s"
            APP_OPEN_RESUME -> "open-r"
            NATIVE -> "native"
            BANNER -> "banner"
            REWARDED -> "reward"
        }
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
    /**
     * The host had a cached ad and chose not to show it — a cooldown, a frequency cap, an
     * excluded screen. Emit it *only* when an ad was actually in hand: skipping with an empty
     * cache costs nothing and would drown the real losses in noise.
     */
    SHOW_SKIPPED_BY_RULE,
    SHOW_STARTED,
    SHOW_FAILED,
    DISMISSED,
    AD_EXPIRED,
    AD_DESTROYED,
    NEXT_PRELOAD_STARTED;

    /**
     * What a reader needs to understand at a glance. Losses are upper-cased so they stand out
     * when scanning a wall of logcat; everything else stays lower-case and quiet.
     */
    internal val plain: String
        get() = when (this) {
            REQUEST_STARTED -> "request"
            REQUEST_JOINED -> "joined"
            REQUEST_SKIPPED_CACHED -> "cached"
            LOAD_SUCCESS -> "loaded"
            LOAD_FAILURE -> "NO FILL"
            READY -> "ready"
            SHOW_REQUESTED -> "show"
            SHOW_REJECTED_NOT_READY -> "NOT SHOWN"
            SHOW_REJECTED_INVALID_ACTIVITY -> "NOT SHOWN"
            SHOW_REJECTED_ALREADY_SHOWING -> "NOT SHOWN"
            SHOW_SKIPPED_BY_RULE -> "NOT SHOWN"
            SHOW_STARTED -> "SHOWING"
            SHOW_FAILED -> "SHOW FAILED"
            DISMISSED -> "dismissed"
            AD_EXPIRED -> "EXPIRED"
            AD_DESTROYED -> "DISCARDED"
            NEXT_PRELOAD_STARTED -> "warming next"
        }

    /** Extra detail appended when the event name alone does not say why. */
    internal val why: String?
        get() = when (this) {
            REQUEST_JOINED -> "a request for this unit was already running"
            REQUEST_SKIPPED_CACHED -> "already loaded, no request needed"
            SHOW_REJECTED_NOT_READY -> "nothing cached"
            SHOW_REJECTED_INVALID_ACTIVITY -> "no usable screen"
            SHOW_REJECTED_ALREADY_SHOWING -> "another full-screen ad is up"
            else -> null
        }

    /** True when this event represents a matched ad that will never become an impression. */
    internal val isLoss: Boolean
        get() = this == SHOW_REJECTED_NOT_READY ||
            this == SHOW_REJECTED_INVALID_ACTIVITY ||
            this == SHOW_REJECTED_ALREADY_SHOWING ||
            this == SHOW_SKIPPED_BY_RULE ||
            this == SHOW_FAILED ||
            this == AD_EXPIRED ||
            this == AD_DESTROYED
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
 * The logcat output is written to be read by a person during a QA pass, not parsed by a tool:
 * fixed-width columns, plain-English outcomes, losses in capitals, and a load duration on every
 * fill so a slow placement is obvious without timestamp arithmetic.
 */
object AdEventLog {

    private const val TAG = "AdEventLog"

    fun interface Listener {
        fun onAdEvent(record: AdEventRecord)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** Host-supplied unit id → placement name, so logs read "splash" rather than "…5399236686". */
    private val placementNames = ConcurrentHashMap<String, String>()

    /**
     * Placement that currently owns a unit's slot — see [markPlacement].
     *
     * [placementNames] cannot do this job on its own: several placements normally resolve to one
     * ad unit (the feature-click, 25s-timer and function-complete interstitials are all the same
     * `homeInter` unit), so registering a name per placement means the last one silently wins and
     * every line for that unit claims to be that placement.
     */
    private val activePlacement = ConcurrentHashMap<String, String>()

    /** When the in-flight request for a unit started, so a fill can report how long it took. */
    private val requestStartedAt = ConcurrentHashMap<String, Long>()

    /** Set false in release builds of the host if the logcat noise is unwanted. */
    @JvmStatic
    @Volatile
    var verboseLogging: Boolean = true

    /**
     * Second, deliberately tiny log stream under its own tag.
     *
     * [verboseLogging] answers "why did this placement lose an impression" and carries sixteen
     * event types to do it. This one answers "is anything happening" in five words, so it can be
     * watched during a normal QA pass without a legend. Both run in release.
     *
     * `adb logcat -s mona:D`
     */
    @JvmStatic
    @Volatile
    var simpleLogging: Boolean = true

    /** Tag for the simple stream. Change it if `mona` collides with something else. */
    @JvmStatic
    @Volatile
    var simpleLogTag: String = "mona"

    /**
     * Units that have already logged `loaded` for the request currently in flight.
     *
     * Interstitials and app-open ads emit LOAD_SUCCESS *and* READY for a single fill, which would
     * otherwise print `loaded` twice for one ad and make the simple stream lie about how many
     * fills arrived.
     */
    private val loadedLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * Registers human names for ad units.
     *
     * Without this a reader sees a 10-digit unit id and has to go looking for which placement it
     * belongs to — the single biggest obstacle to reading these logs. Call it once at startup
     * with whatever the host's AdIds resolve to.
     */
    /**
     * Records which placement is about to use [adUnitId], so its events are labelled with the
     * placement rather than with whichever name happened to be registered last for that unit.
     *
     * Call it immediately before requesting and again immediately before showing: a preload
     * started by one placement is often shown by another, and both halves should say so. Safe to
     * call repeatedly — a unit has one slot, so only one placement can own it at a time.
     */
    @JvmStatic
    fun markPlacement(adUnitId: String, placement: String) {
        if (adUnitId.isBlank() || placement.isBlank()) return
        activePlacement[adUnitId] = placement
    }

    @JvmStatic
    fun namePlacements(names: Map<String, String>) {
        names.forEach { (unitId, name) ->
            if (unitId.isNotBlank()) placementNames[unitId] = name
        }
    }

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
            Log.d(TAG, "Monetization :- ${describe(format, adUnitId, event, reason)}")
        }
        if (simpleLogging) {
            logSimple(format, adUnitId, event, reason)
        }
        trackTiming(format, adUnitId, event)
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

    /**
     * The five-word stream: `request` · `load` · `loaded` · `show` · `fail`.
     *
     * Anything that does not map to one of those is simply not printed here — the detailed
     * stream still has it. The point of this one is that it can be read at a glance without
     * knowing what any of the sixteen event names mean.
     *
     * ```
     * mona: request  inter   splash-inter
     * mona: load     inter   splash-inter    joined a request already running
     * mona: loaded   inter   splash-inter    10.2s
     * mona: show     inter   splash-inter
     * mona: fail     open-r  app-resume      nothing cached
     * ```
     */
    private fun logSimple(
        format: AdFormat,
        adUnitId: String,
        event: AdEvent,
        reason: String?,
    ) {
        val key = timingKey(format, adUnitId)
        if (event == AdEvent.REQUEST_STARTED) loadedLogged.remove(key)

        val word = when (event) {
            AdEvent.REQUEST_STARTED -> "request"
            AdEvent.REQUEST_JOINED, AdEvent.REQUEST_SKIPPED_CACHED -> "load"
            AdEvent.LOAD_SUCCESS, AdEvent.READY -> {
                // One `loaded` per fill, whichever of the two events arrives first.
                if (loadedLogged.putIfAbsent(key, true) != null) return
                "loaded"
            }
            AdEvent.SHOW_STARTED -> "show"
            AdEvent.LOAD_FAILURE,
            AdEvent.SHOW_REJECTED_NOT_READY,
            AdEvent.SHOW_REJECTED_INVALID_ACTIVITY,
            AdEvent.SHOW_REJECTED_ALREADY_SHOWING,
            AdEvent.SHOW_SKIPPED_BY_RULE,
            AdEvent.SHOW_FAILED,
            AdEvent.AD_EXPIRED,
            AdEvent.AD_DESTROYED -> "fail"
            // show-requested, dismissed and preload bookkeeping stay in the detailed stream only.
            else -> return
        }

        val note = when (word) {
            "loaded" -> loadDuration(format, adUnitId, event)
            "load" -> event.why
            "fail" -> reason ?: event.why
            else -> null
        }
        val line = buildString {
            append(word.padEnd(8))
            append(format.short)
            append("  ")
            append(placementLabel(adUnitId).padEnd(18).take(18))
            // Full unit id, so a line can be matched against the AdMob console without a lookup.
            append(adUnitId.ifBlank { "(no unit)" })
            note?.takeIf { it.isNotBlank() }?.let {
                append("   ")
                append(it)
            }
        }
        Log.d(simpleLogTag, line)
    }

    /** `inter   splash          loaded     · 10.2s` */
    private fun describe(
        format: AdFormat,
        adUnitId: String,
        event: AdEvent,
        reason: String?,
    ): String = buildString {
        append(format.short)
        append("  ")
        append(placementLabel(adUnitId).padEnd(18).take(18))
        append(event.plain.padEnd(12))
        // Full unit id, so a line can be matched against the AdMob console without a lookup.
        append(adUnitId.ifBlank { "(no unit)" })
        val notes = listOfNotNull(loadDuration(format, adUnitId, event), event.why, reason)
        if (notes.isNotEmpty()) {
            append("  · ")
            append(notes.joinToString(" · "))
        }
    }

    /** Placement name when the host registered one, else the last 4 digits of the unit. */
    private fun placementLabel(adUnitId: String): String {
        activePlacement[adUnitId]?.let { return it }
        placementNames[adUnitId]?.let { return it }
        val digits = adUnitId.filter { it.isDigit() }
        return if (digits.length >= 4) "unit-${digits.takeLast(4)}" else "unknown"
    }

    private fun timingKey(format: AdFormat, adUnitId: String) = "${format.name}|$adUnitId"

    private fun trackTiming(format: AdFormat, adUnitId: String, event: AdEvent) {
        when (event) {
            AdEvent.REQUEST_STARTED ->
                requestStartedAt[timingKey(format, adUnitId)] = SystemClock.elapsedRealtime()
            AdEvent.LOAD_FAILURE, AdEvent.READY ->
                requestStartedAt.remove(timingKey(format, adUnitId))
            else -> Unit
        }
    }

    /**
     * How long the request took, on the events that conclude one.
     *
     * Worth having inline: a placement that fills in 10s on a real device is one timeout away
     * from being an abandoned request, and that is invisible unless you subtract timestamps.
     */
    private fun loadDuration(format: AdFormat, adUnitId: String, event: AdEvent): String? {
        if (event != AdEvent.LOAD_SUCCESS && event != AdEvent.READY && event != AdEvent.LOAD_FAILURE) {
            return null
        }
        val startedAt = requestStartedAt[timingKey(format, adUnitId)] ?: return null
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        return String.format(java.util.Locale.US, "%.1fs", seconds)
    }

    /** Snapshot of `FORMAT|EVENT -> count` since process start. */
    @JvmStatic
    fun counts(): Map<String, Int> = counts.mapValues { it.value.get() }

    /**
     * Prints one line per format: how many requests went out, how many filled, how many were
     * shown, how many were lost after matching, and how many duplicate requests were avoided.
     *
     * `req` vs `shown` is the whole story — everything in between is where impressions go
     * missing, and `lost` names it.
     */
    @JvmStatic
    fun dumpCounts() {
        val snapshot = counts()
        fun n(format: AdFormat, event: AdEvent) = snapshot["${format.name}|${event.name}"] ?: 0

        val lossEvents = AdEvent.values().filter { it.isLoss }

        Log.d(TAG, "Monetization :- ======== AD FUNNEL (this session) ========")
        AdFormat.values().forEach { format ->
            val requested = n(format, AdEvent.REQUEST_STARTED)
            // Every fill path emits LOAD_SUCCESS exactly once, so this is a straight count.
            // It used to be derived from LOAD_SUCCESS and READY together, which double-counted
            // the formats that emit both for one fill.
            val filled = n(format, AdEvent.LOAD_SUCCESS)
            val noFill = n(format, AdEvent.LOAD_FAILURE)
            val shown = n(format, AdEvent.SHOW_STARTED)
            val saved = n(format, AdEvent.REQUEST_JOINED) +
                n(format, AdEvent.REQUEST_SKIPPED_CACHED)
            val lost = lossEvents.sumOf { n(format, it) }
            // Matched ads that neither reached the screen nor hit a named loss. Some are simply
            // still in cache and will show later; the rest are the silent losses that used to
            // report as LOST 0 while `filled` sat well above `shown` on the same line.
            val unshown = (filled - shown - lost).coerceAtLeast(0)
            if (requested == 0 && filled == 0 && shown == 0 && saved == 0) return@forEach
            Log.d(
                TAG,
                "Monetization :- ${format.short}  req $requested   fill $filled   " +
                    "shown $shown   nofill $noFill   LOST $lost   UNSHOWN $unshown   saved $saved"
            )
        }
        val anyLoss = AdFormat.values().any { f -> lossEvents.any { n(f, it) > 0 } }
        if (anyLoss) {
            Log.d(TAG, "Monetization :- -- where they were lost --")
            AdFormat.values().forEach { format ->
                lossEvents.forEach { event ->
                    val c = n(format, event)
                    if (c > 0) {
                        val why = event.why?.let { " ($it)" } ?: ""
                        Log.d(TAG, "Monetization :- ${format.short}  ${event.plain}$why x$c")
                    }
                }
            }
        }
        Log.d(TAG, "Monetization :- ==========================================")
    }

    @JvmStatic
    fun resetCounts() {
        counts.clear()
        requestStartedAt.clear()
        loadedLogged.clear()
    }
}
