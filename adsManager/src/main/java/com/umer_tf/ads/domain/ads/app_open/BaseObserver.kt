package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

open class BaseObserver(application: Application) : Application.ActivityLifecycleCallbacks {

    private val TAG = "BaseObserver"
    
    init {
        // Register lifecycle callbacks safely after object construction
        registerActivityLifecycleCallbacks(application)
    }

    protected var currentActivity: Activity? = null

    private fun registerActivityLifecycleCallbacks(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
        Log.d(TAG, "Monetization :- onActivityStarted: $activity")
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        Log.d(TAG, "Monetization :- onActivityResumed: $activity")
    }

    override fun onActivityPaused(p0: Activity) {
    }

    override fun onActivityStopped(p0: Activity) {
    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
        currentActivity = null
        Log.d(TAG, "Monetization :- onActivityDestroyed: $activity")
    }

}