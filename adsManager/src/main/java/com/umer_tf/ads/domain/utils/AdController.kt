package com.umer_tf.ads.domain.utils

import androidx.annotation.LayoutRes

class AdController {
    @JvmField
    var openAdResumeTime: Long = 5

    @JvmField
    var interstitialAdMinTime: Long = 0

    @JvmField
    var interstitialAdMaxTime: Long = 0

    @JvmField
    var interstitialCounter: Int = 0

    @JvmField
    var shouldShowOpenAd: Boolean = true

    @JvmField
    var shouldShowResumeAd: Boolean = true

    @JvmField
    var appOpenAdStartId: String = ""

    @JvmField
    var appOpenAdResumeId: String = ""

    @JvmField
    var isSplash: Boolean = false

    @JvmField
    @LayoutRes
    var loadingDialogLayoutResId: Int? = null

    /**
     * How long the "loading ad" dialog stays on screen after an interstitial finishes loading, before
     * the ad is shown. A short beat here stops the ad from appearing under the user's finger the
     * instant they tap, which is what causes accidental clicks.
     */
    @JvmField
    var interstitialDialogDelayMs: Long = DEFAULT_INTERSTITIAL_DIALOG_DELAY_MS

    /** Configuration for the loading dialog shown by interstitial / rewarded ads. */
    @JvmField
    var loadingDialogConfig: AdLoadingDialogConfig? = null

    /**
     * How long a cached ad stays usable, per format.
     *
     * Cached AdMob ads go stale: a stale ad tends to fail at show time, or fill at a lower value.
     * Treating an expired ad as absent means it gets replaced on the next load instead of wasting the
     * user's first tap on a failure.
     *
     * Google documents 4 hours for app open and roughly 1 hour for native. There is no published hard
     * TTL for interstitial or rewarded - 1 hour is a conservative default, tune it against your own
     * fill and show-rate data.
     */
    @JvmField
    var appOpenAdTtlMs: Long = DEFAULT_APP_OPEN_TTL_MS

    @JvmField
    var interstitialAdTtlMs: Long = DEFAULT_FULL_SCREEN_TTL_MS

    @JvmField
    var rewardedAdTtlMs: Long = DEFAULT_FULL_SCREEN_TTL_MS

    @JvmField
    var nativeAdTtlMs: Long = DEFAULT_NATIVE_TTL_MS

    companion object {
        const val DEFAULT_INTERSTITIAL_DIALOG_DELAY_MS: Long = 1500L

        /** Documented by Google for app open ads. */
        const val DEFAULT_APP_OPEN_TTL_MS: Long = 4 * 60 * 60 * 1000L

        /** Google's documented freshness window for native ads. */
        const val DEFAULT_NATIVE_TTL_MS: Long = 60 * 60 * 1000L

        /** Conservative default for interstitial / rewarded; not a documented AdMob limit. */
        const val DEFAULT_FULL_SCREEN_TTL_MS: Long = 60 * 60 * 1000L
    }
}