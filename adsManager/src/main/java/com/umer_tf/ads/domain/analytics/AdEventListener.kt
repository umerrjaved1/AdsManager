package com.umer_tf.ads.domain.analytics

import com.google.android.gms.ads.AdValue

/**
 * Why an ad request failed, decoupled from the AdMob SDK types.
 *
 * @param code AdMob error code (`LoadAdError.getCode()`), or -1 when the failure came from the library
 *   rather than the SDK - a malformed ad unit id, or a request suppressed by consent/kill switch.
 * @param message Human-readable reason.
 * @param domain AdMob error domain, when the SDK supplied one.
 */
data class AdLoadFailure(
    val code: Int,
    val message: String,
    val domain: String? = null
) {
    companion object {
        /** Code used when the library itself refused the request, before it reached the SDK. */
        const val CODE_LIBRARY: Int = -1
    }
}

/** Reliability of a revenue figure, mirroring `AdValue.PrecisionType`. */
enum class AdValuePrecision {
    UNKNOWN,
    ESTIMATED,
    PUBLISHER_PROVIDED,
    PRECISE;

    companion object {
        fun from(precisionType: Int): AdValuePrecision = when (precisionType) {
            AdValue.PrecisionType.ESTIMATED -> ESTIMATED
            AdValue.PrecisionType.PUBLISHER_PROVIDED -> PUBLISHER_PROVIDED
            AdValue.PrecisionType.PRECISE -> PRECISE
            else -> UNKNOWN
        }
    }
}

/**
 * One AdMob paid event, normalised.
 *
 * @param valueMicros Raw value in micros of [currencyCode], exactly as AdMob reported it.
 * @param currencyCode ISO-4217 code AdMob reported for this event - never assumed to be USD.
 */
data class AdRevenueInfo(
    val adUnitId: String,
    val adType: AdType,
    val valueMicros: Long,
    val currencyCode: String,
    val precision: AdValuePrecision
) {
    /** [valueMicros] expressed in whole units of [currencyCode]. */
    val value: Double get() = valueMicros / 1_000_000.0

    companion object {
        internal fun from(adUnitId: String, adType: AdType, adValue: AdValue) = AdRevenueInfo(
            adUnitId = adUnitId,
            adType = adType,
            valueMicros = adValue.valueMicros,
            currencyCode = adValue.currencyCode,
            precision = AdValuePrecision.from(adValue.precisionType)
        )
    }
}

/**
 * Single place to observe the whole ad lifecycle, for every format, with the ad unit id and [AdType]
 * on every callback.
 *
 * Before this existed, each loader logged its own Firebase events inline with hand-written type
 * strings, and there was no way for an app to see ad events at all - so ad-revenue attribution,
 * custom dashboards and debugging all had to be rebuilt per app. Register one implementation and every
 * format reports through it:
 *
 * ```kotlin
 * AdMobManager.getInstance(this).setAdEventListener(object : AdEventListener {
 *     override fun onAdImpression(adUnitId: String, adType: AdType) {
 *         analytics.log("ad_impression", adType.revenueName, adUnitId)
 *     }
 *
 *     override fun onAdRevenuePaid(info: AdRevenueInfo) {
 *         AppsFlyerAdRevenue.logAdRevenue(
 *             "admob",
 *             MediationNetwork.GOOGLE_ADMOB,
 *             Currency.getInstance(info.currencyCode),
 *             info.value,
 *             mapOf("ad_format" to info.adType.revenueName)
 *         )
 *     }
 * })
 * ```
 *
 * Every method has a no-op default, so implement only what you need.
 *
 * ### Threading and failure
 * Lifecycle callbacks arrive on the main thread; [onAdRevenuePaid] may arrive on a background thread.
 * Implementations should not block, and should not throw - [AdEvents] catches and logs exceptions so a
 * broken listener cannot take an ad impression down with it, but a swallowed callback is lost data.
 *
 * ### Counting impressions
 * Never count [onAdLoaded] as an impression. A loaded ad has not necessarily been seen: cached ads may
 * never be shown, and for full-screen formats loading and showing can be minutes apart.
 *
 * Which callback means "seen" depends on the format:
 * - Banner and native report [onAdImpression], driven by AdMob's own impression signal.
 * - Interstitial, rewarded and app open report [onAdShowed] - `FullScreenContentCallback` has no
 *   separate impression callback in this integration, and the ad covers the screen the moment it
 *   shows.
 *
 * So an impression count is [onAdImpression] for inline formats plus [onAdShowed] for full-screen
 * ones; the two never both fire for the same ad.
 */
interface AdEventListener {

    /** An ad finished loading and is cached. Not an impression - see the class docs. */
    fun onAdLoaded(adUnitId: String, adType: AdType) {}

    /** A load request failed, or was refused by the library before reaching the SDK. */
    fun onAdFailedToLoad(adUnitId: String, adType: AdType, failure: AdLoadFailure) {}

    /** AdMob recorded an impression: the ad was actually rendered to the user. */
    fun onAdImpression(adUnitId: String, adType: AdType) {}

    /** The user tapped the ad. */
    fun onAdClicked(adUnitId: String, adType: AdType) {}

    /** A full-screen ad (interstitial, rewarded, app open) began showing. */
    fun onAdShowed(adUnitId: String, adType: AdType) {}

    /** A full-screen ad was closed and control returned to the app. */
    fun onAdDismissed(adUnitId: String, adType: AdType) {}

    /** AdMob reported revenue for this ad. The hook for ad-revenue attribution. */
    fun onAdRevenuePaid(info: AdRevenueInfo) {}
}
