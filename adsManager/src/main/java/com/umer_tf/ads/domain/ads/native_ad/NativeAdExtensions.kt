package com.umer_tf.ads.domain.ads.native_ad

import android.view.View
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * Extension function on FrameLayout to create and initialize a NativeAdBuilder in one line.
 * Handles shimmer start, initial view visibilities, and theme configuration automatically.
 */
fun FrameLayout.createNativeAdBuilder(
    shimmerFrameLayout: ShimmerFrameLayout?,
    @LayoutRes layoutResId: Int,
    theme: NativeAdTheme? = null,
    showMedia: Boolean = true,
    showBody: Boolean = true,
    showCallToAction: Boolean = true,
    iconEnabled: Boolean = true
): NativeAdBuilder {
    this.visibility = View.GONE
    shimmerFrameLayout?.startShimmer()
    shimmerFrameLayout?.visibility = View.VISIBLE

    val builder = NativeAdBuilder.Builder(layoutResId, this, shimmerFrameLayout)
        .setShowMedia(showMedia)
        .setShowBody(showBody)
        .setShowCallToAction(showCallToAction)
        .setIconEnabled(iconEnabled)

    val resolvedTheme = theme ?: NativeAdTheme.auto(context)
    builder.setTheme(resolvedTheme)

    return builder.build()
}
