package com.umer_tf.ads.domain.ads.banner

import com.umer_tf.ads.domain.analytics.AdType

/**
 * Which banner shape to request.
 *
 * The three `show*Banner` methods on [BannerAdLoader] differ only in ad size and request extras, so
 * this collapses them into one parameter - which is what lets a banner be described by data
 * (`adUnitId` + shape) and therefore driven from a ViewModel.
 */
enum class BannerAdType(
    /** How this shape is reported to analytics and [com.umer_tf.ads.domain.analytics.AdEventListener]. */
    val adType: AdType
) {

    /** Anchored adaptive banner, sized to the current screen width. */
    ADAPTIVE(AdType.BANNER),

    /** Fixed 300x250 medium rectangle. */
    MEDIUM_RECTANGLE(AdType.BANNER_MEDIUM_RECTANGLE),

    /** Collapsible banner whose expanded area is anchored to the top of the screen. */
    COLLAPSIBLE_TOP(AdType.BANNER_COLLAPSIBLE),

    /** Collapsible banner whose expanded area is anchored to the bottom of the screen. */
    COLLAPSIBLE_BOTTOM(AdType.BANNER_COLLAPSIBLE);

    internal val isCollapsible: Boolean
        get() = this == COLLAPSIBLE_TOP || this == COLLAPSIBLE_BOTTOM
}
