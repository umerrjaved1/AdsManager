package com.umer_tf.ads.domain.viewmodel

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import com.google.android.gms.ads.nativead.NativeAd
import com.umer_tf.ads.domain.ads.banner.BannerAdType
import com.umer_tf.ads.domain.ads.native_ad.NativeAdBuilder
import com.umer_tf.ads.domain.ads.native_ad.NativeAdPool
import com.umer_tf.ads.domain.analytics.AdLoadFailure
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.core.AdMobManager
import com.umer_tf.ads.domain.utils.AdsLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns every ad on a screen except app open, and exposes each one as a [StateFlow].
 *
 * App open ads are deliberately absent: they are driven by `ProcessLifecycleOwner` inside
 * [com.umer_tf.ads.domain.ads.app_open.AppOpenAdLoader] and belong to the process, not to a screen.
 * Giving them a screen-scoped ViewModel would just be a second, competing owner. Keep using
 * `AdMobManager.appOpenAdLoader` for those.
 *
 * ### Getting one
 * It is an [AndroidViewModel], so no factory is needed and it finds [AdMobManager] itself:
 *
 * ```kotlin
 * private val ads: AdViewModel by viewModels()
 * ```
 *
 * ### Slots
 * Native and banner ads are **keyed**, because a screen really can host several at once. The key
 * defaults to the ad unit id, and the binding helpers default it to the container's view id so two
 * placements sharing one ad unit still get independent state.
 *
 * Interstitial and rewarded are **not** keyed: the underlying loaders each hold exactly one ad, so
 * pretending otherwise would hand two callers the same ad under different names.
 *
 * ### State versus events
 * [nativeState], [bannerState], [interstitialState] and [rewardedState] describe what is currently
 * true and are safe to re-collect after a configuration change. Dismissal and reward are *events* and
 * arrive on [events] exactly once - see [AdEvent].
 *
 * ### Rendering
 * A ViewModel must not hold Views. Methods here that take a container use it for the duration of the
 * call and never retain it; the ads themselves are owned here and released in [onCleared]. The
 * one-line way to wire a slot to its views is `bindNativeAd` / `bindBanner`.
 *
 * All methods must be called from the main thread.
 */
class AdViewModel(application: Application) : AndroidViewModel(application) {

    private val manager: AdMobManager = AdMobManager.getInstance(application)

    // Buffered channel rather than a replay-0 SharedFlow: a dismissal that fires while the screen is
    // STOPPED would be dropped by the latter, and navigation gated on it would stall forever.
    private val _events = Channel<AdEvent>(Channel.BUFFERED)

    /**
     * One-shot outcomes from interstitial and rewarded ads.
     *
     * Intended for a single collector - each event is delivered once, to whoever is listening:
     *
     * ```kotlin
     * lifecycleScope.launch {
     *     repeatOnLifecycle(Lifecycle.State.STARTED) {
     *         ads.events.collect { event ->
     *             if (event is AdEvent.Dismissed) goToNextScreen()
     *         }
     *     }
     * }
     * ```
     */
    val events: Flow<AdEvent> = _events.receiveAsFlow()

    private val nativeSlots = ConcurrentHashMap<String, MutableStateFlow<NativeAdUiState>>()
    private val bannerSlots = ConcurrentHashMap<String, MutableStateFlow<BannerAdUiState>>()

    /** Ads this ViewModel owns, one per native slot. Destroyed in [onCleared]. */
    private val nativeAds = ConcurrentHashMap<String, NativeAd>()

    private val pools = ConcurrentHashMap<String, NativeAdPool>()

    private val _interstitialState = MutableStateFlow<FullScreenAdUiState>(FullScreenAdUiState.Idle)
    private val _rewardedState = MutableStateFlow<FullScreenAdUiState>(FullScreenAdUiState.Idle)

    /** State of the single interstitial the library caches. */
    val interstitialState: StateFlow<FullScreenAdUiState> = _interstitialState.asStateFlow()

    /** State of the single rewarded ad the library caches. */
    val rewardedState: StateFlow<FullScreenAdUiState> = _rewardedState.asStateFlow()

    // ---------------------------------------------------------------------------------------------
    // Native
    // ---------------------------------------------------------------------------------------------

    /** State of the native slot [key]. Slots are created on first observation. */
    fun nativeState(key: String): StateFlow<NativeAdUiState> = nativeSlot(key).asStateFlow()

