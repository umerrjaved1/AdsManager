package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.common.AdActivity
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.core.AdMobManager
import com.umer_tf.ads.domain.core.AdSlotState
import com.umer_tf.ads.domain.diagnostics.AdEvent
import com.umer_tf.ads.domain.diagnostics.AdEventLog
import com.umer_tf.ads.domain.diagnostics.AdFormat
import com.umer_tf.ads.domain.utils.AdController

/**
 * 
 * @position Principal Software Engineer - Android
 * @project ${PROJECT_NAME}
 * @date Created on ${DATE} ${TIME}
 * @see "<a href="https://github.com/ProHussain">Github Profile</a>"
 * @see "<a href="https://linkedin.com/in/prohussain/">Linkedin Profile</a>"
 */

/**
 * Main coordinator class for managing App Open Ads in the application.
 * 
 * This class handles the lifecycle coordination between resume ads and start ads,
 * delegating the actual ad management to specialized manager classes. It ensures
 * proper timing for showing ads based on app lifecycle events and user preferences.
 * 
 * @property application The application context for ad operations
 * @property adController Configuration controller for ad behavior and settings
 * @property resumeAdManager Manages resume ads (shown when app returns from background)
 * @property startAdManager Manages start ads (shown when app first launches)
 * @property isShowingAd Indicates if any ad is currently being displayed
 * @property startTime Timestamp when the app was paused, used for resume ad timing
 * @property isPremium Indicates if the user has premium status (ads disabled)
 */
