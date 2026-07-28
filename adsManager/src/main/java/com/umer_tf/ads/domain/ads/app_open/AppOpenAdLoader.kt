package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdActivity
import com.umer_tf.ads.domain.core.AdMobManager
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AdsLog

class AppOpenAdLoader(
    private val application: Application,
    private val adController: AdController
) : BaseObserver(application), DefaultLifecycleObserver, IAppOpenAdLoader {

    private val resumeAdManager = ResumeAdManager(application, adController)
    private val startAdManager = StartAdManager(application, adController)
    
    @JvmField
    var isShowingAd = false
    private var startTime = 0L
    
    /**
     * Premium status, read live rather than snapshotted.
     *
     * This used to be copied from [AdMobManager.isPremium] in `init` - which runs when the manager is
     * constructed, before the app has had a chance to set it - so a paying user could still be shown
     * app open ads for the whole process lifetime.
     */
    val isPremium: Boolean get() = AdMobManager.isPremium

    companion object {
        private const val TAG = "AdsManager_AppOpen"
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        handleAppResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        handleAppPause()
    }

    override fun loadResumeAd(context: Context, onAdLoaded: ((Boolean) -> Unit)?) {
        resumeAdManager.loadAd(context, onAdLoaded)
    }

    override fun loadAppOpenAd(context: Context, onAdLoaded: ((Boolean) -> Unit)?) {
        startAdManager.loadAd(context, onAdLoaded)
    }

    override fun showResumeAdIfAvailable(onShowAdCompleteListener: ((Boolean) -> Unit)?) {
        if (isShowingAd) {
            onShowAdCompleteListener?.invoke(false)
            return
        }
        resumeAdManager.showAd(currentActivity, onShowAdCompleteListener) { isShowing ->
            isShowingAd = isShowing
        }
    }

    override fun showAppOpenAdIfAvailable(onShowAdCompleteListener: ((Boolean) -> Unit)?) {
        if (isShowingAd) {
            onShowAdCompleteListener?.invoke(false)
            return
        }
        startAdManager.showAd(currentActivity, onShowAdCompleteListener) { isShowing ->
            isShowingAd = isShowing
        }
    }

    override fun destroyAds() {
        resumeAdManager.destroy()
        startAdManager.destroy()
        isShowingAd = false
        startTime = 0L
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        AdsLog.d(TAG, "AppOpenAdLoader: destroyAds called")
    }

    override fun isStartAdAvailable(): Boolean {
        return startAdManager.isAdAvailable()
    }

    private fun handleAppResume() {
        AdsLog.d(TAG, "AppOpenAdLoader: handleAppResume called")
        if (isPremium || !adController.shouldShowOpenAd || !adController.shouldShowResumeAd) {
            return
        }

        currentActivity?.let { activity ->
            if (shouldShowResumeAd(activity)) {
                handleResumeAdLogic(activity)
            }
        }
    }

    private fun shouldShowResumeAd(activity: Activity): Boolean {
        return activity.javaClass.simpleName != AdActivity::class.java.simpleName && !adController.isSplash
    }

    private fun handleResumeAdLogic(activity: Activity) {
        val currentTime = System.currentTimeMillis()
        if (startTime > 0) {
            val timeDiff = (currentTime - startTime) / 1000
            val remoteTimer = adController.openAdResumeTime
            
            logResumeTiming(startTime, currentTime, timeDiff, remoteTimer)
            
            if (timeDiff >= remoteTimer) {
                showResumeAdIfAvailable(null)
            } else {
                startTime = 0
                if (!resumeAdManager.isAdAvailable()) {
                    loadResumeAd(activity, null)
                }
            }
        }
    }

    private fun handleAppPause() {
        AdsLog.d(TAG, "AppOpenAdLoader: handleAppPause at ${System.currentTimeMillis()}")
        startTime = System.currentTimeMillis()
    }

    private fun logResumeTiming(startTime: Long, currentTime: Long, diff: Long, remoteTimer: Long) {
        AdsLog.d(TAG, "AppOpenAdLoader: Resume timing startTime=$startTime, currentTime=$currentTime, diff=$diff, remoteTimer=$remoteTimer")
    }
}