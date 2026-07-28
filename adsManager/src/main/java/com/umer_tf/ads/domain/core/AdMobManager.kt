package com.umer_tf.ads.domain.core

import android.app.Activity
import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.umer_tf.ads.domain.ads.app_open.AppOpenAdLoader
import com.umer_tf.ads.domain.ads.banner.BannerAdLoader
import com.umer_tf.ads.domain.ads.interstitial.InterstitialAdLoader
import com.umer_tf.ads.domain.ads.native_ad.NativeAd
import com.umer_tf.ads.domain.ads.rewarded.RewardedAdLoader
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import androidx.annotation.LayoutRes
import com.umer_tf.ads.domain.consent.AdsConsentGate
import com.umer_tf.ads.domain.consent.AdsConsentManager
import com.umer_tf.ads.domain.analytics.AdEventListener
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.utils.AdController
import com.umer_tf.ads.domain.utils.AdLoadingDialogConfig
import com.umer_tf.ads.domain.utils.AdsLog
import com.umer_tf.ads.domain.utils.LoadingDialogUtil
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

    @JvmField
    val appOpenAdLoader = AppOpenAdLoader(application, adController)

    @JvmField
    val bannerAdLoader = BannerAdLoader()

    @JvmField
    val interstitialAdLoader = InterstitialAdLoader(application, adController)

    @JvmField
    val nativeAdLoader = NativeAd(application).also { it.adTtlMs = adController.nativeAdTtlMs }

    @JvmField
    val rewardedAdLoader = RewardedAdLoader(application, adController)

    private var isInitialized = false
    private var requestConfig: AdsRequestConfig? = null

    /**
     * Consent manager backed by Google's User Messaging Platform.
     *
     * Prefer [gatherConsent] over driving this directly - it is what publishes the result to
     * [AdsConsentGate], which is what actually gates ad requests.
     */
    val consentManager: AdsConsentManager by lazy { AdsConsentManager(application) }

    /**
     * Initializes the AdMob SDK and starts the interstitial frequency-capping clock.
     *
     * This must be called once (typically from `Application.onCreate`) before requesting ads. Nothing
     * else in the library initializes [MobileAds], so skipping it leaves every request unfilled.
     *
     * If you target the EEA, call [gatherConsent] first (or instead - it initializes for you), so that
     * no request is made before the user has answered.
     *
     * @param onInitializationComplete Invoked on the main thread once initialization has finished.
     */
    @JvmOverloads
    fun initialize(onInitializationComplete: (() -> Unit)? = null) {
        TimeManager.getInstance().start()
        if (isInitialized) {
            onInitializationComplete?.invoke()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            // Audience tagging must be in place before the first request, not after it.
            requestConfig?.applyToSdk()
            MobileAds.initialize(application) { initializationStatus ->
                AdsLog.d(TAG, "Monetization:- AdMob initialized: $initializationStatus")
                isInitialized = true
                CoroutineScope(Dispatchers.Main).launch {
                    onInitializationComplete?.invoke()
                }
            }
        }
    }

    /**
     * Gathers user consent, then initializes the AdMob SDK - the correct order for EEA traffic.
     *
     * Until this completes, [com.umer_tf.ads.domain.utils.Utilities.shouldShowAd] reports false and
     * every loader short-circuits, so no request can escape ahead of the user's decision. Call it from
     * your launcher/splash Activity:
     *
     * ```kotlin
     * AdMobManager.getInstance(application).gatherConsent(this) { canRequestAds ->
     *     if (canRequestAds) startLoadingAds()
     * }
     * ```
     *
     * Safe to call on every launch: UMP only shows a form when one is actually required.
     *
     * @param activity Activity used to host the consent form.
     * @param isTest Forces the EEA debug geography so the form can be exercised outside Europe.
     * @param onConsentComplete Invoked with `canRequestAds` once consent settles and the SDK is up.
     */
    @JvmOverloads
    fun gatherConsent(
        activity: Activity,
        isTest: Boolean = false,
        onConsentComplete: ((Boolean) -> Unit)? = null
    ) {
        consentManager.gatherConsent(activity, isTest) { canRequestAds ->
            AdsLog.d(TAG, "gatherConsent settled: canRequestAds=$canRequestAds")
            initialize { onConsentComplete?.invoke(canRequestAds) }
        }
    }

    /**
     * Registers the single observer for every ad event across every format - load, impression, click,
     * failure, show, dismiss and revenue - each carrying the ad unit id and [AdType].
     *
     * This is the hook for ad-revenue attribution (AppsFlyer, Adjust, Singular, your own backend) and
     * for any custom ad analytics.
     *
     * @see AdEventListener
     */
    fun setAdEventListener(listener: AdEventListener?): AdMobManager {
        AdEvents.setListener(listener)
        return this
    }

    /**
     * Sets the global AdMob request configuration - COPPA / under-age tagging, max content rating and
     * test devices. Must be set before [initialize].
     *
     * @see AdsRequestConfig
     */
    fun setRequestConfig(config: AdsRequestConfig): AdMobManager {
        requestConfig = config
        // Applying immediately as well covers callers who configure after initialize().
        if (isInitialized) config.applyToSdk()
        return this
    }

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
     * Sets a custom layout resource ID for the loading dialog to match the app theme.
     *
     * @param layoutResId The custom layout resource ID.
     * @return The current instance of AdMobManager.
     */
    fun setLoadingDialogLayout(@LayoutRes layoutResId: Int): AdMobManager {
        adController.loadingDialogLayoutResId = layoutResId
        LoadingDialogUtil.setGlobalLoadingLayoutResId(layoutResId)
        return this
    }

    /**
     * Sets the loading dialog used by interstitial and rewarded ads. Covers everything from "same
     * layout, different wording" to supplying your own [android.app.Dialog].
     *
     * @see AdLoadingDialogConfig
     */
    fun setLoadingDialog(config: AdLoadingDialogConfig): AdMobManager {
        adController.loadingDialogConfig = config
        adController.loadingDialogLayoutResId = config.layoutResId
        LoadingDialogUtil.globalConfig = config
        return this
    }

    /**
     * Sets how long the loading dialog stays visible after an interstitial loads, before the ad is
     * shown. Defaults to [AdController.DEFAULT_INTERSTITIAL_DIALOG_DELAY_MS] (1500 ms).
     *
     * @param delayMs The delay in milliseconds.
     */
    fun setInterstitialDialogDelay(delayMs: Long): AdMobManager {
        adController.interstitialDialogDelayMs = delayMs
        return this
    }

    companion object {
        private const val TAG = "AdMobManager"
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