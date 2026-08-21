package com.umer_tf.ads.domain.core

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.annotation.LayoutRes
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
import com.umer_tf.ads.domain.utils.AdLoadingDialogConfig
import com.umer_tf.ads.domain.utils.AdsEnvironment
import com.umer_tf.ads.domain.utils.AdsLog
import com.umer_tf.ads.domain.utils.LoadingDialogUtil
import com.umer_tf.ads.domain.utils.TimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
            // Legacy AdMob let this be applied at any time; Next-Gen does not, so a late call is a
            // silent no-op unless it is named. Nothing can be done to honour it after startup.
            AdsLog.e(
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
     * Sets the loading dialog shown by interstitial and rewarded ads while an ad is being fetched.
     *
     * Covers everything from "same layout, different wording" to supplying your own
     * [android.app.Dialog]:
     *
     * ```kotlin
     * AdMobManager.getInstance(app)
     *     .setLoadingDialog(AdLoadingDialogConfig(message = "Almost there…"))
     *
     * // or take over entirely
     * AdMobManager.getInstance(app)
     *     .setLoadingDialog(AdLoadingDialogConfig(dialogProvider = { MyBrandedLoader(it) }))
     * ```
     *
     * Takes effect for every dialog opened after this call - unlike [setRequestConfig], it is not
     * tied to initialization, so a host can change it per screen.
     *
     * @see AdLoadingDialogConfig
     */
    fun setLoadingDialog(config: AdLoadingDialogConfig): AdMobManager {
        LoadingDialogUtil.globalConfig = config
        return this
    }

    /**
     * Replaces only the loading dialog's layout, keeping every other setting.
     *
     * The shorthand for the common "my own layout, nothing else changes" case. Equivalent to
     * `setLoadingDialog(currentConfig.copy(layoutResId = layoutResId))`, so it composes with an
     * earlier [setLoadingDialog] instead of discarding it.
     *
     * A custom layout that wants the library to write [AdLoadingDialogConfig.message] into it must
     * either use `@id/tvLoading` for that `TextView` or name its own via
     * [AdLoadingDialogConfig.messageViewId].
     */
    fun setLoadingDialogLayout(@LayoutRes layoutResId: Int): AdMobManager {
        LoadingDialogUtil.setGlobalLoadingLayoutResId(layoutResId)
        return this
    }

    /**
     * Sets how long the loading dialog stays visible after an interstitial loads, before the ad is
     * shown. Defaults to 1000 ms.
     *
     * The delay exists so a fill that arrives instantly does not flash the dialog and immediately
     * cover it with a full-screen ad. It only postpones the impression - the ad is already in the
     * loader's slot, so nothing is lost if the Activity dies inside the window.
     *
     * @param delayMs The delay in milliseconds.
     */
    fun setInterstitialDialogDelay(delayMs: Long): AdMobManager {
        interstitialAdLoader.adShowDelay = delayMs
        return this
    }

    /**
     * Gathers user consent, then initializes the AdMob SDK - the correct order for EEA traffic.
     *
     * From the moment this is called, [com.umer_tf.ads.domain.utils.Utilities.shouldShowAd] reports
     * false and every loader short-circuits, so no request can escape ahead of the user's decision.
     * Call it from your launcher/splash Activity:
     *
     * ```kotlin
     * AdMobManager.getInstance(application).gatherConsent(this) { canRequestAds ->
     *     if (canRequestAds) startLoadingAds()
     * }
     * ```
     *
     * Safe to call on every launch: UMP only shows a form when one is actually required. A host that
     * never calls it keeps the previous behaviour - [AdsConsentGate] stays unenforced - but must then
     * call [initialize] itself.
     *
     * @param activity Activity used to host the consent form.
     * @param isTest Forces the EEA debug geography so the form can be exercised outside Europe.
     * @param onConsentComplete Invoked with `canRequestAds` once consent settles and the SDK is up.
     */
    @JvmOverloads
    @Suppress("DEPRECATION")
    fun gatherConsent(
        activity: Activity,
        isTest: Boolean = false,
        onConsentComplete: ((Boolean) -> Unit)? = null
    ) {
        // Enforce before the flow starts, not after it settles. Otherwise every request made while
        // the UMP round trip is in flight goes out unenforced - which for EEA traffic is exactly the
        // window the consent flow exists to close.
        AdsConsentGate.update(false)

        // The deprecated one-shot form on purpose: it does the info update and the form in a single
        // call with a single completion, which is what a gate needs. The preLoad/show pair has no
        // way to report "the update finished" to a caller that must then flip the gate.
        consentManager.showGDPRConsent(activity, isTest) { formError ->
            if (formError != null) {
                AdsLog.e(TAG, "Monetization:- consent form error: ${formError.message}")
            }
            val canRequestAds = consentManager.canRequestAds
            AdsConsentGate.update(canRequestAds)
            AdsLog.d(TAG, "gatherConsent settled: canRequestAds=$canRequestAds")
            // Consent first, SDK second. Initializing earlier would let the SDK's own startup
            // traffic precede the user's answer.
            initialize { onConsentComplete?.invoke(canRequestAds) }
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
    @JvmOverloads
    fun initialize(onInitializationComplete: (() -> Unit)? = null) {
        // Set synchronously, before the async SDK start, purely so warnIfNotInitialized can tell
        // "you forgot to call this" apart from "it is still starting up".
        initializeCalled = true
        TimeManager.getInstance().start()
        if (isInitialized || MobileAds.isInitialized) {
            isInitialized = true
            onInitializationComplete?.invoke()
            return
        }

        val applicationId = applicationIdOverride ?: readApplicationIdFromManifest()
        if (applicationId.isNullOrBlank()) {
            // Initializing with a blank id fails inside the SDK and surfaces later as an unexplained
            // no-fill on every unit, so name the actual cause once, here.
            AdsLog.e(
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
            // Audience tagging must be in place before the first request, not after it - which on
            // Next-Gen means folding it into the config, since the SDK has no setter after startup.
            val config = InitializationConfig.Builder(applicationId)
                .apply { requestConfig?.let { setRequestConfiguration(it.toRequestConfiguration()) } }
                .build()
            MobileAds.initialize(application, config) { initializationStatus ->
                AdsLog.d(TAG, "Monetization:- AdMob initialized: $initializationStatus")
                isInitialized = true
                // The Next-Gen SDK calls back on a background thread, so the hop to Main is what
                // makes it safe for hosts to start loading ads (and touching views) from here.
                CoroutineScope(Dispatchers.Main).launch {
                    onInitializationComplete?.invoke()
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
        AdsLog.e(TAG, "Monetization:- failed to read $MANIFEST_APPLICATION_ID from the manifest", it)
    }.getOrNull()

    /**
     * Sets the App Open Ad Resume ID.
     *
     * @param appOpenAdResumeId The ad unit ID for the app open ad resume.
     * @return The current instance of AdMobManager.
     */
    fun setAppOpenAdResumeId( @ValidateAdUnitId appOpenAdResumeId: String): AdMobManager {
        // A malformed id leaves the previous value in place rather than poisoning the controller.
        if (AdUnitIdValidator.validateAdUnitId(appOpenAdResumeId)) {
            adController.appOpenAdResumeId = appOpenAdResumeId
        }
        return this
    }

    /**
     * Sets the App Open Ad Start ID.
     *
     * @param appOpenAdStartId The ad unit ID for the app open ad start.
     * @return The current instance of AdMobManager.
     */
    fun setAppOpenAdStartId( @ValidateAdUnitId appOpenAdStartId: String): AdMobManager {
        if (AdUnitIdValidator.validateAdUnitId(appOpenAdStartId)) {
            adController.appOpenAdStartId = appOpenAdStartId
        }
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
     * Registers a live source of truth for premium status, consulted on every ad request.
     *
     * [setPremium] is a snapshot: it defaults to false, so between process start and the app setting
     * it, a paying user can be served ads. A provider closes that window because it is read at request
     * time - point it at whatever you already have (billing cache, prefs, DB) and the library never
     * needs its own storage:
     *
     * ```kotlin
     * AdMobManager.getInstance(app).setPremiumProvider { billingRepo.isSubscribed }
     * ```
     *
     * The provider wins over [setPremium] whenever it is registered. Called on the calling thread, so
     * keep it cheap - no disk or network reads.
     */
    fun setPremiumProvider(provider: (() -> Boolean)?): AdMobManager {
        premiumProvider = provider
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

        /**
         * Current premium status. Reads through [premiumProvider] when one is registered, otherwise
         * returns the value last set via [setPremium].
         */
        @JvmStatic
        var isPremium: Boolean = false
            get() = premiumProvider?.invoke() ?: field

        @Volatile
        internal var premiumProvider: (() -> Boolean)? = null

        /** True once [initialize] has been called. Not the same as the SDK having finished starting. */
        @Volatile
        internal var initializeCalled: Boolean = false

        private val initWarningLogged = AtomicBoolean(false)

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
         * Logs, once, when an ad is requested before [initialize].
         *
         * [getInstance] only builds the facade - nothing else in the library starts `MobileAds`, so
         * skipping [initialize] leaves every request failing, and the SDK usually reports it as
         * "Network error". That sends the integrator to check connectivity, the emulator, the ad unit
         * ids and the manifest before they think to check this, which is a whole afternoon. Naming it
         * costs one log line.
         *
         * Gated on [initializeCalled] rather than on the SDK having *finished* starting: startup is
         * asynchronous, so a correct integration that requests an ad a moment too early would
         * otherwise be accused of never having initialized at all.
         */
        @JvmStatic
        internal fun warnIfNotInitialized() {
            if (initializeCalled) return
            if (initWarningLogged.compareAndSet(false, true)) {
                AdsLog.e(
                    TAG,
                    "Ad requested before AdMobManager.initialize(). MobileAds was never started, so " +
                        "every request will fail - usually reported as \"Network error\", which is " +
                        "misleading. Call initialize() (or gatherConsent(), which initializes for " +
                        "you) from Application.onCreate before requesting ads."
                )
            }
        }
    }
}