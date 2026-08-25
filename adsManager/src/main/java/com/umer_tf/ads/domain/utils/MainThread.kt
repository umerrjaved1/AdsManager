package com.umer_tf.ads.domain.utils

import android.os.Handler
import android.os.Looper

/**
 * Runs [block] on the main thread, immediately when already there.
 *
 * The GMA Next-Gen SDK delivers **every** ad callback on a background thread, unlike the legacy
 * SDK which delivered them on the main thread. Every loader in this library touches Views, the
 * shimmer placeholders, the slot state machines and the loading dialog from its callbacks, so each
 * callback body has to be marshalled back or the host app crashes with a
 * `CalledFromWrongThreadException` - or, worse, silently corrupts a slot's state under a race.
 */
internal inline fun onMain(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        mainHandler.post { block() }
    }
}

@PublishedApi
internal val mainHandler: Handler = Handler(Looper.getMainLooper())
