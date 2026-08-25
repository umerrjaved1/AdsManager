package com.umer_tf.ads.domain.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationStatus
import com.google.android.libraries.ads.mobile.sdk.initialization.OnAdapterInitializationCompleteListener
import com.umer_tf.ads.domain.utils.onMain
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns GMA Next-Gen SDK initialization for the whole library.
 *
 * The legacy SDK self-initialized from a manifest `<meta-data>` tag, which is why
 * [com.umer_tf.ads.domain.core.AdMobManager.initialize] was deprecated. The Next-Gen SDK does not:
 * `MobileAds.initialize()` must run - on a background thread - and complete *before* any load
 * call, or the request is dropped.
 *
 * Every loader therefore routes its request through [runWhenInitialized] instead of calling the
 * SDK directly, so a host that never calls `initialize()` still gets working ads on the first
 * placement rather than a silent no-op.
 *
 * The application id is read from the same manifest tag the legacy SDK used
 * (`com.google.android.gms.ads.APPLICATION_ID`), which the UMP SDK still requires, so hosts need
 * no manifest change. [setApplicationId] overrides it for hosts that resolve the id at runtime.
 */
object AdsInitializer {

    private const val TAG = "AdsInitializer"

    /** The manifest tag the legacy SDK read, kept as the source of truth for the app id. */
    const val APPLICATION_ID_METADATA = "com.google.android.gms.ads.APPLICATION_ID"

    private val lock = Any()
    private val started = AtomicBoolean(false)
    private val initExecutor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "ads-init") } }

    @Volatile
    private var initialized = false

    /** Callbacks parked until initialization settles. Guarded by [lock]. */
    private val pending = mutableListOf<() -> Unit>()

    @Volatile
    private var overriddenApplicationId: String? = null

    /** True once `MobileAds.initialize` has reported back. */
    @JvmStatic
    fun isInitialized(): Boolean = initialized

    /**
     * Sets the AdMob application id explicitly, overriding the manifest value.
     * Must be called before the first ad request to have any effect.
     */
    @JvmStatic
    fun setApplicationId(applicationId: String) {
        overriddenApplicationId = applicationId
    }

    /**
     * Initializes the SDK if it has not been initialized yet.
     *
     * @param onComplete invoked on the main thread once initialization settles. It also runs when
     * initialization could not be attempted (no application id), so callers never hang.
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(context: Context, onComplete: (() -> Unit)? = null) {
        runWhenInitialized(context, onComplete)
    }

    /**
     * Runs [action] on the main thread once the SDK is ready, or right away when it already is.
     *
     * [action] runs even when initialization failed: the loaders' own callbacks are what report
     * failure to the host, and swallowing the action here would leave slots stuck in `LOADING`.
     */
    fun runWhenInitialized(context: Context, action: (() -> Unit)?) {
        if (initialized) {
            action?.let { onMain(it) }
            start(context)
            return
        }
        var runNow = false
        synchronized(lock) {
            if (initialized) {
                runNow = true
            } else if (action != null) {
                pending.add(action)
            }
        }
        if (runNow) action?.let { onMain(it) }
        start(context)
    }

    private fun start(context: Context) {
        if (initialized || !started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val applicationId = overriddenApplicationId ?: readApplicationId(appContext)
        if (applicationId.isNullOrBlank()) {
            Log.e(
                TAG,
                "Monetization :- no AdMob application id. Declare <meta-data " +
                    "android:name=\"$APPLICATION_ID_METADATA\" .../> in the host manifest or call " +
                    "AdsInitializer.setApplicationId(). Ads cannot load."
            )
            // Released rather than parked forever: a caller waiting on an id that will never
            // arrive is a hung placement, not a deferred one.
            settle()
            return
        }

        // Mandatory: initialize() blocks while it does disk and network work.
        initExecutor.execute {
            runCatching {
                MobileAds.initialize(
                    appContext,
                    InitializationConfig.Builder(applicationId).build(),
                    object : OnAdapterInitializationCompleteListener {
                        override fun onAdapterInitializationComplete(status: InitializationStatus) {
                            Log.d(
                                TAG,
                                "Monetization :- AdMob initialized: ${status.adapterStatusMap}"
                            )
                            settle()
                        }
                    }
                )
            }.onFailure {
                Log.e(TAG, "Monetization :- AdMob initialization threw", it)
                settle()
            }
        }
    }

    private fun settle() {
        val waiting: List<() -> Unit>
        synchronized(lock) {
            initialized = true
            waiting = pending.toList()
            pending.clear()
        }
        if (waiting.isEmpty()) return
        onMain { waiting.forEach { it() } }
    }

    private fun readApplicationId(context: Context): String? = runCatching {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        val metaData = info.metaData ?: return null
        // Declared as a string resource in most hosts; getString resolves both forms.
        metaData.getString(APPLICATION_ID_METADATA)
            ?: metaData.getInt(APPLICATION_ID_METADATA, 0)
                .takeIf { it != 0 }
                ?.let { context.getString(it) }
    }.onFailure { Log.e(TAG, "Monetization :- could not read $APPLICATION_ID_METADATA", it) }
        .getOrNull()
}