class AppOpenAdLoader(
    private val application: Application,
    private val adController: AdController
) : BaseObserver(application), DefaultLifecycleObserver, IAppOpenAdLoader {

    // Delegate ad management to specialized classes
    private val resumeAdManager = ResumeAdManager(application, adController)
    private val startAdManager = StartAdManager(application, adController)
    
    @JvmField
    var isShowingAd = false
    private var startTime = 0L
    
    @JvmField
    var isPremium = false

    companion object {
        private const val TAG = "AppOpenAdLoader"
    }

    init {
        isPremium = getPremiumPref()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Retrieves the current premium status from AdMobManager.
     * 
     * @return true if user has premium status, false otherwise
     */
    private fun getPremiumPref(): Boolean = AdMobManager.isPremium

    /**
     * Handles app resume events and determines if resume ads should be shown.
     * 
     * This method is called when the app returns to the foreground. It checks
     * various conditions including premium status, ad settings, and timing to
     * decide whether to show a resume ad.
     * 
     * @param owner The lifecycle owner (ProcessLifecycleOwner)
     */
    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        handleAppResume()
    }

    /**
     * Handles app pause events and records the pause timestamp.
     * 
     * This timestamp is used to calculate the duration the app was in background,
     * which determines if a resume ad should be shown when returning to foreground.
     * 
     * @param owner The lifecycle owner (ProcessLifecycleOwner)
     */
    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        handleAppPause()
    }

    /**
     * Loads a resume ad using the ResumeAdManager.
     * 
     * Delegates the ad loading logic to the specialized ResumeAdManager class.
     * This maintains separation of concerns while preserving the public API.
     * 
     * @param context The context for loading the ad
     * @param onSuccessListener Callback to notify when loading completes
     */
    override fun loadResumeAd(context: Context, onSuccessListener: OnSuccessListener<Boolean>?) {
        resumeAdManager.loadAd(context, onSuccessListener)
    }

    /**
     * Loads a start ad using the StartAdManager.
     * 
     * Delegates the ad loading logic to the specialized StartAdManager class.
     * This maintains separation of concerns while preserving the public API.
     * 
     * @param context The context for loading the ad
     * @param onSuccessListener Callback to notify when loading completes
     */
    override fun loadAppOpenAd(context: Context, onSuccessListener: OnSuccessListener<Boolean>?) {
        startAdManager.loadAd(context, onSuccessListener)
    }

    /**
     * Shows a resume ad if available, using the ResumeAdManager.
     * 
     * This method coordinates the showing of resume ads while maintaining the
     * global showing state. It delegates the actual ad display to ResumeAdManager.
     * 
     * @param onShowAdCompleteListener Callback to notify when ad display completes
     */
    override fun showResumeAdIfAvailable(onShowAdCompleteListener: OnSuccessListener<Boolean>) {
        val activity = currentActivity
        if (isShowingAd || activity == null || isOpenAdExcluded(activity)) {
            logShowRefusal(
                AdFormat.APP_OPEN_RESUME,
                adController.appOpenAdResumeId,
                activity,
                resumeAdManager.state,
            )
            onShowAdCompleteListener.onSuccess(false)
            return
        }
        resumeAdManager.showAd(activity, onShowAdCompleteListener) { isShowing ->
            isShowingAd = isShowing
        }
    }

    /**
     * Shows a start ad if available, using the StartAdManager.
     * 
     * This method coordinates the showing of start ads while maintaining the
     * global showing state. It delegates the actual ad display to StartAdManager.
     * 
     * @param onShowAdCompleteListener Callback to notify when ad display completes
     */
    override fun showAppOpenAdIfAvailable(onShowAdCompleteListener: OnSuccessListener<Boolean>) {
        val activity = currentActivity
        if (isShowingAd || activity == null || isOpenAdExcluded(activity)) {
            logShowRefusal(
                AdFormat.APP_OPEN_START,
                adController.appOpenAdStartId,
                activity,
                startAdManager.state,
            )
            onShowAdCompleteListener.onSuccess(false)
            return
        }
        startAdManager.showAd(activity, onShowAdCompleteListener) { isShowing ->
            isShowingAd = isShowing
        }
    }

    /**
     * Records why a show never reached the slot manager. Without this the three refusals that
     * happen before the ad is even consulted - already showing, no usable Activity, excluded
     * screen - were indistinguishable from a plain no-fill in the reports.
     */
    private fun logShowRefusal(
        format: AdFormat,
        adUnitId: String,
        activity: Activity?,
        state: AdSlotState,
    ) {
        val event = if (isShowingAd) {
            AdEvent.SHOW_REJECTED_ALREADY_SHOWING
        } else {
            AdEvent.SHOW_REJECTED_INVALID_ACTIVITY
        }
        val reason = when {
            isShowingAd -> "another app-open ad is showing"
            activity == null -> "no usable foreground Activity"
            else -> "excluded screen ${activity.javaClass.simpleName}"
        }
        AdEventLog.emit(format, adUnitId, event, state, reason)
    }

    /** True when an unexpired resume ad is cached. */
    fun isResumeAdAvailable(): Boolean = resumeAdManager.isAdAvailable()

    /** Current state of the cold-start slot, for diagnostics. */
    fun startAdState(): AdSlotState = startAdManager.state

    /** Current state of the resume slot, for diagnostics. */
    fun resumeAdState(): AdSlotState = resumeAdManager.state

    /**
     * Destroys all ads and resets the loader state.
     * 
     * This method ensures proper cleanup of all ad resources and resets
     * the internal state. It delegates cleanup to individual managers.
     */
    override fun destroyAds() {
        resumeAdManager.destroy()
        startAdManager.destroy()
        isShowingAd = false
        startTime = 0L
        // Remove ProcessLifecycleObserver to prevent memory leaks
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        Log.d(TAG, "Monetization :- OpenAd - destroyAds.")
    }

    override fun isStartAdAvailable(): Boolean {
        return startAdManager.isAdAvailable()
    }


    /**
     * Handles the app resume logic and determines if resume ads should be shown.
     * 
     * This method contains the core logic for deciding when to show resume ads
     * based on timing, user preferences, and app state.
     */
    private fun handleAppResume() {
        Log.d(TAG, "Monetization :- OpenAd -> OnResume")
        if (isPremium || !adController.shouldShowOpenAd || !adController.shouldShowResumeAd) {
            return
        }

        currentActivity?.let { activity ->
            if (shouldShowResumeAd(activity)) {
                handleResumeAdLogic(activity)
            }
        }
    }

    /**
     * Determines if a resume ad should be shown for the given activity.
     * 
     * @param activity The current activity to check
     * @return true if resume ad should be shown, false otherwise
     */
    private fun shouldShowResumeAd(activity: Activity): Boolean {
        return !adController.isSplash && !isOpenAdExcluded(activity)
    }

    /**
     * Returns true when [activity] must not host an app-open ad.
     * Always excludes AdMob's [AdActivity]; also honors host-configured exclusions.
     */
    private fun isOpenAdExcluded(activity: Activity): Boolean {
        if (AdActivity::class.java.isAssignableFrom(activity.javaClass)) {
            return true
        }
        val excluded = adController.openAdExcludedActivities.any {
            it.isAssignableFrom(activity.javaClass)
        }
        if (excluded) {
            Log.d(TAG, "Monetization :- OpenAd - excluded for ${activity.javaClass.simpleName}")
        }
        return excluded
    }

    /**
     * Handles the core logic for resume ad timing and display.
     * 
     * This method calculates the time difference since the app was paused and
     * determines if enough time has passed to show a resume ad.
     * 
     * @param activity The current activity context
     */
    private fun handleResumeAdLogic(activity: Activity) {
        val currentTime = System.currentTimeMillis()
        if (startTime > 0) {
            val timeDiff = (currentTime - startTime) / 1000
            val remoteTimer = adController.openAdResumeTime
            
            logResumeTiming(startTime, currentTime, timeDiff, remoteTimer)
            
            if (timeDiff >= remoteTimer) {
                if (resumeAdManager.isAdAvailable()) {
                    showResumeAdIfAvailable { }
                } else {
                    // Request only when this resume is eligible to show. Prefetching here
                    // (or after a show) produced matches that the next resume never displayed.
                    loadResumeAd(activity) { loaded ->
                        if (loaded == true) showResumeAdIfAvailable { }
                    }
                }
            }
        }
    }

    /**
     * Handles the app pause event and records the pause timestamp.
     * 
     * This timestamp is used to calculate the background duration for resume ad logic.
     */
    private fun handleAppPause() {
        Log.d(TAG, "Monetization :- OpenAd -TimeManager Resume App paused at ${System.currentTimeMillis()}")
        startTime = System.currentTimeMillis()
    }

    /**
     * Logs the resume ad timing information for debugging purposes.
     * 
     * @param startTime The timestamp when the app was paused
     * @param currentTime The current timestamp when resuming
     * @param diff The calculated time difference in seconds
     * @param remoteTimer The configured remote timer value
     */
    private fun logResumeTiming(startTime: Long, currentTime: Long, diff: Long, remoteTimer: Long) {
        Log.d(TAG, "Monetization :- OpenAd - TimeManager Resume startTime sec: $startTime")
        Log.d(TAG, "Monetization :- OpenAd - TimeManager Resume backTime sec: $currentTime")
        Log.d(TAG, "Monetization :- OpenAd - TimeManager Resume backTime diff: $diff")
        Log.d(TAG, "Monetization :- OpenAd - TimeManager Resume Remote diff: $remoteTimer")
    }
}