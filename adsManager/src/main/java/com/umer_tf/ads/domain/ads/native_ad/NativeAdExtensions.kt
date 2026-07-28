package com.umer_tf.ads.domain.ads.native_ad

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * Creates and initializes a [NativeAdBuilder] in one line: starts the shimmer, sets the initial view
 * visibilities and applies the theme.
 *
 * Pass [shimmerFrameLayout] only if your own layout already contains a shimmer shaped like
 * [layoutResId]. Leaving it null is the better default - see [createNativeAdBuilderAutoShimmer],
 * which inflates the shimmer that matches [layoutResId] for you.
 */
@JvmOverloads
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

    val resolvedTheme = theme ?: NativeAdTheme.auto(context)

    if (shimmerFrameLayout != null) {
        shimmerFrameLayout.visibility = View.VISIBLE
        shimmerFrameLayout.startShimmer()
    }

    return NativeAdBuilder.Builder(layoutResId, this, shimmerFrameLayout)
        .setShowMedia(showMedia)
        .setShowBody(showBody)
        .setShowCallToAction(showCallToAction)
        .setIconEnabled(iconEnabled)
        .setTheme(resolvedTheme)
        .build()
}

/**
 * Same as [createNativeAdBuilder], but inflates the shimmer that matches [layoutResId] into
 * [shimmerHost] instead of making the caller pick one.
 *
 * This is the recommended entry point: a hand-picked shimmer drifts out of sync with the ad layout as
 * soon as either changes, which is what produces the "placeholder is the wrong shape" flash. Register
 * your own pairings with [NativeAdShimmer.register].
 *
 * ```kotlin
 * val builder = binding.adContainer.createNativeAdBuilderAutoShimmer(
 *     shimmerHost = binding.shimmerHost,
 *     layoutResId = R.layout.layout_native_ad_small_1a
 * )
 * AdMobManager.getInstance(application).nativeAdLoader.loadAndShow(adUnitId, builder)
 * ```
 *
 * @param shimmerHost An empty container (typically a `FrameLayout`) that the shimmer is inflated into.
 */
@JvmOverloads
fun FrameLayout.createNativeAdBuilderAutoShimmer(
    shimmerHost: ViewGroup,
    @LayoutRes layoutResId: Int,
    theme: NativeAdTheme? = null,
    showMedia: Boolean = true,
    showBody: Boolean = true,
    showCallToAction: Boolean = true,
    iconEnabled: Boolean = true
): NativeAdBuilder {
    val resolvedTheme = theme ?: NativeAdTheme.auto(context)
    val shimmer = NativeAdShimmer.attachTo(shimmerHost, layoutResId, resolvedTheme)
    return createNativeAdBuilder(
        shimmerFrameLayout = shimmer,
        layoutResId = layoutResId,
        theme = resolvedTheme,
        showMedia = showMedia,
        showBody = showBody,
        showCallToAction = showCallToAction,
        iconEnabled = iconEnabled
    )
}
