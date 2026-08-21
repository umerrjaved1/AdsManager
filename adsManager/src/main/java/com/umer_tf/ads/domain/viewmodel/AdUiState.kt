package com.umer_tf.ads.domain.viewmodel

import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.umer_tf.ads.domain.analytics.AdLoadFailure
import com.umer_tf.ads.domain.analytics.AdType

/**
 * State of one native ad slot.
 *
 * Slots are keyed, so a screen with two native placements observes
 * `nativeState("top")` and `nativeState("bottom")` independently.
 */
sealed interface NativeAdUiState {

    /** Nothing requested yet. */
    data object Idle : NativeAdUiState

    /** A request is in flight; the shimmer placeholder is showing. */
    data object Loading : NativeAdUiState

    /**
     * An ad is cached and ready to render.
     *
     * The [nativeAd] is owned by the `AdViewModel` and destroyed in `onCleared`, so it survives a
     * configuration change - do not call `destroy()` on it yourself.
     */
    data class Loaded(val nativeAd: NativeAd, val adUnitId: String) : NativeAdUiState

    /** The request failed or was refused; the slot should be hidden. */
    data class Failed(val adUnitId: String, val failure: AdLoadFailure) : NativeAdUiState
}

/** State of one banner slot. The `AdView` itself lives in the container, not in the state. */
sealed interface BannerAdUiState {

    data object Idle : BannerAdUiState

    data object Loading : BannerAdUiState

    /** The banner is attached to its container and visible. */
    data class Loaded(val adUnitId: String, val adType: AdType) : BannerAdUiState

    data class Failed(val adUnitId: String, val adType: AdType, val failure: AdLoadFailure) :
        BannerAdUiState
}

/**
 * State of one interstitial or rewarded slot.
 *
 * There is deliberately **no `Dismissed` state**. A terminal outcome held in a `StateFlow` is
 * re-delivered to every new collector, so a screen that navigates on dismissal would navigate again
 * after each rotation. Dismissal and reward arrive once, on [AdViewModel.events]; the slot returns to
 * [Idle] so it can be loaded again.
 */
sealed interface FullScreenAdUiState {

    data object Idle : FullScreenAdUiState

    data object Loading : FullScreenAdUiState

    /** Cached and ready. Show it with `showInterstitial` / `showRewarded`. */
    data class Loaded(val adUnitId: String) : FullScreenAdUiState

    /** The ad is on screen. */
    data class Showing(val adUnitId: String) : FullScreenAdUiState

    data class Failed(val adUnitId: String, val failure: AdLoadFailure) : FullScreenAdUiState
}

/**
 * One-shot outcomes from full-screen ads, delivered on [AdViewModel.events].
 *
 * These are events rather than state precisely because acting on them twice is wrong: navigating on
 * a re-collected `Dismissed` is the bug this split exists to prevent.
 *
 * [Dismissed] preserves the library's core guarantee at the flow level - it is emitted **exactly once
 * for every show attempt**, including one that failed to show or was aborted, so navigation gated on
 * it can never stall.
 */
sealed interface AdEvent {

    /** The slot key the event belongs to. */
    val key: String

    val adUnitId: String

    val adType: AdType

    /**
     * The user earned the reward. Emitted before [Dismissed], and only when the reward was actually
     * granted - a rewarded ad closed early produces [Dismissed] alone.
     */
    data class RewardEarned(
        override val key: String,
        override val adUnitId: String
    ) : AdEvent {
        override val adType: AdType get() = AdType.REWARDED
    }

    /**
     * The ad could not be shown. Always followed by [Dismissed], so a caller that only handles
     * dismissal still gets control back.
     */
    data class ShowFailed(
        override val key: String,
        override val adUnitId: String,
        override val adType: AdType,
        val failure: AdLoadFailure
    ) : AdEvent

    /** Control has returned to the app. Emitted exactly once per show attempt, on every path. */
    data class Dismissed(
        override val key: String,
        override val adUnitId: String,
        override val adType: AdType
    ) : AdEvent
}
