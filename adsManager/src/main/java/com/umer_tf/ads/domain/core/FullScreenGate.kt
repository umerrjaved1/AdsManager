package com.umer_tf.ads.domain.core

import android.os.SystemClock
import android.util.Log

/**
 * Process-wide interlock: exactly one full-screen ad may own the screen at a time.
 *
 * Two of the three host apps grew their own version of this after shipping an app-open ad on top
 * of an interstitial; the third never had one. It belongs here so every placement in every app is
 * covered by the same check.
 *
 * The watchdog matters as much as the flag. `onAdDismissedFullScreenContent` is not guaranteed -
 * a process kill behind the ad, or an SDK that never calls back, would otherwise leave the gate
 * closed for the rest of the session and silently suppress every later full-screen ad.
 */
object FullScreenGate {

    private const val TAG = "FullScreenGate"

    /** No ad legitimately occupies the screen longer than this; past it the flag is stale. */
    private const val MAX_SHOWING_MS = 5 * 60 * 1000L

    @Volatile
    private var owner: String? = null

    @Volatile
    private var startedAtElapsed: Long = 0L

    /** True while a full-screen ad is on screen. Self-heals from a lost dismissal callback. */
    @JvmStatic
    fun isShowing(): Boolean {
        val current = owner ?: return false
        if (SystemClock.elapsedRealtime() - startedAtElapsed > MAX_SHOWING_MS) {
            Log.w(TAG, "Monetization :- stale gate held by $current - releasing")
            release(current)
            return false
        }
        return true
    }

    /** Who owns the screen right now, for diagnostics. */
    @JvmStatic
    fun currentOwner(): String? = owner

    /**
     * Claims the screen for [placement]. Returns false when another full-screen ad already owns
     * it, in which case the caller must not show.
     */
    @JvmStatic
    @Synchronized
    fun acquire(placement: String): Boolean {
        if (isShowing()) {
            Log.d(TAG, "Monetization :- $placement refused - $owner is showing")
            return false
        }
        owner = placement
        startedAtElapsed = SystemClock.elapsedRealtime()
        return true
    }

    /** Releases the screen. Ignores a release from a placement that does not hold the gate. */
    @JvmStatic
    @Synchronized
    fun release(placement: String) {
        if (owner != null && owner != placement) {
            Log.w(TAG, "Monetization :- $placement tried to release a gate held by $owner")
            return
        }
        owner = null
        startedAtElapsed = 0L
    }
}
