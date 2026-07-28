package com.umer_tf.ads.domain.ads.native_ad

import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAdOptions

/**
 * NativeAdBuilder is a builder class for constructing native ad configurations.
 */
class NativeAdBuilder private constructor(
    val layout: Int,
    val frameLayout: FrameLayout?,
    val shimmerFrameLayout: ShimmerFrameLayout?,
    val iconEnabled: Boolean,
    val showMedia: Boolean,
    val showRating: Boolean,
    val showPrice: Boolean,
    val showStore: Boolean,
    val showAdvertiser: Boolean,
    val showHeadline: Boolean,
    val showBody: Boolean,
    val showCallToAction: Boolean,
    val showAdChoices: Boolean,
    val adBgColor: String?,
    val ctaBgColor: String?,
    val ctaTextColor: String?,
    val ctaRadius: Int = 16,
    val adTitleColor: String?,
    val adBodyColor: String?,
    val showBgStroke: Boolean = true,
    val strokeColor: String? = null,
    val strokeWidth: Int = 1,
    val shimmerBaseColor: String? = null,
    val shimmerHighlightColor: String? = null,
    val adCornerRadius: Int = 12,
    val badgeTextColor: String? = null,
    val badgeStrokeColor: String? = null,
    val adChoicesPlacement: Int = NativeAdOptions.ADCHOICES_TOP_RIGHT
) {

    /**
     * Builder class for constructing NativeAdBuilder instances.
     */
    class Builder(private var layout: Int, private var frameLayout: FrameLayout?, private var shimmerFrameLayout: ShimmerFrameLayout?) {
        private var iconEnabled: Boolean = false
        private var showMedia: Boolean = false
        private var showRating: Boolean = false
        private var showPrice: Boolean = false
        private var showStore: Boolean = false
        private var showAdvertiser: Boolean = false
        private var showHeadline: Boolean = true
        private var showBody: Boolean = false
        private var showCallToAction: Boolean = true
        // AdMob policy: the AdChoices overlay must be present. Defaulted on so an integrator cannot
        // ship a non-compliant ad by simply not calling the setter.
        private var showAdChoices: Boolean = true
        private var adChoicesPlacement: Int = NativeAdOptions.ADCHOICES_TOP_RIGHT
        private var adBgColor: String? = null
        private var ctaBgColor: String? = null
        private var ctaTextColor: String? = null
        private var ctaRadius: Int = 16
        private var adTitleColor: String? = null
        private var adBodyColor: String? = null
        private var showBgStroke: Boolean = true
        private var strokeColor: String? = null
        private var strokeWidth: Int = 1
        private var shimmerBaseColor: String? = null
        private var shimmerHighlightColor: String? = null
        private var adCornerRadius: Int = 12
        private var badgeTextColor: String? = null
        private var badgeStrokeColor: String? = null

        fun setLayout(layout: Int) = apply { this.layout = layout }
        fun setFrameLayout(frameLayout: FrameLayout?) = apply { this.frameLayout = frameLayout }
        fun setShimmerFrameLayout(shimmerFrameLayout: ShimmerFrameLayout?) = apply { this.shimmerFrameLayout = shimmerFrameLayout }
        fun setIconEnabled(iconEnabled: Boolean) = apply { this.iconEnabled = iconEnabled }
        fun setShowMedia(showMedia: Boolean) = apply { this.showMedia = showMedia }
        fun setShowRating(showRating: Boolean) = apply { this.showRating = showRating }
        fun setShowPrice(showPrice: Boolean) = apply { this.showPrice = showPrice }
        fun setShowStore(showStore: Boolean) = apply { this.showStore = showStore }
        fun setShowAdvertiser(showAdvertiser: Boolean) = apply { this.showAdvertiser = showAdvertiser }
        fun setShowHeadline(showHeadline: Boolean) = apply { this.showHeadline = showHeadline }
        fun setShowBody(showBody: Boolean) = apply { this.showBody = showBody }
        fun setShowCallToAction(showCallToAction: Boolean) = apply { this.showCallToAction = showCallToAction }
        fun setShowAdChoices(showAdChoices: Boolean) = apply { this.showAdChoices = showAdChoices }
        fun setAdBgColor(adBgColor: String?) = apply { this.adBgColor = adBgColor }
        fun setCtaBgColor(ctaBgColor: String?) = apply { this.ctaBgColor = ctaBgColor }
        fun setCtaTextColor(ctaTextColor: String?) = apply { this.ctaTextColor = ctaTextColor }
        fun setCtaRadius(ctaRadius: Int) = apply { this.ctaRadius = ctaRadius }
        fun setAdTitleColor(adTitleColor: String?) = apply { this.adTitleColor = adTitleColor }
        fun setAdBodyColor(adBodyColor: String?) = apply { this.adBodyColor = adBodyColor }
        fun setShowBgStroke(showBgStroke: Boolean) = apply { this.showBgStroke = showBgStroke }
        fun setStrokeColor(strokeColor: String?) = apply { this.strokeColor = strokeColor }
        fun setStrokeWidth(strokeWidth: Int) = apply { this.strokeWidth = strokeWidth }

        /** Corner radius (dp) of the ad card background. */
        fun setAdCornerRadius(adCornerRadius: Int) = apply { this.adCornerRadius = adCornerRadius }

        /** Colours of the "Ad" attribution label. Visibility is not configurable, by policy. */
        fun setBadgeColors(textColor: String?, strokeColor: String? = textColor) = apply {
            this.badgeTextColor = textColor
            this.badgeStrokeColor = strokeColor
        }

        /**
         * Where the AdChoices overlay is drawn. One of the `NativeAdOptions.ADCHOICES_*` constants.
         */
        fun setAdChoicesPlacement(placement: Int) = apply { this.adChoicesPlacement = placement }

        /**
         * Customizes shimmer colors specifically for this native ad container.
         *
         * Only the shimmer is affected - this no longer drags the rest of the ad into the light
         * palette as a side effect, which used to wash out dark-themed ads.
         */
        fun setShimmerColor(baseColor: String?, highlightColor: String?) = apply {
            this.shimmerBaseColor = baseColor
            this.shimmerHighlightColor = highlightColor
            if (baseColor != null && highlightColor != null) {
                NativeAdTheme(
                    adBgColor = adBgColor ?: "#FFFFFF",
                    adTitleColor = adTitleColor ?: "#111827",
                    adBodyColor = adBodyColor ?: "#4B5563",
                    ctaBgColor = ctaBgColor ?: "#2563EB",
                    ctaTextColor = ctaTextColor ?: "#FFFFFF",
                    shimmerBaseColor = baseColor,
                    shimmerHighlightColor = highlightColor
                ).applyShimmerTo(shimmerFrameLayout)
            }
        }

        /**
         * Applies a NativeAdTheme (Light, Dark, or Auto) programmatically, including shimmer colors.
         */
        fun setTheme(theme: NativeAdTheme) = apply {
            this.adBgColor = theme.adBgColor
            this.adTitleColor = theme.adTitleColor
            this.adBodyColor = theme.adBodyColor
            this.ctaBgColor = theme.ctaBgColor
            this.ctaTextColor = theme.ctaTextColor
            this.strokeColor = theme.strokeColor
            this.showBgStroke = theme.showBgStroke
            this.strokeWidth = theme.strokeWidth
            this.ctaRadius = theme.ctaRadius
            this.shimmerBaseColor = theme.shimmerBaseColor
            this.shimmerHighlightColor = theme.shimmerHighlightColor
            this.adCornerRadius = theme.adCornerRadius
            this.badgeTextColor = theme.badgeTextColor
            this.badgeStrokeColor = theme.badgeStrokeColor

            theme.applyShimmerTo(shimmerFrameLayout)
        }

        fun build(): NativeAdBuilder {
            return NativeAdBuilder(
                layout,
                frameLayout,
                shimmerFrameLayout,
                iconEnabled,
                showMedia,
                showRating,
                showPrice,
                showStore,
                showAdvertiser,
                showHeadline,
                showBody,
                showCallToAction,
                showAdChoices,
                adBgColor,
                ctaBgColor,
                ctaTextColor,
                ctaRadius,
                adTitleColor,
                adBodyColor,
                showBgStroke,
                strokeColor,
                strokeWidth,
                shimmerBaseColor,
                shimmerHighlightColor,
                adCornerRadius,
                badgeTextColor,
                badgeStrokeColor,
                adChoicesPlacement
            )
        }
    }
}