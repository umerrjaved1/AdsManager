package com.umer_tf.ads.domain.ads.banner

import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import com.facebook.shimmer.ShimmerFrameLayout
import com.umer_tf.ads.R
import com.umer_tf.ads.domain.ads.native_ad.NativeAdShimmer
import com.umer_tf.ads.domain.ads.native_ad.NativeAdTheme

/**
 * Picks the shimmer that matches a banner shape, so a banner slot is described the same way a native
 * one is: an empty host, and the library fills it.
 *
 * Native ads got this via [NativeAdShimmer]; banners did not, which meant every integrator had to
 * hand-pick a `ShimmerFrameLayout` and hard-code its height in XML - and then it drifted out of sync
 * the moment the ad shape changed. Handing over an empty container instead means the placeholder
 * cannot be the wrong shape.
 *
 * A caller that really does want its own shimmer can still pass a [ShimmerFrameLayout] directly;
 * [BannerAdLoader] uses it as-is and only resizes it to the resolved ad height.
 */
object BannerShimmer {

    /** The shimmer layout used for [type]. */
    @JvmStatic
    @LayoutRes
    fun shimmerLayoutFor(type: BannerAdType): Int = when (type) {
        // 300x250 is closer to a large-media native ad than to a banner strip.
        BannerAdType.MEDIUM_RECTANGLE -> R.layout.adlibrary_shimmer_native_media_large
        else -> R.layout.adlibrary_shimmer_native_banner
    }

    /**
     * Inflates the shimmer for [type] into [host], themes it and starts it.
     *
     * Any previous children of [host] are removed, so this is safe to call again when a slot is
     * re-shown. Returns null if inflation failed - the banner then simply loads without a placeholder
     * rather than taking the screen down.
     *
     * @param clearHost False when [host] is the ad container itself and already holds the `AdView`;
     *   the shimmer is then added on top of it rather than replacing it.
     */
    @JvmStatic
    @JvmOverloads
    fun attachTo(
        host: ViewGroup,
        type: BannerAdType,
        theme: NativeAdTheme? = null,
        clearHost: Boolean = true
    ): ShimmerFrameLayout? {
        host.visibility = View.VISIBLE
        return NativeAdShimmer.inflateInto(host, shimmerLayoutFor(type), theme, clearHost)
    }
}