    /** The ad currently held for [key], or null. Owned by this ViewModel - do not destroy it. */
    fun nativeAd(key: String): NativeAd? = nativeAds[key]

    /**
     * Loads a native ad into slot [key].
     *
     * A slot that already holds an ad is left alone, which is what makes the ad survive a rotation
     * instead of costing a second request. Pass [forceRefresh] to replace it deliberately.
     *
     * @param key Slot identifier. Defaults to [adUnitId]; give two placements sharing one ad unit
     *   distinct keys.
     */
    @MainThread
    @JvmOverloads
    fun loadNative(
        @ValidateAdUnitId adUnitId: String,
        key: String = adUnitId,
        forceRefresh: Boolean = false
    ) {
        val slot = nativeSlot(key)
        if (!forceRefresh) {
            when (slot.value) {
                is NativeAdUiState.Loading -> {
                    AdsLog.d(TAG, "loadNative($key): already in flight")
                    return
                }
                is NativeAdUiState.Loaded -> {
                    AdsLog.d(TAG, "loadNative($key): keeping the ad already held")
                    return
                }
                else -> Unit
            }
        }

        AdsLog.d(TAG, "loadNative($key): requesting $adUnitId")
        slot.value = NativeAdUiState.Loading

        manager.nativeAdLoader.loadDetached(adUnitId) { ad, failure ->
            if (ad != null) {
                replaceNativeAd(key, ad)
                slot.value = NativeAdUiState.Loaded(ad, adUnitId)
            } else {
                slot.value = NativeAdUiState.Failed(
                    adUnitId,
                    failure ?: AdLoadFailure(AdLoadFailure.CODE_LIBRARY, "Native ad load failed")
                )
            }
        }
    }

    /**
     * Renders the ad held for [key] into [builder]'s container.
     *
     * No-op when the slot holds no ad, so it is safe to call speculatively. `bindNativeAd` calls this
     * for you as the state changes.
     */
    @MainThread
    fun renderNative(key: String, builder: NativeAdBuilder) {
        val state = nativeSlot(key).value
        if (state !is NativeAdUiState.Loaded) {
            AdsLog.d(TAG, "renderNative($key): nothing loaded to render")
            return
        }
        manager.nativeAdLoader.render(state.nativeAd, builder, state.adUnitId)
    }

    /** Destroys the ad held for [key] and returns the slot to [NativeAdUiState.Idle]. */
    @MainThread
    fun clearNative(key: String) {
        nativeAds.remove(key)?.destroy()
        nativeSlot(key).value = NativeAdUiState.Idle
    }

    /**
     * A pool of native ads for a list, created once per [adUnitId] and destroyed in [onCleared].
     *
     * `nativeAdLoader` holds a single ad and cannot serve a RecyclerView; the pool remembers which ad
     * each position was given so a recycled row does not re-bill an impression.
     */
    @MainThread
    @JvmOverloads
    fun nativeAdPool(@ValidateAdUnitId adUnitId: String, size: Int = 3): NativeAdPool =
        pools.getOrPut(adUnitId) {
            NativeAdPool(getApplication<Application>(), adUnitId, size).also { it.preload() }
        }

    // ---------------------------------------------------------------------------------------------
    // Banner
    // ---------------------------------------------------------------------------------------------

    /** State of the banner slot [key]. */
    fun bannerState(key: String): StateFlow<BannerAdUiState> = bannerSlot(key).asStateFlow()

    /**
     * Loads and shows a banner into [container], publishing the outcome to [bannerState] for [key].
     *
     * [container] and [shimmer] are used during this call only and are never retained - a banner has
     * to be attached to a live view hierarchy, so unlike a native ad it is re-shown after a
     * configuration change rather than restored. `bindBanner` does that re-show for you.
     *
     * @param shimmer Where the placeholder goes; defaults to [container], so one FrameLayout is
     *   enough. Pass a separate container to put it elsewhere, your own
     *   [com.facebook.shimmer.ShimmerFrameLayout] to keep control of it, or null for none. Managed
     *   entirely by the library in every case.
     */
    @MainThread
    @JvmOverloads
    fun showBanner(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        container: FrameLayout,
        shimmer: ViewGroup? = container,
        type: BannerAdType = BannerAdType.ADAPTIVE,
        key: String = adUnitId
    ) {
        val slot = bannerSlot(key)
        slot.value = BannerAdUiState.Loading
        AdsLog.d(TAG, "showBanner($key): requesting $adUnitId as $type")

        manager.bannerAdLoader.showBanner(activity, adUnitId, container, shimmer, type) { ok, failure ->
            slot.value = if (ok) {
                BannerAdUiState.Loaded(adUnitId, type.adType)
            } else {
                BannerAdUiState.Failed(
                    adUnitId,
                    type.adType,
                    failure ?: AdLoadFailure(AdLoadFailure.CODE_LIBRARY, "Banner load failed")
                )
            }
        }
    }

