package com.umer_tf.ads.domain.ads.native_ad

import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * NativeAdBuilder is a builder class for constructing native ad configurations.
 *
 * @property adUnitId The ad unit ID.
 * @property layout The layout resource ID for the ad.
 * @property frameLayout The FrameLayout to hold the ad view.
 * @property shimmerFrameLayout The ShimmerFrameLayout for loading animation.
 * @property iconEnabled Boolean indicating if the icon is enabled.
 * @property showMedia Boolean indicating if the media content is shown.
 * @property showRating Boolean indicating if the rating is shown.
 * @property showPrice Boolean indicating if the price is shown.
 * @property showStore Boolean indicating if the store information is shown.
 * @property showAdvertiser Boolean indicating if the advertiser information is shown.
 * @property showHeadline Boolean indicating if the headline is shown.
 * @property showBody Boolean indicating if the body text is shown.
 * @property showCallToAction Boolean indicating if the call to action is shown.
 * @property showAdChoices Boolean indicating if the ad choices are shown.
 * @property adBgColor The background color of the ad.
 * @property ctaBgColor The background color of the call to action button.
 * @property ctaTextColor The text color of the call to action button.
 * @property adTitleColor The color of the ad title.
 * @property adBodyColor The color of the ad body text.
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
    val ctaRadius: Int = 64,
    val adTitleColor: String?,
    val adBodyColor: String?,
    val showBgStroke: Boolean = true,
    val strokeColor: String? = null,
    val strokeWidth: Int = 1,
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
        private var showAdChoices: Boolean = false
        private var adBgColor: String? = null
        private var ctaBgColor: String? = null
        private var ctaTextColor: String? = null
        private var ctaRadius: Int = 64
        private var adTitleColor: String? = null
        private var adBodyColor: String? = null
        private var showBgStroke: Boolean = true
        private var strokeColor: String? = null
        private var strokeWidth: Int = 1

        /**
         * Sets the layout resource ID.
         *
         * @param layout The layout resource ID.
         * @return The Builder instance.
         */
        fun setLayout(layout: Int) = apply { this.layout = layout }

        /**
         * Sets the FrameLayout to hold the ad view.
         *
         * @param frameLayout The FrameLayout.
         * @return The Builder instance.
         */
        fun setFrameLayout(frameLayout: FrameLayout?) = apply { this.frameLayout = frameLayout }

        /**
         * Sets the ShimmerFrameLayout for loading animation.
         *
         * @param shimmerFrameLayout The ShimmerFrameLayout.
         * @return The Builder instance.
         */
        fun setShimmerFrameLayout(shimmerFrameLayout: ShimmerFrameLayout?) = apply { this.shimmerFrameLayout = shimmerFrameLayout }

        /**
         * Sets whether the icon is enabled.
         *
         * @param iconEnabled Boolean indicating if the icon is enabled.
         * @return The Builder instance.
         */
        fun setIconEnabled(iconEnabled: Boolean) = apply { this.iconEnabled = iconEnabled }

        /**
         * Sets whether the media content is shown.
         *
         * @param showMedia Boolean indicating if the media content is shown.
         * @return The Builder instance.
         */
        fun setShowMedia(showMedia: Boolean) = apply { this.showMedia = showMedia }

        /**
         * Sets whether the rating is shown.
         *
         * @param showRating Boolean indicating if the rating is shown.
         * @return The Builder instance.
         */
        fun setShowRating(showRating: Boolean) = apply { this.showRating = showRating }

        /**
         * Sets whether the price is shown.
         *
         * @param showPrice Boolean indicating if the price is shown.
         * @return The Builder instance.
         */
        fun setShowPrice(showPrice: Boolean) = apply { this.showPrice = showPrice }

        /**
         * Sets whether the store information is shown.
         *
         * @param showStore Boolean indicating if the store information is shown.
         * @return The Builder instance.
         */
        fun setShowStore(showStore: Boolean) = apply { this.showStore = showStore }

        /**
         * Sets whether the advertiser information is shown.
         *
         * @param showAdvertiser Boolean indicating if the advertiser information is shown.
         * @return The Builder instance.
         */
        fun setShowAdvertiser(showAdvertiser: Boolean) = apply { this.showAdvertiser = showAdvertiser }

        /**
         * Sets whether the headline is shown.
         *
         * @param showHeadline Boolean indicating if the headline is shown.
         * @return The Builder instance.
         */
        fun setShowHeadline(showHeadline: Boolean) = apply { this.showHeadline = showHeadline }

        /**
         * Sets whether the body text is shown.
         *
         * @param showBody Boolean indicating if the body text is shown.
         * @return The Builder instance.
         */
        fun setShowBody(showBody: Boolean) = apply { this.showBody = showBody }

        /**
         * Sets whether the call to action is shown.
         *
         * @param showCallToAction Boolean indicating if the call to action is shown.
         * @return The Builder instance.
         */
        fun setShowCallToAction(showCallToAction: Boolean) = apply { this.showCallToAction = showCallToAction }

        /**
         * Sets whether the ad choices are shown.
         *
         * @param showAdChoices Boolean indicating if the ad choices are shown.
         * @return The Builder instance.
         */
        fun setShowAdChoices(showAdChoices: Boolean) = apply { this.showAdChoices = showAdChoices }

        /**
         * Sets the background color of the ad.
         *
         * @param adBgColor The background color of the ad.
         * @return The Builder instance.
         */
        fun setAdBgColor(adBgColor: String?) = apply { this.adBgColor = adBgColor }

        /**
         * Sets the background color of the call to action button.
         *
         * @param ctaBgColor The background color of the call to action button.
         * @return The Builder instance.
         */
        fun setCtaBgColor(ctaBgColor: String?) = apply { this.ctaBgColor = ctaBgColor }

        /**
         * Sets the text color of the call to action button.
         *
         * @param ctaTextColor The text color of the call to action button.
         * @return The Builder instance.
         */
        fun setCtaTextColor(ctaTextColor: String?) = apply { this.ctaTextColor = ctaTextColor }

        fun setCtaRadius(ctaRadius: Int) = apply { this.ctaRadius = ctaRadius }

        /**
         * Sets the color of the ad title.
         *
         * @param adTitleColor The color of the ad title.
         * @return The Builder instance.
         */
        fun setAdTitleColor(adTitleColor: String?) = apply { this.adTitleColor = adTitleColor }

        /**
         * Sets the color of the ad body text.
         *
         * @param adBodyColor The color of the ad body text.
         * @return The Builder instance.
         */
        fun setAdBodyColor(adBodyColor: String?) = apply { this.adBodyColor = adBodyColor }

        fun setShowBgStroke(showBgStroke: Boolean) = apply { this.showBgStroke = showBgStroke }

        fun setStrokeColor(strokeColor: String?) = apply { this.strokeColor = strokeColor }

        fun setStrokeWidth(strokeWidth: Int) = apply { this.strokeWidth = strokeWidth }

        /**
         * Builds and returns a NativeAdBuilder instance.
         *
         * @return A new NativeAdBuilder instance.
         */
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
                strokeWidth
            )
        }
    }
}