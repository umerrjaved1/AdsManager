/**
 * 
 * @position Principal Software Engineer - Android
 * @project ${PROJECT_NAME}
 * @date Created on ${DATE} ${TIME}
 * @see "<a href="https://github.com/ProHussain">Github Profile</a>"
 * @see "<a href="https://linkedin.com/in/prohussain/">Linkedin Profile</a>"
 */
package com.umer_tf.ads.domain.ads.app_open

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_DISMISSED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_SHOWN
import com.umer_tf.ads.domain.utils.AnalyticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages the lifecycle and display of start ads (ads shown when app first launches).
 * 
 * This class handles all aspects of start ad management including loading, displaying,
 * callback handling, and cleanup. It operates independently from resume ads to prevent
 * conflicts and ensure proper state management.
 * 
 * @property application The application context for analytics and ad operations
 * @property adController Configuration controller for ad behavior and settings
 * @property appOpenAd The currently loaded start ad instance
 * @property isLoadingAd Indicates if an ad is currently being loaded
 * @property loadTime Timestamp when the ad was loaded, used for expiration checking
 */
internal class StartAdManager(
    private val application: Application,
    private val adController: AdController
) {
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var loadTime: Long = 0

    companion object {
        private const val TAG = "StartAdManager"
        private const val AD_EXPIRATION_TIME_MS = 4 * 60 * 60 * 1000L // 4 hours
    }

    /**
     * Loads a start ad if one is not already available or loading.
     * 
     * This method checks the current state and initiates ad loading if appropriate.
     * It prevents duplicate loading requests and ensures proper state management.
     * 
     * @param context The context for loading the ad
     * @param onSuccessListener Callback to notify when loading completes
     */
    fun loadAd(context: Context, onSuccessListener: OnSuccessListener<Boolean>?) {
        if (isAdAvailable() || isLoadingAd) {
            onSuccessListener?.onSuccess(false)
            return
        }

        isLoadingAd = true
        val request = AdRequest.Builder().build()

        AppOpenAd.load(
            context,
            adController.appOpenAdStartId,
            request,
            createLoadCallback(onSuccessListener)
        )
    }

    /**
     * Shows the start ad if available and sets up all necessary callbacks.
     * 
     * This method handles the complete ad display process including callback setup,
     * state management, and actual ad showing. It ensures proper cleanup after display.
     * 
     * @param activity The activity to show the ad on
     * @param onShowAdCompleteListener Callback to notify when ad display completes
     * @param onStateChange Callback to update the global showing state
     */
    fun showAd(
        activity: Activity?,
        onShowAdCompleteListener: OnSuccessListener<Boolean>,
        onStateChange: (Boolean) -> Unit
    ) {
        if (activity == null || !isAdAvailable()) {
            onShowAdCompleteListener.onSuccess(false)
            return
        }

        setupAdCallbacks(onShowAdCompleteListener, onStateChange)
        onStateChange(true)
        appOpenAd?.show(activity)
    }

    /**
     * Checks if a start ad is available and not expired.
     * 
     * @return true if ad is available and valid, false otherwise
     */
    fun isAdAvailable(): Boolean = appOpenAd != null && !isAdExpired()

    /**
     * Destroys the current start ad and resets the manager state.
     * 
     * This method ensures proper cleanup of ad resources and resets all internal
     * state variables to their initial values.
     */
    fun destroy() {
        appOpenAd = null
        isLoadingAd = false
        loadTime = 0
    }

    /**
     * Checks if the currently loaded ad has expired.
     * 
     * Ads expire after 4 hours to ensure they remain relevant and effective.
     * 
     * @return true if ad has expired, false otherwise
     */
    private fun isAdExpired(): Boolean =
        System.currentTimeMillis() - loadTime > AD_EXPIRATION_TIME_MS

    /**
     * Creates the load callback for ad loading operations.
     * 
     * This callback handles both successful ad loading and loading failures,
     * updating the internal state accordingly.
     * 
     * @param onSuccessListener Callback to notify when loading completes
     * @return Configured AppOpenAdLoadCallback instance
     */
    private fun createLoadCallback(onSuccessListener: OnSuccessListener<Boolean>?) =
        object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                appOpenAd = ad
                isLoadingAd = false
                loadTime = System.currentTimeMillis()
                onSuccessListener?.onSuccess(true)
                Log.d(TAG, "Monetization :- OpenAd Start - onAdLoaded.")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                isLoadingAd = false
                onSuccessListener?.onSuccess(false)
                Log.d(
                    TAG,
                    "Monetization :- OpenAd Start - onAdFailedToLoad: ${loadAdError.message}"
                )
            }
        }

    /**
     * Sets up all necessary callbacks for the start ad.
     * 
     * This method configures the full screen content callback and paid event listener
     * to handle all ad lifecycle events including display, dismissal, and revenue tracking.
     * 
     * @param onShowAdCompleteListener Callback to notify when ad display completes
     * @param onStateChange Callback to update the global showing state
     */
    private fun setupAdCallbacks(
        onShowAdCompleteListener: OnSuccessListener<Boolean>,
        onStateChange: (Boolean) -> Unit
    ) {
        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                onStateChange(false)
                Log.d(TAG, "Monetization :- OpenAd Start - onAdDismissedFullScreenContent.")
                AnalyticsManager.getInstance(application)
                    .sendAnalytics(AD_DISMISSED, "OpenAd_Start")
                onShowAdCompleteListener.onSuccess(true)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appOpenAd = null
                onStateChange(false)
                Log.d(
                    TAG,
                    "Monetization :- OpenAd Start - onAdFailedToShowFullScreenContent: ${adError.message}"
                )
                onShowAdCompleteListener.onSuccess(false)
            }

            override fun onAdShowedFullScreenContent() {
                AnalyticsManager.getInstance(application).sendAnalytics(AD_SHOWN, "OpenAd_Start")
                Log.d(TAG, "Monetization :- OpenAd Start - onAdShowedFullScreenContent.")
            }
        }

        appOpenAd?.setOnPaidEventListener { adValue ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AdsAnalytics.logAppsFlyerRevenue(
                        appOpenAd?.adUnitId ?: "",
                        "OpenAd_Start",
                        adValue,
                        application
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to log start ad revenue", e)
                }
            }
        }
    }
}