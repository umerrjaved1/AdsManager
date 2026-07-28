package com.umer_tf.ads.domain.utils

import android.util.Log
import com.umer_tf.ads.BuildConfig

/**
 * Logging gate for the ads library.
 *
 * Every ad event used to be written with `Log.e`, so a release build spammed logcat at ERROR level
 * for ordinary lifecycle events - noise that also lands in log-collection and crash-triage tooling.
 * Informational events now go through [d] and are silent unless logging is enabled; genuine failures
 * still go through [e].
 *
 * Enabled by default in debug builds. Turn it on for a release build (to debug mediation, say) with:
 * ```kotlin
 * AdsLog.isEnabled = true
 * ```
 */
object AdsLog {

    @JvmStatic
    var isEnabled: Boolean = BuildConfig.DEBUG

    @JvmStatic
    @JvmOverloads
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (isEnabled) {
            if (throwable != null) Log.d(tag, message, throwable) else Log.d(tag, message)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }

    @JvmStatic
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
}
