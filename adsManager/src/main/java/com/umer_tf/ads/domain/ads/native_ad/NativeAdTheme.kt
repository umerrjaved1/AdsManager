package com.umer_tf.ads.domain.ads.native_ad

import android.content.Context
import android.content.res.Configuration
import androidx.core.graphics.toColorInt
import com.facebook.shimmer.Shimmer
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * Programmatic theme configuration for native ads without XML color resources.
 * Supports light mode, dark mode, auto-system detection, shimmer colors, and per-ad custom overrides via .copy().
 */
data class NativeAdTheme(
    val adBgColor: String,
    val adTitleColor: String,
    val adBodyColor: String,
    val ctaBgColor: String,
    val ctaTextColor: String,
    val strokeColor: String? = null,
    val showBgStroke: Boolean = true,
    val strokeWidth: Int = 1,
    val ctaRadius: Int = 16,
    val shimmerBaseColor: String? = null,
    val shimmerHighlightColor: String? = null
) {
    /**
     * Applies the configured shimmer colors programmatically to a ShimmerFrameLayout.
     */
    fun applyShimmerTo(shimmerFrameLayout: ShimmerFrameLayout?) {
        if (shimmerFrameLayout == null) return
        val baseStr = shimmerBaseColor ?: return
        val highlightStr = shimmerHighlightColor ?: return

        kotlin.runCatching {
            val base = baseStr.toColorInt()
            val highlight = highlightStr.toColorInt()

            val shimmer = Shimmer.ColorHighlightBuilder()
                .setBaseColor(base)
                .setHighlightColor(highlight)
                .setDuration(1200L)
                .setDirection(Shimmer.Direction.LEFT_TO_RIGHT)
                .setAutoStart(true)
                .build()

            shimmerFrameLayout.setShimmer(shimmer)
            shimmerFrameLayout.startShimmer()
        }
    }

    companion object {
        /**
         * Light Mode default color palette & light shimmer.
         * Suggested Shimmer Colors for Light Mode:
         * - Base: #E0E0E0 (Soft neutral grey)
         * - Highlight: #F5F5F5 (Bright sheen pass)
         */
        fun light(
            adBgColor: String = "#FFFFFF",
            adTitleColor: String = "#111827",
            adBodyColor: String = "#4B5563",
            ctaBgColor: String = "#2563EB",
            ctaTextColor: String = "#FFFFFF",
            strokeColor: String = "#E5E7EB",
            showBgStroke: Boolean = true,
            strokeWidth: Int = 1,
            ctaRadius: Int = 16,
            shimmerBaseColor: String = "#E0E0E0",
            shimmerHighlightColor: String = "#F5F5F5"
        ): NativeAdTheme {
            return NativeAdTheme(
                adBgColor = adBgColor,
                adTitleColor = adTitleColor,
                adBodyColor = adBodyColor,
                ctaBgColor = ctaBgColor,
                ctaTextColor = ctaTextColor,
                strokeColor = strokeColor,
                showBgStroke = showBgStroke,
                strokeWidth = strokeWidth,
                ctaRadius = ctaRadius,
                shimmerBaseColor = shimmerBaseColor,
                shimmerHighlightColor = shimmerHighlightColor
            )
        }

        /**
         * Dark Mode default color palette & dark shimmer.
         * Suggested Shimmer Colors for Dark Mode:
         * - Base: #2A2F3A (Dark slate grey)
         * - Highlight: #3E4452 (Lighter slate sheen pass)
         */
        fun dark(
            adBgColor: String = "#1F2937",
            adTitleColor: String = "#F9FAFB",
            adBodyColor: String = "#D1D5DB",
            ctaBgColor: String = "#3B82F6",
            ctaTextColor: String = "#FFFFFF",
            strokeColor: String = "#374151",
            showBgStroke: Boolean = true,
            strokeWidth: Int = 1,
            ctaRadius: Int = 16,
            shimmerBaseColor: String = "#2A2F3A",
            shimmerHighlightColor: String = "#3E4452"
        ): NativeAdTheme {
            return NativeAdTheme(
                adBgColor = adBgColor,
                adTitleColor = adTitleColor,
                adBodyColor = adBodyColor,
                ctaBgColor = ctaBgColor,
                ctaTextColor = ctaTextColor,
                strokeColor = strokeColor,
                showBgStroke = showBgStroke,
                strokeWidth = strokeWidth,
                ctaRadius = ctaRadius,
                shimmerBaseColor = shimmerBaseColor,
                shimmerHighlightColor = shimmerHighlightColor
            )
        }

        /**
         * Automatically selects Light or Dark theme based on system/context Configuration.
         */
        fun auto(
            context: Context,
            lightTheme: NativeAdTheme = light(),
            darkTheme: NativeAdTheme = dark()
        ): NativeAdTheme {
            val currentNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
                darkTheme
            } else {
                lightTheme
            }
        }
    }
}
