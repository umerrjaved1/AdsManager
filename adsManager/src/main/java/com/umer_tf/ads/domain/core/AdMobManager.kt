package com.umer_tf.ads.domain.core

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.umer_tf.ads.domain.ads.app_open.AppOpenAdLoader
import com.umer_tf.ads.domain.ads.banner.BannerAdLoader
import com.umer_tf.ads.domain.ads.interstitial.InterstitialAdLoader
import com.umer_tf.ads.domain.ads.native_ad.NativeAd
import com.umer_tf.ads.domain.ads.rewarded.RewardedAdLoader
import com.umer_tf.ads.domain.analytics.AdEventListener
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.consent.AdsConsentGate
import com.umer_tf.ads.domain.consent.AdsConsentManager
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AdsEnvironment
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

    /**
     * UMP consent, created on first use because it needs no `Activity` to construct but is useless
     * to a host that never runs a consent flow.
     */
    val consentManager: AdsConsentManager by lazy { AdsConsentManager(application) }

    private var isInitialized = false

    /** Overrides the app id read from the host manifest. Must be set before [initialize]. */
    private var applicationIdOverride: String? = null

    /** Audience tagging, content rating and test devices. Folded into [initialize]'s config. */
    private var requestConfig: AdsRequestConfig? = null

    /**
     * Registers the app's single observer for every ad event, for every format.
     *
     * Pass null to clear. See [AdEventListener] for what each callback means and, in particular,
     * which one counts as an impression.
     */
    fun setAdEventListener(listener: AdEventListener?): AdMobManager {
        AdEvents.setListener(listener)
        return this
    }

    /**
     * Sets global request configuration: COPPA / under-age tagging, max content rating and test
     * devices.
     *
     * Only has an effect before [initialize]. The Next-Gen SDK has no
     * `MobileAds.setRequestConfiguration()` - the configuration is part of `InitializationConfig`,
     * so it can only be supplied while the SDK is starting.
     */
    fun setRequestConfig(config: AdsRequestConfig?): AdMobManager {
        if (isInitialized) {
            Log.e(
                TAG,
                "Monetization:- setRequestConfig() after initialize() is ignored: the Next-Gen SDK " +
                    "takes RequestConfiguration as part of InitializationConfig."
            )
            return this
        }
        requestConfig = config
        return this
    }

    /**
     * Runs the UMP consent flow and switches the process-wide [AdsConsentGate] on.
     *
     * Until this is called the gate stays unenforced, because `canRequestAds()` is false before
     * UMP's update completes and gating on it unconditionally would mean zero ads for a host with
     * no consent flow at all.
     *
     * @param onConsentGathered Invoked once the flow finishes, with whether ads may now be
     *   requested. Ad requests made before then are allowed through unenforced.
     */
    @JvmOverloads
    @Suppress("DEPRECATION")
    fun gatherConsent(
        activity: Activity,
        isTest: Boolean = false,
        onConsentGathered: ((Boolean) -> Unit)? = null
    ) {
        // The deprecated one-shot form on purpose: it does the info update and the form in a single
        // call with a single completion, which is what a gate needs. The preLoad/show pair has no
        // way to report "the update finished" to a caller that must then flip the gate.
        consentManager.showGDPRConsent(activity, isTest) { formError ->
            if (formError != null) {
                Log.e(TAG, "Monetization:- consent form error: ${formError.message}")
            }
            val canRequestAds = consentManager.canRequestAds
            AdsConsentGate.update(canRequestAds)
            onConsentGathered?.invoke(canRequestAds)
        }
    }

    /**
     * Sets the AdMob application id explicitly, for hosts that resolve it at runtime (remote config,
     * a per-flavour value) rather than declaring it in the manifest.
     *
     * Only has an effect before [initialize]. When unset, the id is read from the host's
     * `com.google.android.gms.ads.APPLICATION_ID` meta-data, which is where it already lives for
     * every existing consumer of this library.
     */
    fun setApplicationId(applicationId: String): AdMobManager {
        applicationIdOverride = applicationId
        return this
    }

    /**
     * Initializes the Google Mobile Ads SDK.
     *
     * **This must now be called**, and it must complete before any ad is requested. The Next-Gen SDK
     * has no implicit startup: it takes the AdMob application id as an explicit
     * [InitializationConfig] argument, so nothing can start it on the library's behalf the way the
     * legacy SDK did from the manifest meta-data alone. A request made before this finishes fails.
     *
     * The id is read from the host manifest's `com.google.android.gms.ads.APPLICATION_ID` meta-data
     * (still required, and still what the User Messaging Platform SDK reads), unless
     * [setApplicationId] supplied one.
     *
     * @param onInitializationComplete Invoked on the main thread once initialization has finished.
     *   Not called when no application id could be resolved - there is nothing to wait for in that
     *   case, and every request will fail.
     */
    fun initialize(onInitializationComplete: () -> Unit) {
        TimeManager.getInstance().start()
        if (isInitialized || MobileAds.isInitialized) {
            isInitialized = true
            onInitializationComplete()
            return
        }

        val applicationId = applicationIdOverride ?: readApplicationIdFromManifest()
        if (applicationId.isNullOrBlank()) {
            // Initializing with a blank id fails inside the SDK and surfaces later as an unexplained
            // no-fill on every unit, so name the actual cause once, here.
            Log.e(
                TAG,
                "Monetization:- AdMob NOT initialized: no application id. Declare " +
                    "<meta-data android:name=\"com.google.android.gms.ads.APPLICATION_ID\" " +
                    "android:value=\"ca-app-pub-...\"/> in the app manifest, or call " +
                    "AdMobManager.setApplicationId() before initialize()."
            )
            return
        }

        // Still off the main thread: initialize() does disk and network work before returning.
        CoroutineScope(Dispatchers.IO).launch {
            val config = InitializationConfig.Builder(applicationId)
                .apply { requestConfig?.let { setRequestConfiguration(it.toRequestConfiguration()) } }
                .build()
            MobileAds.initialize(application, config) { initializationStatus ->
                Log.d(TAG, "Monetization:- AdMob initialized: $initializationStatus")
                isInitialized = true
                // The Next-Gen SDK calls back on a background thread, so the hop to Main is what
                // makes it safe for hosts to start loading ads (and touching views) from here.
                CoroutineScope(Dispatchers.Main).launch {
                    onInitializationComplete()
                }
            }
        }
    }

    /**
     * Reads the AdMob app id the host declared in its manifest.
     *
     * The legacy SDK picked this up by itself; the Next-Gen SDK does not, but the tag is still
     * required for UMP, so reading it keeps every existing consumer working without a code change.
     */
    private fun readApplicationIdFromManifest(): String? = runCatching {
        application.packageManager
            .getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString(MANIFEST_APPLICATION_ID)
    }.onFailure {
        Log.e(TAG, "Monetization:- failed to read $MANIFEST_APPLICATION_ID from the manifest", it)
    }.getOrNull()

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
     * Subclasses of each listed class are also excluded. AdMob's own [com.google.android.libraries.ads.mobile.sdk.common.AdActivity]
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

        /**
         * Manifest meta-data holding the AdMob app id. Deliberately still the legacy key: the tag is
         * unchanged by the migration, and the UMP SDK reads the same one.
         */
        private const val MANIFEST_APPLICATION_ID = "com.google.android.gms.ads.APPLICATION_ID"
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
            // Must run before anything else touches the library: AdsLog.isEnabled and
            // AdUnitIdValidator.strictMode both default off it, and a library cannot read its own
            // BuildConfig.DEBUG to find out (a published AAR is always release).
            AdsEnvironment.detectFrom(application)

            return instance ?: synchronized(this) {
                instance ?: AdMobManager(application).also { instance = it }
            }
        }

        /**
         * Logs, once, that the SDK was never started.
         *
         * Requests made before [initialize] completes fail inside AdMob and are reported as
         * "Network error", which sends people looking at connectivity. `Utilities.shouldShowAd`
         * calls this so the real cause is named exactly once.
         */
        @JvmStatic
        fun warnIfNotInitialized() {
            if (instance?.isInitialized == true || MobileAds.isInitialized) return
            if (!warnedNotInitialized) {
                warnedNotInitialized = true
                Log.e(
                    TAG,
                    "Monetization:- ad requested before AdMobManager.initialize() completed. Every " +
                        "request will fail, usually reported as \"Network error\"."
                )
            }
        }

        @Volatile
        private var warnedNotInitialized = false
    }
}