    /** Forwards the host's `onPause` to every live banner, stopping off-screen refresh. */
    @MainThread
    fun pauseBanners() = manager.bannerAdLoader.pause()

    /** Forwards the host's `onResume` to every live banner. */
    @MainThread
    fun resumeBanners() = manager.bannerAdLoader.resume()

    /** Destroys the banner inside [container] and returns slot [key] to [BannerAdUiState.Idle]. */
    @MainThread
    @JvmOverloads
    fun destroyBanner(container: FrameLayout, key: String? = null) {
        manager.bannerAdLoader.destroyFor(container)
        key?.let { bannerSlot(it).value = BannerAdUiState.Idle }
    }

    // ---------------------------------------------------------------------------------------------
    // Interstitial
    // ---------------------------------------------------------------------------------------------

    /** Preloads the interstitial. A cached, unexpired ad short-circuits to [FullScreenAdUiState.Loaded]. */
    @MainThread
    fun loadInterstitial(@ValidateAdUnitId adUnitId: String) {
        if (_interstitialState.value is FullScreenAdUiState.Loading) return
        if (manager.interstitialAdLoader.isAdLoaded()) {
            _interstitialState.value = FullScreenAdUiState.Loaded(adUnitId)
            return
        }
        _interstitialState.value = FullScreenAdUiState.Loading
        manager.interstitialAdLoader.loadAd(adUnitId) { success ->
            _interstitialState.value = if (success) {
                FullScreenAdUiState.Loaded(adUnitId)
            } else {
                FullScreenAdUiState.Failed(
                    adUnitId,
                    AdLoadFailure(AdLoadFailure.CODE_LIBRARY, "Interstitial failed to load")
                )
            }
        }
    }

    /**
     * Shows the cached interstitial.
     *
     * [AdEvent.Dismissed] is emitted exactly once whatever happens - including when there was no ad to
     * show - so navigation waiting on it cannot stall.
     */
    @MainThread
    fun showInterstitial(activity: Activity, @ValidateAdUnitId adUnitId: String) {
        _interstitialState.value = FullScreenAdUiState.Showing(adUnitId)
        val finish = terminalOnce(INTERSTITIAL_KEY, adUnitId, AdType.INTERSTITIAL, _interstitialState)

        manager.interstitialAdLoader.showAd(
            activity = activity,
            adUnitId = adUnitId,
            onAdDismissed = { finish(null) },
            onAdFailedToShow = { reason ->
                finish(AdLoadFailure(AdLoadFailure.CODE_LIBRARY, reason))
            }
        )
    }

