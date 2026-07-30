package com.umer_tf.ads.domain.utils

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Whether the **host app** is a debuggable build.
 *
 * This exists because a library must never branch on its own `BuildConfig.DEBUG`. A published AAR is
 * built in release, so inside the library that constant is permanently `false` - regardless of how
 * the consuming app was built. Anything gated on it therefore behaves as "release" even in a
 * developer's debug build, which produced a false
 * "Google TEST ad unit id is in use outside a debug build" warning on every debug run.
 *
 * Detected from the host's `ApplicationInfo`, so no setup is required: [AdMobManager] calls
 * [detectFrom] when it is first created.
 *
 * @see com.umer_tf.ads.domain.core.AdMobManager
 */
object AdsEnvironment {

    @Volatile
    private var explicitOverride: Boolean? = null

    @Volatile
    private var detected: Boolean = false

    /**
     * True when the host app is debuggable.
     *
     * Set it directly to override the detected value - useful for a QA build that is technically
     * release-signed but should behave like debug.
     */
    @JvmStatic
    var isHostDebuggable: Boolean
        get() = explicitOverride ?: detected
        set(value) {
            explicitOverride = value
        }

    /**
     * Reads `FLAG_DEBUGGABLE` from the host's manifest. This is the same flag the platform and Play
     * use to decide what a debug build is, and it cannot be faked by a release APK.
     */
    @JvmStatic
    fun detectFrom(context: Context) {
        detected = runCatching {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false)
    }
}
