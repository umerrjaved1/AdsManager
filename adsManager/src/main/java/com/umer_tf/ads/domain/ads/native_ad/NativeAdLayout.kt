package com.umer_tf.ads.domain.ads.native_ad

import androidx.annotation.LayoutRes
import com.umer_tf.ads.R
import com.umer_tf.ads.domain.utils.AdsLog

/**
 * The bundled native ad layouts, addressable by a short code instead of an `R.layout` constant.
 *
 * Picking a native ad used to mean knowing the library's resource names
 * (`com.umer_tf.ads.R.layout.layout_native_ad_small_4`) and separately knowing which shimmer went with
 * it. A screen now needs two facts - which shape, and which ad unit:
 *
 * ```kotlin
 * adViewModel.bindNativeAd(this, AD_UNIT, "4a", binding.adContainer, binding.shimmerHost)
 * ```
 *
 * Every constant here is registered in [NativeAdShimmer], so the placeholder always matches the shape.
 *
 * @property code The short form used in [from] - `"1a"`, `"4a"`, `"v2"`, `"large"`.
 * @property layoutResId The layout this code resolves to.
 */
enum class NativeAdLayout(
    val code: String,
    @LayoutRes val layoutResId: Int,
    internal val aliases: List<String> = emptyList()
) {

    /** 130 dp banner shape: icon + text + CTA. */
    BANNER("banner", R.layout.layout_native_ad_banner),

    /** Media left, text right. */
    SMALL_1A("1a", R.layout.layout_native_ad_small_1a),

    /** Icon + text, no media. */
    SMALL_1B("1b", R.layout.layout_native_ad_small_1b),

    /** Media left, icon + headline + body. */
    SMALL_1C("1c", R.layout.layout_native_ad_small_1c),

    /** Media left, icon + headline + body, alternate spacing. */
    SMALL_1D("1d", R.layout.layout_native_ad_small_1d),

    /** Icon left, CTA right. */
    SMALL_3A("3a", R.layout.layout_native_ad_small_3a),

    /** Icon left, CTA right, alternate spacing. */
    SMALL_3B("3b", R.layout.layout_native_ad_small_3b),

    /** Media + icon header. Accepts `"4a"` as well as `"4"`. */
    SMALL_4("4", R.layout.layout_native_ad_small_4, aliases = listOf("4a")),

    /** Compact, text-forward. */
    SMALL_7A("7a", R.layout.layout_native_ad_small_7a),

    /** Compact, text-forward. */
    SMALL_7B("7b", R.layout.layout_native_ad_small_7b),

    /** Compact, text-forward. */
    SMALL_7C("7c", R.layout.layout_native_ad_small_7c),

    /** Icon header, body, media, CTA. */
    LARGE("large", R.layout.layout_native_ad_large),

    /** Large media variant. */
    LARGE_5A("5a", R.layout.layout_native_ad_large_5a),

    /** Large media variant. */
    LARGE_6A("6a", R.layout.layout_native_ad_large_6a),

    /** Large media variant. */
    LARGE_6B("6b", R.layout.layout_native_ad_large_6b),

    /** Media-first with a rating row. */
    LARGE_V2("v2", R.layout.layout_native_ad_large_v2),

    /** Media-first with a rating row. */
    LARGE_V3("v3", R.layout.layout_native_ad_large_v3),

    /** The library's small default. */
    SMALL_DEFAULT("small", R.layout.admob_small_native_media, aliases = listOf("default")),

    /** The library's large default. */
    LARGE_DEFAULT("largemedia", R.layout.admob_large_native_media),

    /** Banner-shaped native default. */
    BANNER_DEFAULT("bannermedia", R.layout.admob_native_banner_type),

    /** Used by [FullScreenNativeAdActivity]. */
    FULLSCREEN("fullscreen", R.layout.admob_native_fullscreen);

    companion object {

        private const val TAG = "AdsManager_Native"

        /** What [from] falls back to when a code cannot be resolved. */
        @JvmField
        val DEFAULT: NativeAdLayout = SMALL_1A

        private val byKey: Map<String, NativeAdLayout> = buildMap {
            // Qualified: inside buildMap, a bare `entries` would resolve to the MutableMap's own.
            NativeAdLayout.entries.forEach { layout ->
                // Each constant answers to its code, its enum name and its aliases, all normalised, so
                // "1a", "SMALL_1A" and "small_1a" are the same request.
                put(normalize(layout.code), layout)
                put(normalize(layout.name), layout)
                layout.aliases.forEach { put(normalize(it), layout) }
            }
        }

        /**
         * Resolves a short code to a layout, or null when nothing matches.
         *
         * Matching ignores case, separators and the `layout_native_ad_` / `small_` / `large_` prefixes,
         * so `"4a"`, `"SMALL_4"` and `"layout_native_ad_small_4"` all resolve to [SMALL_4].
         */
        @JvmStatic
        fun from(code: String): NativeAdLayout? {
            val normalized = normalize(code)
            if (normalized.isEmpty()) return null
            byKey[normalized]?.let { return it }

            // Only strip a shape prefix when something is left over: "large" is itself a valid code, so
            // stripping it unconditionally would turn a good request into an empty one.
            for (prefix in STRIPPABLE_PREFIXES) {
                if (normalized.startsWith(prefix) && normalized.length > prefix.length) {
                    byKey[normalized.removePrefix(prefix)]?.let { return it }
                }
            }
            return null
        }

        /**
         * Resolves a short code, falling back to [fallback] and logging when it does not match.
         *
         * Used by the binding helpers: an unrecognised code is an integration mistake, but rendering the
         * default shape is better than rendering no ad at all.
         */
        @JvmStatic
        @JvmOverloads
        fun fromOrDefault(code: String, fallback: NativeAdLayout = DEFAULT): NativeAdLayout {
            val resolved = from(code)
            if (resolved == null) {
                AdsLog.w(
                    TAG,
                    "Unknown native ad layout code \"$code\", falling back to ${fallback.code}. " +
                        "Valid codes: ${NativeAdLayout.entries.joinToString { it.code }}"
                )
                return fallback
            }
            return resolved
        }

        /** Longest first, so `"layoutnativead"` is tried before `"native"`. */
        private val STRIPPABLE_PREFIXES = listOf(
            "layoutnativead", "admobnative", "nativead", "native", "small", "large"
        )

        private fun normalize(raw: String): String =
            raw.lowercase().filter { it.isLetterOrDigit() }
    }
}
