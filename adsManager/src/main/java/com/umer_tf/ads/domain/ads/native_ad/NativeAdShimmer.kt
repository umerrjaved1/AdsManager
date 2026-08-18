package com.umer_tf.ads.domain.ads.native_ad

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import com.facebook.shimmer.ShimmerFrameLayout
import com.umer_tf.ads.R
import com.umer_tf.ads.domain.utils.AdsLog

/**
 * Single source of truth for pairing a native ad layout with the shimmer placeholder that has the
 * same shape.
 *
 * Before this existed, every call site had to hand-pick a shimmer layout, and several ad layouts had
 * no shimmer at all, so they silently fell back to the full-screen shimmer and flashed a
 * wrongly-sized placeholder before the ad appeared.
 *
 * Consumers with their own layouts can register a pairing once (typically in `Application.onCreate`)
 * via [register] and everything else in the library will pick it up:
 *
 * ```kotlin
 * NativeAdShimmer.register(R.layout.my_native_ad, R.layout.my_native_ad_shimmer)
 * ```
 */
object NativeAdShimmer {

    private const val TAG = "AdsManager_Shimmer"

    private val pairings = linkedMapOf(
        // Library defaults
        R.layout.admob_native_fullscreen to R.layout.adlibrary_shimmer_native_fullscreen,
        R.layout.admob_small_native_media to R.layout.adlibrary_shimmer_native_media_small,
        R.layout.admob_large_native_media to R.layout.adlibrary_shimmer_native_media_large,
        // Its own shimmer, not adlibrary_shimmer_native_banner: this shape is a fixed 80sdp card with
        // a 50sdp trailing CTA, where layout_native_ad_banner is a 130dp card with a full-width one.
        R.layout.admob_native_banner_type to R.layout.shimmer_native_banner,

        // Variant layouts
        R.layout.layout_native_ad_banner to R.layout.adlibrary_shimmer_native_banner,
        R.layout.layout_native_ad_large to R.layout.adlibrary_shimmer_native_large,
        R.layout.layout_native_ad_large_5a to R.layout.adlibrary_shimmer_native_large_5a,
        R.layout.layout_native_ad_large_6a to R.layout.adlibrary_shimmer_native_large_6a,
        R.layout.layout_native_ad_large_6b to R.layout.adlibrary_shimmer_native_large_6b,
        R.layout.layout_native_ad_large_v2 to R.layout.adlibrary_shimmer_native_large_v2,
        R.layout.layout_native_ad_large_v3 to R.layout.adlibrary_shimmer_native_large_v3,
        R.layout.layout_native_ad_small_1a to R.layout.adlibrary_shimmer_native_small_1a,
        R.layout.layout_native_ad_small_1b to R.layout.adlibrary_shimmer_native_small_1b,
        R.layout.layout_native_ad_small_1c to R.layout.adlibrary_shimmer_native_small_1c,
        R.layout.layout_native_ad_small_1d to R.layout.adlibrary_shimmer_native_small_1d,
        R.layout.layout_native_ad_small_3a to R.layout.adlibrary_shimmer_native_small_3a,
        R.layout.layout_native_ad_small_3b to R.layout.adlibrary_shimmer_native_small_3b,
        R.layout.layout_native_ad_small_4 to R.layout.adlibrary_shimmer_native_small_4,
        R.layout.layout_native_ad_small_7a to R.layout.adlibrary_shimmer_native_small_7a,
        R.layout.layout_native_ad_small_7b to R.layout.adlibrary_shimmer_native_small_7b,
        R.layout.layout_native_ad_small_7c to R.layout.adlibrary_shimmer_native_small_7c
    )

    /** Registers (or overrides) the shimmer layout used for [adLayoutResId]. */
    @JvmStatic
    fun register(@LayoutRes adLayoutResId: Int, @LayoutRes shimmerLayoutResId: Int) {
        pairings[adLayoutResId] = shimmerLayoutResId
    }