    /** Loads behind the loading dialog and shows as soon as it fills, reusing a cached ad if there is one. */
    @MainThread
    @JvmOverloads
    fun loadAndShowInterstitial(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean = true
    ) {
        _interstitialState.value = FullScreenAdUiState.Loading
        val finish = terminalOnce(INTERSTITIAL_KEY, adUnitId, AdType.INTERSTITIAL, _interstitialState)

        // Remembered so the dismissal that follows a no-fill is distinguishable from a normal close;
        // the loader signals both through onAdDismissed.
        var loadFailure: AdLoadFailure? = null

        manager.interstitialAdLoader.loadAndShowAd(
            activity = activity,
            adUnitId = adUnitId,
            showDialog = showDialog,
            onAdLoaded = { success ->
                if (success) {
                    _interstitialState.value = FullScreenAdUiState.Showing(adUnitId)
                } else {
                    loadFailure = AdLoadFailure(
                        AdLoadFailure.CODE_LIBRARY,
                        "Interstitial failed to load"
                    )
                }
            },
            onAdDismissed = { finish(loadFailure) }
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Rewarded
    // ---------------------------------------------------------------------------------------------

    /** Preloads the rewarded ad. */
    @MainThread
    fun loadRewarded(activity: Activity, @ValidateAdUnitId adUnitId: String) {
        if (_rewardedState.value is FullScreenAdUiState.Loading) return
        if (manager.rewardedAdLoader.isAdLoaded()) {
            _rewardedState.value = FullScreenAdUiState.Loaded(adUnitId)
            return
        }
        _rewardedState.value = FullScreenAdUiState.Loading
        manager.rewardedAdLoader.loadAd(activity, adUnitId) { success ->
            _rewardedState.value = if (success) {
                FullScreenAdUiState.Loaded(adUnitId)
            } else {
                FullScreenAdUiState.Failed(
                    adUnitId,
                    AdLoadFailure(AdLoadFailure.CODE_LIBRARY, "Rewarded ad failed to load")
                )
            }
        }
    }

    /**
     * Shows the cached rewarded ad.
     *
     * Emits [AdEvent.RewardEarned] only when the reward was actually granted, then
     * [AdEvent.Dismissed] exactly once. Grant the reward on the former; navigate on the latter.
     */
    @MainThread
    fun showRewarded(activity: Activity, @ValidateAdUnitId adUnitId: String) {
        _rewardedState.value = FullScreenAdUiState.Showing(adUnitId)
        val finish = terminalOnce(REWARDED_KEY, adUnitId, AdType.REWARDED, _rewardedState)

        manager.rewardedAdLoader.showAd(
            activity = activity,
            onRewardEarned = { earned ->
                if (earned) _events.trySend(AdEvent.RewardEarned(REWARDED_KEY, adUnitId))
            },
            onAdDismissed = { finish(null) }
        )
    }

    /** Loads behind the loading dialog and shows as soon as it fills, reusing a cached ad if there is one. */
    @MainThread
    @JvmOverloads
    fun loadAndShowRewarded(
        activity: Activity,
        @ValidateAdUnitId adUnitId: String,
        showDialog: Boolean = true
    ) {
        _rewardedState.value = FullScreenAdUiState.Loading
        val finish = terminalOnce(REWARDED_KEY, adUnitId, AdType.REWARDED, _rewardedState)

        manager.rewardedAdLoader.loadAndShowAd(
            activity = activity,
            adUnitId = adUnitId,
            showDialog = showDialog,
            onRewardEarned = { earned ->
                if (earned) _events.trySend(AdEvent.RewardEarned(REWARDED_KEY, adUnitId))
            },
            onAdDismissed = { finish(null) }
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun nativeSlot(key: String) =
        nativeSlots.getOrPut(key) { MutableStateFlow(NativeAdUiState.Idle) }

    private fun bannerSlot(key: String) =
        bannerSlots.getOrPut(key) { MutableStateFlow(BannerAdUiState.Idle) }

    private fun replaceNativeAd(key: String, ad: NativeAd) {
        // A forced refresh must not leak the ad it replaces.
        nativeAds.put(key, ad)?.destroy()
    }

    /**
     * Builds the terminal handler for one show attempt: at most one [AdEvent.ShowFailed], then exactly
     * one [AdEvent.Dismissed], then the slot back to [FullScreenAdUiState.Idle] so it can be reloaded.
     *
     * The loaders already promise their callbacks fire once per path, but `showAd` and
     * `loadAndShowAd` reach that promise through different callbacks; funnelling both through here is
     * what keeps the guarantee true at the flow level.
     */
    private fun terminalOnce(
        key: String,
        adUnitId: String,
        adType: AdType,
        slot: MutableStateFlow<FullScreenAdUiState>
    ): (AdLoadFailure?) -> Unit {
        var done = false
        return { failure ->
            if (!done) {
                done = true
                if (failure != null) {
                    AdsLog.d(TAG, "$adType show failed: ${failure.message}")
                    _events.trySend(AdEvent.ShowFailed(key, adUnitId, adType, failure))
                }
                slot.value = FullScreenAdUiState.Idle
                _events.trySend(AdEvent.Dismissed(key, adUnitId, adType))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        AdsLog.d(TAG, "onCleared: releasing ${nativeAds.size} native ad(s) and ${pools.size} pool(s)")
        nativeAds.values.forEach { runCatching { it.destroy() } }
        nativeAds.clear()
        nativeSlots.values.forEach { it.value = NativeAdUiState.Idle }
        pools.values.forEach { runCatching { it.destroy() } }
        pools.clear()
        _events.close()
    }

    companion object {
        private const val TAG = "AdsManager_AdViewModel"

        /** Slot key reported on interstitial events; the loader holds a single interstitial. */
        const val INTERSTITIAL_KEY: String = "interstitial"

        /** Slot key reported on rewarded events; the loader holds a single rewarded ad. */
        const val REWARDED_KEY: String = "rewarded"
    }
}
