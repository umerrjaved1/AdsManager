package com.umer_tf.ads.domain.annotations

import com.umer_tf.ads.BuildConfig
import com.umer_tf.ads.domain.utils.AdsLog

object AdUnitIdValidator {

    private const val TAG = "AdsManager_AdUnitId"

    /** All AdMob ad unit ids share one shape, regardless of format. */
    private val GENERAL_AD_PATTERN = Regex("^ca-app-pub-\\d{16,}/\\d{10}$")

    /** Google's public test publisher id. */
    private val TEST_AD_PATTERN = Regex("^ca-app-pub-3940256099942544/\\d{10}$")

    /**
     * When true, a malformed ad unit id throws instead of being skipped.
     *
     * Defaults to debug-only. Ad unit ids in a product factory usually arrive from a per-app config
     * file or build field, and a single typo used to crash the app at the ad call site; in release the
     * right behaviour is to log loudly and show no ad. Keeping it strict in debug means the typo still
     * surfaces immediately during development.
     */
    @JvmStatic
    var strictMode: Boolean = BuildConfig.DEBUG

    /**
     * Validates an ad unit id.
     *
     * @return true when [adUnitId] is well formed. Callers must skip the ad request when this returns
     *   false and report failure through their own callback.
     * @throws IllegalArgumentException only when [strictMode] is on.
     */
    @JvmStatic
    fun validateAdUnitId(adUnitId: String): Boolean {
        if (isValidAdUnitId(adUnitId)) {
            warnIfTestIdInRelease(adUnitId)
            return true
        }

        val message = "Invalid Ad Unit ID format. Expected ca-app-pub-{16-digit-publisher-id}/" +
            "{10-digit-ad-id}, provided: \"$adUnitId\""
        if (strictMode) throw IllegalArgumentException(message)
        AdsLog.e(TAG, "$message - skipping this ad request.")
        return false
    }

    @JvmStatic
    fun validateBannerAdUnitId(adUnitId: String): Boolean = validateAdUnitId(adUnitId)

    @JvmStatic
    fun validateInterstitialAdUnitId(adUnitId: String): Boolean = validateAdUnitId(adUnitId)

    @JvmStatic
    fun validateRewardedAdUnitId(adUnitId: String): Boolean = validateAdUnitId(adUnitId)

    @JvmStatic
    fun validateNativeAdUnitId(adUnitId: String): Boolean = validateAdUnitId(adUnitId)

    @JvmStatic
    fun validateAppOpenAdUnitId(adUnitId: String): Boolean = validateAdUnitId(adUnitId)

    /**
     * Checks if an ad unit ID is valid.
     * @param adUnitId The ad unit ID to check.
     * @return true if valid, false otherwise.
     */
    @JvmStatic
    fun isValidAdUnitId(adUnitId: String): Boolean = GENERAL_AD_PATTERN.matches(adUnitId)

    /**
     * Checks if an ad unit ID is one of Google's test ad unit IDs.
     *
     * Test ids reaching a release build is otherwise silent, and shows up only as zero revenue.
     */
    @JvmStatic
    fun isTestAdUnitId(adUnitId: String): Boolean = TEST_AD_PATTERN.matches(adUnitId)

    /** Logs a warning, once per id, when a Google test ad unit id is used outside a debug build. */
    @JvmStatic
    fun warnIfTestIdInRelease(adUnitId: String) {
        if (BuildConfig.DEBUG || !isTestAdUnitId(adUnitId)) return
        if (warnedTestIds.add(adUnitId)) {
            AdsLog.w(TAG, "Google TEST ad unit id \"$adUnitId\" is in use outside a debug build.")
        }
    }

    private val warnedTestIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
}