    /** True when [adLayoutResId] has a shimmer of its own rather than relying on the fallback. */
    @JvmStatic
    fun hasShimmerFor(@LayoutRes adLayoutResId: Int): Boolean = pairings.containsKey(adLayoutResId)

    /**
     * Returns the shimmer layout matching [adLayoutResId].
     *
     * `0` is treated as "library default layout", mirroring [NativeAdBuilder.layout]. Unknown
     * layouts fall back to the small-media shimmer and log a warning, because an unregistered
     * custom layout is a configuration mistake, not an expected state.
     */
    @JvmStatic
    @LayoutRes
    fun resolveShimmerLayout(@LayoutRes adLayoutResId: Int): Int {
        val key = if (adLayoutResId == 0) R.layout.admob_small_native_media else adLayoutResId
        pairings[key]?.let { return it }
        AdsLog.w(
            TAG,
            "No shimmer registered for ad layout $adLayoutResId; falling back to the small-media " +
                "shimmer. Call NativeAdShimmer.register() to pair your own layout."
        )
        return R.layout.adlibrary_shimmer_native_media_small
    }

    /**
     * Inflates the shimmer matching [adLayoutResId] into [host], themes it, starts it and returns it.
     *
     * Any previous children of [host] are removed, so this is safe to call again when a container is
     * recycled (for example a native ad inside a RecyclerView row).
     */
    @JvmStatic
    @JvmOverloads
    fun attachTo(
        host: ViewGroup,
        @LayoutRes adLayoutResId: Int,
        theme: NativeAdTheme? = null
    ): ShimmerFrameLayout? = inflateInto(host, resolveShimmerLayout(adLayoutResId), theme)

    /**
     * Inflates an already-resolved shimmer layout into [host], themes it and starts it.
     *
     * Split out of [attachTo] so banners can reuse the same inflate-theme-start path without going
     * through the native layout->shimmer table, which does not describe them.
     */
    internal fun inflateInto(
        host: ViewGroup,
        @LayoutRes shimmerLayoutResId: Int,
        theme: NativeAdTheme? = null,
        clearHost: Boolean = true
    ): ShimmerFrameLayout? {
        return runCatching {
            // Not cleared when the host is the ad container itself and already holds the AdView -
            // wiping it there would detach the very view we are about to load into.
            if (clearHost) host.removeAllViews()
            val view = LayoutInflater.from(host.context)
                .inflate(shimmerLayoutResId, host, false)
            host.addView(view)

            val shimmer = view as? ShimmerFrameLayout
                ?: view.findViewByTypeOrNull()
                ?: error("Shimmer layout $shimmerLayoutResId has no ShimmerFrameLayout root")

            (theme ?: NativeAdTheme.auto(host.context)).applyShimmerTo(shimmer)
            host.visibility = View.VISIBLE
            shimmer.visibility = View.VISIBLE
            shimmer.startShimmer()
            shimmer
        }.getOrElse {
            AdsLog.e(TAG, "attachTo: failed to inflate shimmer $shimmerLayoutResId -> ${it.message}")
            null
        }
    }

    private fun View.findViewByTypeOrNull(): ShimmerFrameLayout? {
        if (this is ShimmerFrameLayout) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) {
            getChildAt(i).findViewByTypeOrNull()?.let { return it }
        }
        return null
    }
}

/**
 * Hides and stops a shimmer container without the caller having to remember to do both.
 * Every ad terminal state (loaded, failed, skipped) must go through this, otherwise the placeholder
 * keeps animating over an empty slot.
 */
fun ShimmerFrameLayout?.stopAndHide() {
    this ?: return
    stopShimmer()
    visibility = View.GONE
    // A host created by attachTo() holds nothing but the shimmer, so it has to be hidden too or it
    // keeps reserving the shimmer's height. Shared parents are left alone - they may also hold the
    // ad container.
    (parent as? FrameLayout)?.takeIf { it.childCount == 1 }?.visibility = View.GONE
}
