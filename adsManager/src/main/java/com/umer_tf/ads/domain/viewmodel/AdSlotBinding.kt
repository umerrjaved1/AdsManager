package com.umer_tf.ads.domain.viewmodel

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.umer_tf.ads.domain.ads.banner.BannerAdType
import com.umer_tf.ads.domain.ads.native_ad.NativeAdBuilder
import com.umer_tf.ads.domain.ads.native_ad.NativeAdLayout
import com.umer_tf.ads.domain.ads.native_ad.NativeAdShimmer
import com.umer_tf.ads.domain.ads.native_ad.NativeAdTheme
import com.umer_tf.ads.domain.ads.native_ad.stopAndHide
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.core.AdMobManager
import com.umer_tf.ads.domain.utils.AdsLog
import kotlinx.coroutines.launch

private const val TAG = "AdsManager_AdBinding"

/**
 * Slot key for a container, so two placements sharing one ad unit stay independent.
 *
 * A view id is a stable resource constant, so the key survives a configuration change and the ad held
 * for it is reused rather than re-requested.
 */
private fun defaultKey(container: View, adUnitId: String): String =
    if (container.id != View.NO_ID) "slot_${container.id}" else adUnitId

/**
 * Wires a native ad slot to its views: loads it, drives the shimmer, renders it, and hides the slot on
 * failure - for the lifetime of [owner].
 *
 * This is the whole integration for a native ad. The caller supplies **one container** and a shape;
 * the layout, the matching shimmer, the load, the state collection and the teardown are all derived:
 *
 * ```xml
 * <FrameLayout android:id="@+id/adSlot"
 *     android:layout_width="match_parent" android:layout_height="wrap_content" />
 * ```
 * ```kotlin
 * ads.bindNativeAd(this, AD_UNIT, "4a", binding.adSlot)
 * ```
 *
 * The shimmer is inflated into that container and replaced by the ad when it arrives, so there is no
 * second view whose visibility has to be kept in sync - the class of bug where a loaded ad sits under
 * a leftover placeholder's reserved height cannot happen. Pass [shimmerHost] only when the
 * placeholder genuinely belongs elsewhere in the layout.
 *
 * From a Fragment, pass `viewLifecycleOwner` - not the Fragment - so the binding dies with the view.
 *
 * @param layout A [NativeAdLayout] code such as `"1a"`, `"4a"`, `"v2"` or `"large"`. An unrecognised
 *   code logs a warning and falls back to [NativeAdLayout.DEFAULT] rather than showing nothing.
 * @param container Holds the shimmer, then the ad.
 * @param shimmerHost Optional separate container for the placeholder. Null (the default) puts it
 *   inside [container].
 * @param key Slot identifier; defaults to [container]'s view id.
 * @return The builder that was constructed, for callers who want to keep configuring it.
 */
@MainThread
@JvmOverloads
fun AdViewModel.bindNativeAd(
    owner: LifecycleOwner,
    @ValidateAdUnitId adUnitId: String,
    layout: String,
    container: FrameLayout,
    shimmerHost: ViewGroup? = null,
    theme: NativeAdTheme? = null,
    key: String = defaultKey(container, adUnitId),
    showMedia: Boolean = true,
    showBody: Boolean = true,
    showCallToAction: Boolean = true,
    iconEnabled: Boolean = true
): NativeAdBuilder = bindNativeAd(
    owner = owner,
    adUnitId = adUnitId,
    layout = NativeAdLayout.fromOrDefault(layout),
    container = container,
    shimmerHost = shimmerHost,
    theme = theme,
    key = key,
    showMedia = showMedia,
    showBody = showBody,
    showCallToAction = showCallToAction,
    iconEnabled = iconEnabled
)

