package com.umer_tf.ads.domain.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference

/**
 * The single source of truth for "which Activity is on top right now".
 *
 * Every host app kept its own copy of this and two of the three got it wrong in the same way:
 * they cleared the reference in `onActivityStopped`, then read it from a
 * `ProcessLifecycleOwner` `ON_START` observer. That read always saw `null`, because
 * `ProcessLifecycleInitializer` registers its callbacks from a ContentProvider - before
 * `Application.onCreate()` - so `ON_START` is dispatched *ahead of* any callback the app
 * registers later. The resume app-open ad returned early on every single resume.
 *
 * The rule that fixes it: **a reference is released when the Activity is destroyed, never when
 * it stops.** A stopped Activity is still the right one to come back to; a destroyed one is not.
 */
object ForegroundActivityTracker : Application.ActivityLifecycleCallbacks {

    private const val TAG = "ForegroundActivity"

    private var installed = false

    /** Most recently started Activity. Survives `onStop`; released on `onDestroy`. */
    @Volatile
    private var currentRef: WeakReference<Activity>? = null

    /** Currently RESUMED Activity, or null while nothing is resumed. */
    @Volatile
    private var resumedRef: WeakReference<Activity>? = null

    /** Number of started Activities. > 0 means the process is foreground. */
    @Volatile
    private var startedCount = 0

    /**
     * Registers the tracker. Idempotent - calling it from several places is safe and only the
     * first call registers callbacks.
     */
    @JvmStatic
    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
        Log.d(TAG, "Monetization :- ForegroundActivityTracker installed")
    }

    /** Most recent Activity that is still usable, or null. */
    @JvmStatic
    fun current(): Activity? = currentRef?.get()?.takeIf { it.isUsable() }

    /** The RESUMED Activity, or null when nothing is resumed (app backgrounding / in transition). */
    @JvmStatic
    fun resumed(): Activity? = resumedRef?.get()?.takeIf { it.isUsable() }

    /** True while at least one Activity is started. */
    @JvmStatic
    fun isForeground(): Boolean = startedCount > 0

    /**
     * The Activity a full-screen ad may be shown on, or null.
     *
     * Deliberately requires STARTED rather than RESUMED: an app-open ad on return from background
     * is shown from the `ON_START` dispatch, at which point the Activity is started but not yet
     * resumed. Demanding RESUMED here would reintroduce the very miss this class exists to fix.
     */
    @JvmStatic
    fun showableActivity(): Activity? {
        if (!isForeground()) return null
        return resumed() ?: current()
    }

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        currentRef = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        currentRef = WeakReference(activity)
        resumedRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedRef?.get() === activity) resumedRef = null
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedCount > 0) startedCount--
        // Intentionally does NOT clear currentRef. See the class doc.
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (currentRef?.get() === activity) currentRef = null
        if (resumedRef?.get() === activity) resumedRef = null
    }
}
