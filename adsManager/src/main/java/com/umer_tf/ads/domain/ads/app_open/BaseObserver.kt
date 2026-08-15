package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.umer_tf.ads.domain.core.ForegroundActivityTracker

/**
 * Activity awareness for the app-open loader.
 *
 * This used to keep its own `currentActivity` field. It no longer does: tracking lives in
 * [ForegroundActivityTracker] so the SDK and every host app read the same reference. The
 * `ActivityLifecycleCallbacks` implementation is kept (empty) because it is part of the published
 * type and subclasses may rely on it.
 */
open class BaseObserver(application: Application) : Application.ActivityLifecycleCallbacks {

    init {
        ForegroundActivityTracker.install(application)
    }

    /** The Activity an app-open ad may be shown on right now, or null. */
    protected val currentActivity: Activity?
        get() = ForegroundActivityTracker.showableActivity()

    /** True while the process has at least one started Activity. */
    protected val isForeground: Boolean
        get() = ForegroundActivityTracker.isForeground()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
