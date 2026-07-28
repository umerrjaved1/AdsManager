package com.umer_tf.ads.domain.analytics

/**
 * Every ad type the library can serve.
 *
 * This is the single identifier for an ad slot across the whole library: it names the type in
 * [AdEventListener] callbacks and labels revenue events. It replaced a set of loose strings
 * (`"banner_ad"`, `"Interstitial_ad"`, `"NativeAd"`, …) that were duplicated at each call site and
 * drifted apart.
 *
 * @param revenueName Value reported as the `ad_format` dimension on revenue events.
 * @param analyticsName Firebase event name for lifecycle events. These deliberately keep the exact
 *   strings the library used before this enum existed - renaming them would break continuity in
 *   dashboards already collecting them, which is not worth tidier identifiers.
 */
enum class AdType(
    val revenueName: String,
    val analyticsName: String
) {
    BANNER("Banner", "banner_ad"),
    BANNER_MEDIUM_RECTANGLE("MemRecBanner", "banner_memrec_ad"),
    BANNER_COLLAPSIBLE("CollapsableBanner", "banner_collapsable_ad"),
    INTERSTITIAL("Interstitial", "Interstitial_ad"),
    REWARDED("Rewarded", "rewarded_ad"),
    NATIVE("Native", "NativeAd"),
    NATIVE_EXIT("ExitNative", "ExitNative"),
    APP_OPEN_START("OpenAd_Start", "OpenAd_Start"),
    APP_OPEN_RESUME("OpenAd_Resume", "OpenAd_Resume");

    override fun toString(): String = revenueName

    companion object {
        /** Resolves a legacy free-text format name back to an [AdType]; null when unrecognised. */
        @JvmStatic
        fun fromRevenueName(name: String): AdType? = entries.firstOrNull { it.revenueName == name }
    }
}
