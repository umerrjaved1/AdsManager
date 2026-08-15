package com.umer_tf.ads.domain.core

import android.app.Activity
import android.app.Application
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.umer_tf.ads.domain.ads.app_open.AppOpenAdLoader
import com.umer_tf.ads.domain.ads.banner.BannerAdLoader
import com.umer_tf.ads.domain.ads.interstitial.InterstitialAdLoader
import com.umer_tf.ads.domain.ads.native_ad.NativeAd
import com.umer_tf.ads.domain.ads.rewarded.RewardedAdLoader
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.TimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages AdMob ad loaders and initialization.
 *
 * @property application The application context.
 */
open class AdMobManager(
    private val application: Application,
) {
    private val adController = AdController()

    init {
        // Registered here rather than lazily with the app-open loader so the reference is valid
        // even for hosts that never touch app-open ads.
        ForegroundActivityTracker.install(application)
    }

    @JvmField
    val appOpenAdLoader = AppOpenAdLoader(application, adController)

    @JvmField
    val bannerAdLoader = BannerAdLoader()

    @JvmField
    val interstitialAdLoader = InterstitialAdLoader(application, adController)

    @JvmField
    val nativeAdLoader = NativeAd(application)

    @JvmField
    val rewardedAdLoader = RewardedAdLoader(application, adController)

    private var isInitialized = false

    /**
     * Initializes the AdMob SDK.
     *
     * @deprecated This method is obsolete and should not be called. AdMob SDK initialization is now
     * handled automatically by the system or through alternative mechanisms. Use of this method is
     * unnecessary and may lead to redundant initialization.
     *
     * @param onInitializationComplete Callback to be invoked when initialization is complete.
     * This callback is no longer required as the method is obsolete.
     */
    @Deprecated("This method is obsolete. AdMob SDK initialization is handled automatically.")
    fun initialize(onInitializationComplete: () -> Unit) {
        TimeManager.getInstance().start()
        if (!isInitialized) {
            CoroutineScope(Dispatchers.IO).launch {
                MobileAds.initialize(application) { initializationStatus ->
                    // Log or handle initialization status if needed
                    Log.d(TAG, "Monetization:- AdMob intialized: $initializationStatus")
                    isInitialized = true
                    CoroutineScope(Dispatchers.Main).launch {
                        onInitializationComplete()
                    }
                }
            }
        }
        else{
            onInitializationComplete()
        }
    }

    /**
     * Sets the App Open Ad Resume ID.
     *
     * @param appOpenAdResumeId The ad unit ID for the app open ad resume.
     * @return The current instance of AdMobManager.
     */
    fun setAppOpenAdResumeId( @ValidateAdUnitId appOpenAdResumeId: String): AdMobManager {
        AdUnitIdValidator.validateAdUnitId(appOpenAdResumeId)
        adController.appOpenAdResumeId = appOpenAdResumeId
        return this
    }

    /**
     * Sets the App Open Ad Start ID.
     *
     * @param appOpenAdStartId The ad unit ID for the app open ad start.
     * @return The current instance of AdMobManager.
     */
    fun setAppOpenAdStartId( @ValidateAdUnitId appOpenAdStartId: String): AdMobManager {
        AdUnitIdValidator.validateAdUnitId(appOpenAdStartId)
        adController.appOpenAdStartId = appOpenAdStartId
        return this
    }

    /**
     * Sets the maximum time for interstitial ads.
     *
     * @param interstitialAdMaxTime The maximum time in seconds.
     * @return The current instance of AdMobManager.
     */
    fun setInterstitialAdMaxTime(interstitialAdMaxTime: Long): AdMobManager {
        adController.interstitialAdMaxTime = interstitialAdMaxTime
        return this
    }

    /**
     * Sets the minimum time for interstitial ads.
     *
     * @param interstitialAdMinTime The minimum time in seconds.
     * @return The current instance of AdMobManager.
     */
    fun setInterstitialAdMinTime(interstitialAdMinTime: Long): AdMobManager {
        adController.interstitialAdMinTime = interstitialAdMinTime
        return this
    }

    /**
     * Sets the interstitial ad counter.
     *
     * @param interstitialCounter The counter value to show ad after this value.
     * @return The current instance of AdMobManager.
     */
    fun setInterstitialCounter(interstitialCounter: Int): AdMobManager {
        adController.interstitialCounter = interstitialCounter
        return this
    }

    /**
     * Sets the resume time for open ads.
     *
     * @param openAdResumeTime The resume time in seconds to show Open Ad after application has been paused.
     * @return The current instance of AdMobManager.
     */
    fun setOpenAdResumeTime(openAdResumeTime: Long): AdMobManager {
        adController.openAdResumeTime = openAdResumeTime
        return this
    }

    /**
     * Sets the status to show or hide resume ad.
     *
     * @param shouldShowResumeAd The status to show or hide resume ad.
     * @return The current instance of AdMobManager.
     */

    fun setShouldShowResumeAd(shouldShowResumeAd: Boolean): AdMobManager {
        adController.shouldShowResumeAd = shouldShowResumeAd
        return this
    }

    /**
     * Sets the premium status.
     *
     * @param isPremium The premium status.
     * @return The current instance of AdMobManager.
     */
    fun setPremium(isPremium: Boolean): AdMobManager {
        AdMobManager.isPremium = isPremium
        return this
    }

    /**
     * Sets the splash status to only show App open ad at start.
     * Set this to false when splash screen is destroyed to show the Open ad at resume.
     *
     * @param isSplash The splash status.
     * @return The current instance of AdMobManager.
     */
    fun setSplash(isSplash: Boolean): AdMobManager {
        adController.isSplash = isSplash
        return this
    }

    /**
     * Replaces the list of activities where app-open ads must not be shown.
     * Subclasses of each listed class are also excluded. AdMob's own [com.google.android.gms.ads.AdActivity]
     * is always excluded regardless of this list.
     *
     * @param activities Activity classes to exclude.
     * @return The current instance of AdMobManager.
     */
    fun setOpenAdExcludedActivities(vararg activities: Class<out Activity>): AdMobManager {
        adController.openAdExcludedActivities.clear()
        adController.openAdExcludedActivities.addAll(activities)
        return this
    }

    /**
     * Adds a single activity class to the app-open exclusion list.
     *
     * @param activity Activity class (and its subclasses) to exclude.
     * @return The current instance of AdMobManager.
     */
    fun addOpenAdExcludedActivity(activity: Class<out Activity>): AdMobManager {
        adController.openAdExcludedActivities.add(activity)
        return this
    }

    /**
     * Clears all host-configured app-open activity exclusions.
     *
     * @return The current instance of AdMobManager.
     */
    fun clearOpenAdExcludedActivities(): AdMobManager {
        adController.openAdExcludedActivities.clear()
        return this
    }

    /**
     * The Activity a full-screen ad may be shown on right now, or null when the app has no usable
     * foreground Activity.
     *
     * Hosts should use this instead of keeping their own `currentActivity` field. A host-side
     * tracker that clears on `onActivityStopped` reads `null` from a `ProcessLifecycleOwner`
     * `ON_START` observer, because that observer runs before the host's own lifecycle callbacks -
     * which is how resume app-open ads ended up never being shown.
     */
    fun foregroundActivity(): Activity? = ForegroundActivityTracker.showableActivity()

    /** True while the process has at least one started Activity. */
    fun isAppInForeground(): Boolean = ForegroundActivityTracker.isForeground()

    /** True while any full-screen ad (interstitial, app-open, rewarded) owns the screen. */
    fun isFullScreenAdShowing(): Boolean = FullScreenGate.isShowing()

    companion object {
        private const val TAG = "AdMobManager"
        @Volatile
        private var instance: AdMobManager? = null

        @JvmStatic
        var isPremium: Boolean = false

        /**
         * Gets the singleton instance of AdMobManager.
         *
         * @param application The application context.
         * @return The singleton instance of AdMobManager.
         */
        @JvmStatic
        fun getInstance(application: Application): AdMobManager {

            return instance ?: synchronized(this) {
                instance ?: AdMobManager(application).also { instance = it }
            }
        }
    }
}