/** [bindNativeAd] with the layout given as an enum constant instead of a code. */
@MainThread
@JvmOverloads
fun AdViewModel.bindNativeAd(
    owner: LifecycleOwner,
    @ValidateAdUnitId adUnitId: String,
    layout: NativeAdLayout,
    container: FrameLayout,
    shimmerHost: ViewGroup? = null,
    theme: NativeAdTheme? = null,
    key: String = defaultKey(container, adUnitId),
    showMedia: Boolean = true,
    showBody: Boolean = true,
    showCallToAction: Boolean = true,
    iconEnabled: Boolean = true
): NativeAdBuilder {
    val host = shimmerHost ?: container
    // When the placeholder lives elsewhere, that wrapper has to be collapsed separately or its
    // height, padding and margins stay behind as a gap. When it lives in the container, rendering
    // the ad replaces it and there is nothing extra to hide.
    val separateHost = host !== container

    // A paying user is never getting an ad here, and premium is stable rather than pending like
    // consent - so skip the placeholder entirely instead of flashing one that resolves to an empty
    // slot a moment later.
    if (AdMobManager.isPremium) {
        AdsLog.d(TAG, "bindNativeAd($key): premium user, slot hidden without a placeholder")
        if (separateHost) host.visibility = View.GONE
        container.removeAllViews()
        container.visibility = View.GONE
        return NativeAdBuilder.Builder(layout.layoutResId, container, null).build()
    }

    val resolvedTheme = theme ?: NativeAdTheme.auto(container.context)
    val shimmer = NativeAdShimmer.attachTo(host, layout.layoutResId, resolvedTheme)
    container.visibility = if (separateHost) View.GONE else View.VISIBLE

    val builder = NativeAdBuilder.Builder(layout.layoutResId, container, shimmer)
        .setShowMedia(showMedia)
        .setShowBody(showBody)
        .setShowCallToAction(showCallToAction)
        .setIconEnabled(iconEnabled)
        .setTheme(resolvedTheme)
        .build()

    // repeatOnLifecycle re-collects on every STARTED, and the slot replays its current value - so
    // without this guard, returning to the screen would re-register the same ad and risk billing a
    // second impression for one fill.
    var rendered: NativeAd? = null

    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            nativeState(key).collect { state ->
                when (state) {
                    is NativeAdUiState.Idle -> loadNative(adUnitId, key)

                    is NativeAdUiState.Loading -> {
                        host.visibility = View.VISIBLE
                        container.visibility = if (separateHost) View.GONE else View.VISIBLE
                        builder.shimmerFrameLayout?.visibility = View.VISIBLE
                        builder.shimmerFrameLayout?.startShimmer()
                    }

                    is NativeAdUiState.Loaded -> {
                        if (rendered !== state.nativeAd) {
                            rendered = state.nativeAd
                            // Rendering clears the container, which drops the shimmer with it when
                            // the two are the same view.
                            renderNative(key, builder)
                        }
                        if (separateHost) host.visibility = View.GONE
                    }

                    is NativeAdUiState.Failed -> {
                        // The code matters as much as the message: -1 means the library refused the
                        // request before the SDK saw it, 2 is a network error, 3 is no fill. Logging
                        // the message alone makes those look like the same problem.
                        AdsLog.d(
                            TAG,
                            "bindNativeAd($key): hiding slot, code=${state.failure.code} " +
                                "${state.failure.message}"
                        )
                        builder.shimmerFrameLayout.stopAndHide()
                        if (separateHost) host.visibility = View.GONE
                        container.visibility = View.GONE
                    }
                }
            }
        }
    }

    return builder
}

/**
 * Wires a banner slot to its container for the lifetime of [owner], including the pause/resume/destroy
 * forwarding that a banner needs.
 *
 * ```xml
 * <FrameLayout android:id="@+id/bannerSlot"
 *     android:layout_width="match_parent" android:layout_height="wrap_content" />
 * ```
 * ```kotlin
 * ads.bindBanner(this, this, BANNER_UNIT, binding.bannerSlot)
 * ```
 *
 * One container, same as a native slot: the placeholder is inflated into it and hidden once the ad
 * arrives. The observer this binds is what destroys the
 * [com.google.android.libraries.ads.mobile.sdk.banner.AdView] on teardown; leaving it attached keeps
 * its Activity alive. (Off-screen refresh is no longer part of it - the Next-Gen `AdView` suspends
 * its own refresh while detached or hidden, which is why `pauseBanners`/`resumeBanners` are now
 * no-ops.)
 *
 * The banner is requested once per binding. A configuration change destroys the old `AdView` along
 * with its view hierarchy, so the new [owner] correctly requests a fresh one.
 *
 * @param shimmer Where the placeholder goes. Defaults to [container]. Pass a separate container to
 *   put it elsewhere, your own [ShimmerFrameLayout] to keep control of it, or null for no
 *   placeholder at all.
 */
@MainThread
@JvmOverloads
fun AdViewModel.bindBanner(
    activity: Activity,
    owner: LifecycleOwner,
    @ValidateAdUnitId adUnitId: String,
    container: FrameLayout,
    shimmer: ViewGroup? = container,
    type: BannerAdType = BannerAdType.ADAPTIVE,
    key: String = defaultKey(container, adUnitId)
) {
    owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        private var requested = false

        override fun onStart(owner: LifecycleOwner) {
            if (requested) return
            requested = true
            showBanner(activity, adUnitId, container, shimmer, type, key)
        }

        override fun onResume(owner: LifecycleOwner) {
            resumeBanners()
        }

        override fun onPause(owner: LifecycleOwner) {
            pauseBanners()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            destroyBanner(container, key)
            owner.lifecycle.removeObserver(this)
        }
    })
}
