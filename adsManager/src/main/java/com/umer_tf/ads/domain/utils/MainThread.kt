package com.umer_tf.ads.domain.utils

import android.os.Handler
import android.os.Looper

/**
 * Runs [block] on the main thread.
 *
 * Every callback in the Google Mobile Ads SDK (Next-Gen) is delivered on a **background** thread -
 * unlike the legacy SDK, which always called back on the main thread. Each loader in this library
 * reacts to those callbacks by touching views (shimmer visibility, the ad container), mutating slot
 * state that is documented as main-thread-only, and invoking host callbacks that navigate or update
 * UI. Doing any of that off the main thread throws `CalledFromWrongThreadException` at best and
 * corrupts slot state at worst, so every SDK callback body is funnelled through here.
 *
 * Runs inline when already on the main thread, so a callback that the SDK happens to deliver there
 * keeps its ordering guarantees instead of being deferred behind whatever is already queued - which
 * matters for the load/show sequencing in the full-screen loaders.
 */
internal inline fun onMainThread(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        mainThreadHandler.post { block() }
    }
}

@PublishedApi
internal val mainThreadHandler: Handler = Handler(Looper.getMainLooper())
