package com.umer_tf.ads.domain.ads.native_ad

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.appcompat.widget.AppCompatButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.VideoController
import com.google.android.libraries.ads.mobile.sdk.common.VideoOptions
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.umer_tf.ads.R
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListenerNative
import com.umer_tf.ads.domain.analytics.AdLoadFailure
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.core.AdSlotState
import com.umer_tf.ads.domain.diagnostics.AdEvent
import com.umer_tf.ads.domain.diagnostics.AdEventLog
import com.umer_tf.ads.domain.diagnostics.AdFormat
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_CLICKED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_SHOWN
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import com.umer_tf.ads.domain.utils.onMainThread
import com.umer_tf.ads.domain.utils.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.graphics.toColorInt

class NativeAd(
    private val context: Context
): INativeAdLoader {

    private val TAG = "NativeAd"

    private class CachedNative(val ad: NativeAd, val loadedAtElapsed: Long)

    /**
     * Preloaded ads keyed by their ad unit.
     *
     * A single shared slot meant loading unit B destroyed a fill already paid for on unit A -
     * exactly the case a startup chain hits when it warms the next screen while the current one
     * still holds an unshown ad. The map is bounded so a host cannot accumulate creatives.
     */
    private val cache = LinkedHashMap<String, CachedNative>()

    /** Units with a request in flight, so a second caller joins instead of duplicating it. */
    private val inFlight = mutableSetOf<String>()

    /** Callbacks parked on an in-flight request, keyed by unit. */
    private val waiters = mutableMapOf<String, MutableList<(NativeAd?) -> Unit>>()

    /**
     * Ad currently rendered in each frame, so replacing a frame's content can destroy the ad it
     * held. Keyed weakly by the host frame: only the ad being replaced right now is destroyed,
     * never one still on screen elsewhere.
     */
    private val adsByFrame = java.util.WeakHashMap<android.widget.FrameLayout, NativeAd>()

    private var exitNativeAd: NativeAd? = null

    /**
     * Whether native video creatives start muted. **True by default.**
     *
     * A native ad is an inline element of someone else's screen, so audio that starts on its own is
     * the one thing a user cannot anticipate or avoid - and it is the behaviour Google's own native
     * guidance asks for. The mute state is a property of the *request*, not of the rendered view, so
     * it has to be set here rather than at bind time; [NativeAdPool] uses the same default.
     *
     * Set it to false only for a placement whose whole purpose is a video the user chose to watch.
     */
    @JvmField
    var startVideoMuted: Boolean = true

    /** The video options every native request in this loader is built with. */
    private fun videoOptions(): VideoOptions =
        VideoOptions.Builder().setStartMuted(startVideoMuted).build()

    private fun emit(adUnitId: String, event: AdEvent, reason: String? = null) {
        val state = when {
            inFlight.contains(adUnitId) -> AdSlotState.LOADING
            cache.containsKey(adUnitId) -> AdSlotState.READY
            else -> AdSlotState.IDLE
        }
        AdEventLog.emit(AdFormat.NATIVE, adUnitId, event, state, reason)
    }

    /** Cached ad for [adUnitId], dropping it first if it has aged past [NATIVE_EXPIRY_MS]. */
    private fun cachedAdFor(adUnitId: String): NativeAd? {
        val entry = cache[adUnitId] ?: return null
        if (SystemClock.elapsedRealtime() - entry.loadedAtElapsed > NATIVE_EXPIRY_MS) {
            Log.d(TAG, "Monetization :- cached native for $adUnitId expired")
            emit(adUnitId, AdEvent.AD_EXPIRED)
            cache.remove(adUnitId)
            entry.ad.destroy()
            return null
        }
        return entry.ad
    }

    /**
     * Takes the cached ad for [adUnitId] and empties its slot. A preloaded ad is good for exactly
     * one display: leaving it cached made every later placement re-render the same object, which
     * produces no new request and no new impression.
     */
    private fun consumeCachedAd(adUnitId: String): NativeAd? {
        val ad = cachedAdFor(adUnitId) ?: return null
        cache.remove(adUnitId)
        return ad
    }

    private fun putInCache(adUnitId: String, ad: NativeAd) {
        cache.remove(adUnitId)?.ad?.takeIf { it !== ad }?.destroy()
        cache[adUnitId] = CachedNative(ad, SystemClock.elapsedRealtime())
        while (cache.size > MAX_CACHED_UNITS) {
            val oldest = cache.keys.first()
            Log.d(TAG, "Monetization :- evicting cached native for $oldest (cache full)")
            emit(oldest, AdEvent.AD_DESTROYED, "evicted, cache limit $MAX_CACHED_UNITS")
            cache.remove(oldest)?.ad?.destroy()
        }
    }

    /** Releases everyone parked on [adUnitId]'s request. */
    private fun settleWaiters(adUnitId: String, ad: NativeAd?) {
        val pending = waiters.remove(adUnitId).orEmpty()
        pending.forEach { it(ad) }
    }

    /** True while a request for [adUnitId] is in flight; a second caller must join it. */
    fun isLoading(adUnitId: String): Boolean = inFlight.contains(adUnitId)

    /** Current slot state for [adUnitId], for diagnostics. */
    fun stateOf(adUnitId: String): AdSlotState = when {
        inFlight.contains(adUnitId) -> AdSlotState.LOADING
        cachedAdFor(adUnitId) != null -> AdSlotState.READY
        else -> AdSlotState.IDLE
    }

    /** Renders [ad] into the builder's frame, destroying whatever that frame held before. */
    private fun bindToFrame(ad: NativeAd, builder: NativeAdBuilder) {
        val adView = LayoutInflater.from(context).inflate(
            if (builder.layout == 0) R.layout.admob_small_native_media else builder.layout,
            null
        ) as NativeAdView

        populateNativeAdView(ad, adView, builder)

        builder.frameLayout?.let { frame ->
            adsByFrame.put(frame, ad)?.takeIf { it !== ad }?.destroy()
            frame.removeAllViews()
            frame.addView(adView)
            frame.visibility = View.VISIBLE
        }
    }

    /**
     * Loads and shows a native ad.
     * @param builder The builder for the native ad.
     * @param onSuccessListener The listener for the success event.
     */
    @MainThread
    override fun loadAndShow(
        @ValidateAdUnitId adUnitId: String,
        builder: NativeAdBuilder,
        activity: Activity,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            onSuccessListener?.onSuccess(false)
            return
        }

        // Consume a same-unit preload even while that request is still marked in-flight.
        // onNativeAdLoaded caches the object before onAdLoadingCompleted clears inFlight, so
        // checking join first parked on a fill that was already READY and then skipped it.
        consumeCachedAd(adUnitId)?.let { cached ->
            Log.d(TAG, "Monetization :- loadAndShow using preloaded ad for $adUnitId")
            emit(adUnitId, AdEvent.REQUEST_SKIPPED_CACHED)
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            bindToFrame(cached, builder)
            // Impression, click and revenue reporting were attached to this ad when it loaded - see
            // attachAdEventCallback. They belong to the ad now, not to the loader, so a preloaded ad
            // arrives here already reporting and must not be re-wired.
            onSuccessListener?.onSuccess(true)
            return
        }

        // A request for this unit is already running. Joining it is the whole point: two AdMob
        // requests can only ever produce one impression, so the second is a matched ad nobody
        // will ever see. The join is deliberately not optional - the host flag that used to skip
        // it is how one app ended up paying for two Language natives per display.
        if (inFlight.contains(adUnitId)) {
            parkUntilRequestSettles(adUnitId, builder, activity, onSuccessListener)
            return
        }

        startLoadAndShow(adUnitId, builder, activity, onSuccessListener)
    }

    /**
     * Waits for the in-flight request on [adUnitId] instead of firing a duplicate.
     *
     * When it settles, exactly one outcome applies:
     *  - the request was a preload and this caller wins the race to consume it → render it;
     *  - the ad went into someone else's frame, or another waiter claimed it → this caller needs
     *    an ad of its own, because one [NativeAd] cannot be rendered into two views.
     *
     * In the second case it re-checks [inFlight] rather than requesting unconditionally. Waiters
     * are settled in sequence, so the first one to reach here starts the next request and every
     * other waiter parks on *that* one. Requesting directly meant N joined screens produced N
     * simultaneous requests - the exact duplication this join exists to prevent, just deferred by
     * one round trip.
     */
    @MainThread
    private fun parkUntilRequestSettles(
        adUnitId: String,
        builder: NativeAdBuilder,
        activity: Activity,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        Log.d(TAG, "Monetization :- loadAndShow joining in-flight request for $adUnitId")
        emit(adUnitId, AdEvent.REQUEST_JOINED)
        builder.frameLayout?.visibility = View.GONE
        builder.shimmerFrameLayout?.startShimmer()
        builder.shimmerFrameLayout?.visibility = View.VISIBLE

        waiters.getOrPut(adUnitId) { mutableListOf() }.add { joined ->
            if (activity.isFinishing || activity.isDestroyed) {
                builder.shimmerFrameLayout?.stopShimmer()
                builder.shimmerFrameLayout?.visibility = View.GONE
                onSuccessListener?.onSuccess(false)
                return@add
            }
            // Claim by consuming rather than trusting the handed-out reference: only the first
            // waiter can win, so the rest never render the same object into a second frame.
            val mine = if (joined != null) consumeCachedAd(adUnitId) else null
            when {
                mine != null -> {
                    builder.shimmerFrameLayout?.stopShimmer()
                    builder.shimmerFrameLayout?.visibility = View.GONE
                    bindToFrame(mine, builder)
                    // Already reporting - the event callback was attached when the ad loaded.
                    onSuccessListener?.onSuccess(true)
                }
                // Another waiter already started the next request — queue behind it.
                inFlight.contains(adUnitId) ->
                    parkUntilRequestSettles(adUnitId, builder, activity, onSuccessListener)

                else -> startLoadAndShow(adUnitId, builder, activity, onSuccessListener)
            }
        }
    }

    /**
     * Wires impression, click and revenue reporting for one loaded ad.
     *
     * In the legacy SDK these were three separate hooks: `AdListener.onAdImpression` and
     * `onAdClicked` on the *loader*, plus `setOnPaidEventListener` on the ad. The Next-Gen SDK has
     * one `NativeAdEventCallback` per ad, so all three are set together, at load time, on the ad
     * they belong to.
     *
     * Attaching at load rather than at bind is what keeps preloaded ads reportable: a cached ad is
     * rendered later by [showLoadedAd] or a joined [loadAndShow], and a loader-level listener has no
     * equivalent here to catch its impression.
     *
     * @param revenueLabel Ad-format label sent to AppsFlyer revenue reporting.
     * @param analyticsLabel Ad-format label sent to Firebase for impression/click counting.
     */
    private fun attachAdEventCallback(
        ad: NativeAd,
        adUnitId: String,
        revenueLabel: String,
        analyticsLabel: String
    ) {
        ad.adEventCallback = object : NativeAdEventCallback {
            override fun onAdImpression() = onMainThread {
                emit(adUnitId, AdEvent.SHOW_STARTED)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_SHOWN, analyticsLabel)
            }

            override fun onAdClicked() = onMainThread {
                Log.d(TAG, "Monetization :- onAdClicked() $analyticsLabel")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_CLICKED, analyticsLabel)
            }

            override fun onAdPaid(value: AdValue) {
                // Analytics only, and already off the main thread, so no hop.
                CoroutineScope(Dispatchers.IO).launch {
                    AdsAnalytics.logAppsFlyerRevenue(
                        adUnitId,
                        revenueLabel,
                        value,
                        context.applicationContext
                    )
                }
            }
        }
    }

    /**
     * Requests a native for [adUnitId] and renders it into the builder's frame.
     *
     * Separate from [loadAndShow] so a caller that joined an in-flight request and came back
     * empty-handed can start a real one without re-entering the join check, which would let two
     * callers bounce off each other indefinitely.
     */
    @MainThread
    private fun startLoadAndShow(
        adUnitId: String,
        builder: NativeAdBuilder,
        activity: Activity,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        builder.frameLayout?.visibility = View.GONE
        builder.shimmerFrameLayout?.startShimmer()
        builder.shimmerFrameLayout?.visibility = View.VISIBLE

        inFlight.add(adUnitId)
        emit(adUnitId, AdEvent.REQUEST_STARTED)

        var cachedBecauseActivityDied = false

        // Video options moved from NativeAdOptions onto the request itself, and the request now
        // declares which native formats it will accept.
        val request = NativeAdRequest.Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))
            .setVideoOptions(videoOptions())
            .build()

        // NativeAdLoader is a static loader - there is no AdLoader.Builder to configure and no
        // Context argument. The old two-phase shape survives intact: onNativeAdLoaded replaces
        // forNativeAd (the ad itself), onAdLoadingCompleted replaces AdListener.onAdLoaded (the
        // request finishing). Impression and click moved onto the ad's own event callback.
        NativeAdLoader.load(request, object : NativeAdLoaderCallback {
            // Every body here inflates views, drives the shimmer and calls back into the host, and
            // Next-Gen delivers them on a background thread.
            override fun onNativeAdLoaded(nativeAd: NativeAd) = onMainThread {
                attachAdEventCallback(nativeAd, adUnitId, "Native", "NativeAd")
                // Language (and other short-lived screens) can finish in 1–3s. Binding into a
                // dying view produces a match with no impression; keep the fill for the next screen.
                if (activity.isFinishing || activity.isDestroyed) {
                    Log.d(TAG, "Monetization :- loadAndShow fill after Activity died — caching $adUnitId")
                    putInCache(adUnitId, nativeAd)
                    cachedBecauseActivityDied = true
                } else {
                    bindToFrame(nativeAd, builder)
                }
            }

            override fun onAdLoadingCompleted() = onMainThread {
                Log.d("AdmobNative", "Monetization :- onAdLoaded (loadAndShow): Admob ${activity.javaClass.simpleName}")
                inFlight.remove(adUnitId)
                emit(adUnitId, AdEvent.LOAD_SUCCESS)
                builder.shimmerFrameLayout?.stopShimmer()
                builder.shimmerFrameLayout?.visibility = View.GONE
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "NativeAd")
                if (cachedBecauseActivityDied) {
                    emit(adUnitId, AdEvent.READY)
                    onSuccessListener?.onSuccess(false)
                    settleWaiters(adUnitId, cachedAdFor(adUnitId))
                    return@onMainThread
                }
                onSuccessListener?.onSuccess(true)
                // This ad went into a frame, not the cache, so joiners get null and start their
                // own request instead of rendering the same object into a second view.
                settleWaiters(adUnitId, null)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                Log.d("AdmobNative", "Monetization :- onAdFailedToLoad() " + adError.message)
                inFlight.remove(adUnitId)
                emit(adUnitId, AdEvent.LOAD_FAILURE, adError.message)
                // No-fill must clear the placeholder: leaving it running is what made empty
                // shimmers pulse forever on screens whose ad never arrived.
                builder.shimmerFrameLayout?.stopShimmer()
                builder.shimmerFrameLayout?.visibility = View.GONE
                builder.frameLayout?.visibility = View.GONE
                onSuccessListener?.onSuccess(false)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "NativeAd")
                settleWaiters(adUnitId, null)
            }
        })
    }

    /**
     * Loads a native ad.
     * @param adUnitId The ad unit ID for the native ad.
     * @param onSuccessListener The listener for the success event.
     */
    @MainThread
    override fun loadAd(
        @ValidateAdUnitId adUnitId: String,
        activity: Activity,
        onSuccessListener: OnSuccessListenerNative<Boolean,NativeAd?>?,

    ) {
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            onSuccessListener?.onSuccess(false,null)
            return
        }
        // Only a cached ad for the SAME unit can satisfy this request. Returning another unit's
        // ad meant the requested unit was never actually loaded.
        cachedAdFor(adUnitId)?.let { cached ->
            emit(adUnitId, AdEvent.REQUEST_SKIPPED_CACHED)
            onSuccessListener?.onSuccess(true, cached)
            return
        }
        // Join rather than duplicate. Other units' cached ads are left alone - they belong to
        // placements that have not shown yet, and destroying them here threw away paid fills.
        if (inFlight.contains(adUnitId)) {
            emit(adUnitId, AdEvent.REQUEST_JOINED)
            waiters.getOrPut(adUnitId) { mutableListOf() }.add { joined ->
                onSuccessListener?.onSuccess(joined != null, joined)
            }
            return
        }

        inFlight.add(adUnitId)
        emit(adUnitId, AdEvent.REQUEST_STARTED)

        val request = NativeAdRequest.Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))
            .setVideoOptions(videoOptions())
            .build()

        NativeAdLoader.load(request, object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(nativeAd: NativeAd) = onMainThread {
                // Set here, at load time, rather than when this ad is eventually rendered: the
                // impression callback now lives on the ad, so a preloaded native would otherwise
                // report `filled N shown 0` no matter how it performed.
                attachAdEventCallback(nativeAd, adUnitId, "Native", "NativeAd")
                putInCache(adUnitId, nativeAd)
                onSuccessListener?.onSuccess(true, nativeAd)
            }

            override fun onAdLoadingCompleted() = onMainThread {
                Log.d("AdmobNative", "Monetization :- onAdLoaded (loadAd): Admob ${activity.javaClass.simpleName}")
                inFlight.remove(adUnitId)
                // LOAD_SUCCESS is what the funnel counts as a fill. Emitting only READY here
                // meant preloaded natives were invisible to it, so `filled` had to be derived
                // by summing two events - which double-counted every path that emits both.
                emit(adUnitId, AdEvent.LOAD_SUCCESS)
                emit(adUnitId, AdEvent.READY)
                context.showToast("Native ad loaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "NativeAd")
                // Preload: the ad is in the cache, so joiners may render it themselves.
                settleWaiters(adUnitId, cachedAdFor(adUnitId))
            }

            override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                Log.d("AdmobNative", "Monetization :- onAdFailedToLoad() " + adError.message)
                inFlight.remove(adUnitId)
                emit(adUnitId, AdEvent.LOAD_FAILURE, adError.message)
                context.showToast("Failed to load native ad")
                onSuccessListener?.onSuccess(false,null)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "NativeAd")
                settleWaiters(adUnitId, null)
            }
        })
    }

    /**
     * Shows the loaded native ad.
     * @param builder The builder for the native ad.
     * @param adUnitId The ad unit ID for Appsflyer.
     */
    @MainThread
    override fun showLoadedAd(builder: NativeAdBuilder, @ValidateAdUnitId adUnitId: String,activity: Activity) {
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            return
        }
        // Consume: one preloaded ad displays once. Keeping it cached made every later placement
        // re-render this same object - no request, no impression, wrong unit in the report.
        val ad = consumeCachedAd(adUnitId)
        if (ad == null) {
            Log.d("AdmobNative", "Monetization :- showLoadedAd: no preloaded ad for $adUnitId")
            emit(adUnitId, AdEvent.SHOW_REJECTED_NOT_READY)
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            return
        }
        builder.shimmerFrameLayout?.stopShimmer()
        builder.shimmerFrameLayout?.visibility = View.GONE
        Log.d("AdmobNative", "Monetization :- (show loaded) ${activity.javaClass.simpleName}")
        emit(adUnitId, AdEvent.SHOW_REQUESTED, activity.javaClass.simpleName)
        bindToFrame(ad, builder)
        // Already reporting - the event callback was attached when the ad loaded.
    }

    override fun destroy() {
        cache.keys.toList().forEach { emit(it, AdEvent.AD_DESTROYED, "loader destroyed") }
        cache.values.forEach { it.ad.destroy() }
        cache.clear()
        inFlight.clear()
        waiters.clear()
        adsByFrame.values.forEach { it.destroy() }
        adsByFrame.clear()
        exitNativeAd?.destroy()
        exitNativeAd = null
    }

    // Iterates a copy: cachedAdFor() evicts expired entries, which would otherwise be a
    // structural modification of the map being iterated.
    override fun isAdLoaded(): Boolean = cache.keys.toList().any { cachedAdFor(it) != null }

    /** True when a preloaded ad for exactly [adUnitId] is cached and unused. */
    fun isAdLoaded(adUnitId: String): Boolean = cachedAdFor(adUnitId) != null

    /**
     * Requests a native ad and hands it to [onResult] **without caching it**.
     *
     * This is what [com.umer_tf.ads.domain.viewmodel.AdViewModel] loads through, and the reason it
     * exists is ownership. Every other entry point routes through the loader's single per-unit
     * cache, which the loader itself consumes, evicts and destroys - so a ViewModel holding one of
     * those ads across a rotation could find it destroyed underneath it, and destroying it in
     * `onCleared` would take out an ad the loader had already handed to someone else. A detached ad
     * belongs solely to the caller, who must destroy it.
     *
     * The ad already reports impressions, clicks and revenue when it arrives: the event callback is
     * attached here, at load time, because in the Next-Gen SDK those callbacks live on the ad rather
     * than on the loader.
     *
     * @param onResult Invoked exactly once on the main thread, with either an ad or a failure.
     */
    @MainThread
    fun loadDetached(
        @ValidateAdUnitId adUnitId: String,
        onResult: (NativeAd?, AdLoadFailure?) -> Unit
    ) {
        // A false return means "skip": strictMode has already thrown if the host wanted a malformed
        // id to be fatal. Here it comes back through onResult so the caller can settle its state.
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
            val reason = "Malformed ad unit id \"$adUnitId\""
            Log.e(TAG, "Monetization :- loadDetached refused: $reason")
            onResult(null, AdLoadFailure(AdLoadFailure.CODE_LIBRARY, reason))
            return
        }
        if (!shouldShowAd(context)) {
            onResult(
                null,
                AdLoadFailure(
                    AdLoadFailure.CODE_LIBRARY,
                    "Request blocked: premium, offline or consent-gated"
                )
            )
            return
        }

        emit(adUnitId, AdEvent.REQUEST_STARTED)

        val request = NativeAdRequest.Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))
            .setVideoOptions(videoOptions())
            .build()

        var delivered: NativeAd? = null
        NativeAdLoader.load(request, object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(nativeAd: NativeAd) = onMainThread {
                attachAdEventCallback(nativeAd, adUnitId, "Native", "NativeAd")
                delivered = nativeAd
            }

            override fun onAdLoadingCompleted() = onMainThread {
                emit(adUnitId, AdEvent.LOAD_SUCCESS)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "NativeAd")
                val ad = delivered
                if (ad != null) {
                    onResult(ad, null)
                } else {
                    // The request finished without ever producing an ad object - treat it as a
                    // no-fill rather than leaving the caller's slot stuck in Loading forever.
                    onResult(
                        null,
                        AdLoadFailure(AdLoadFailure.CODE_LIBRARY, "Request completed with no ad")
                    )
                }
            }

            override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                Log.d(TAG, "Monetization :- loadDetached onAdFailedToLoad: ${adError.message}")
                emit(adUnitId, AdEvent.LOAD_FAILURE, adError.message)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "NativeAd")
                onResult(null, AdLoadFailure(adError.code.value, adError.message))
            }
        })
    }

    /**
     * Renders a caller-owned [nativeAd] into [builder]'s frame.
     *
     * The counterpart to [loadDetached]. Unlike [showLoadedAd] it touches neither the cache nor the
     * per-frame ownership map, so re-rendering the same ad after a configuration change is free and
     * the loader never destroys an ad it does not own.
     */
    @MainThread
    fun render(nativeAd: NativeAd, builder: NativeAdBuilder, @ValidateAdUnitId adUnitId: String) {
        builder.shimmerFrameLayout?.stopShimmer()
        builder.shimmerFrameLayout?.visibility = View.GONE

        val adView = LayoutInflater.from(context).inflate(
            if (builder.layout == 0) R.layout.admob_small_native_media else builder.layout,
            null
        ) as NativeAdView

        populateNativeAdView(nativeAd, adView, builder)

        builder.frameLayout?.let { frame ->
            frame.removeAllViews()
            frame.addView(adView)
            frame.visibility = View.VISIBLE
        }
        emit(adUnitId, AdEvent.SHOW_REQUESTED, "detached")
    }


    /**
     * Populates the native ad view.
     * @param nativeAd The native ad.
     * @param adView The native ad view.
     * @param builder The builder for the native ad.
     */
    @MainThread
    private fun populateNativeAdView(
        nativeAd: NativeAd,
        adView: NativeAdView,
        builder: NativeAdBuilder
    ) {
        try {

            val clAdBg = adView.findViewById<ConstraintLayout>(R.id.clAd)
            val btnCTA = adView.findViewById<AppCompatButton>(R.id.ad_call_to_action)
            val adTitle = adView.findViewById<TextView>(R.id.ad_headline)
            val adBody = adView.findViewById<TextView>(R.id.ad_body)
            val icon = adView.findViewById<ImageView>(R.id.ad_app_icon)
            val price = adView.findViewById<TextView>(R.id.ad_price)
            val adMedia = adView.findViewById<MediaView>(R.id.ad_media)
            val adRating = adView.findViewById<RatingBar>(R.id.ad_stars)
            val adStore = adView.findViewById<TextView>(R.id.ad_store)
            val adAdvertiser = adView.findViewById<TextView>(R.id.ad_advertiser)

            builder.adBgColor?.let { adBgColor ->
                clAdBg?.background = GradientDrawable().also { it ->
                    if (builder.showBgStroke) {
                        it.shape = GradientDrawable.RECTANGLE
                        val color = builder.strokeColor?.toColorInt() ?: Color.GRAY
                        val width = builder.strokeWidth ?: 1
                        it.setStroke(width, color)
                    }
                    // Only a NativeAdTheme carries a card radius; a builder configured colour by
                    // colour has no equivalent setting, so it keeps the layout's square corners.
                    builder.theme?.let { theme ->
                        it.cornerRadius = theme.adCornerRadius.toFloat()
                    }
                    it.setColor(adBgColor.toColorInt())
                }
            }

            // The "Ad" attribution label. AdMob policy requires it to stay visible, so it is only
            // ever recoloured, never hidden - and it needs recolouring, or a dark theme leaves the
            // default dark-grey badge unreadable on a dark card.
            builder.theme?.let { theme ->
                adView.findViewById<TextView>(R.id.ad_badge)?.let { badge ->
                    badge.setTextColor(theme.badgeTextColor.toColorInt())
                    (badge.background as? GradientDrawable)?.setStroke(
                        1,
                        theme.badgeStrokeColor.toColorInt()
                    )
                }
            }

            builder.ctaBgColor?.let { ctaBgColor ->
                btnCTA?.background = GradientDrawable().also {
                    it.shape = GradientDrawable.RECTANGLE
                    it.cornerRadius = builder.ctaRadius.toFloat()
                    it.setColor(ctaBgColor.toColorInt())
                    it.setTint(ctaBgColor.toColorInt())
                }
            }

            builder.ctaTextColor?.let { ctaTextColor ->
                btnCTA?.setTextColor(ctaTextColor.toColorInt())
            }

            builder.adTitleColor?.let { adTitleColor ->
                adTitle?.setTextColor(adTitleColor.toColorInt())
            }

            builder.adBodyColor?.let { adBodyColor ->
                adBody?.setTextColor(adBodyColor.toColorInt())
            }

        // NativeAdView.mediaView is read-only now - the MediaView is handed to registerNativeAd
        // instead (see the end of this block), so this branch only decides visibility and content.
        var mediaViewToRegister: MediaView? = null
        if (builder.showMedia) {
            adMedia?.let {
                it.visibility = View.VISIBLE
                val adMediaContainer = adView.findViewById<ConstraintLayout>(R.id.constraintLayoutMedia)
                adMediaContainer?.visibility = View.VISIBLE
                it.mediaContent = nativeAd.mediaContent
                mediaViewToRegister = it
            }
        } else {
            adMedia?.visibility = View.GONE
            val adMediaContainer = adView.findViewById<ConstraintLayout>(R.id.constraintLayoutMedia)
            adMediaContainer?.visibility = View.GONE
        }

        adView.apply {
            headlineView = adTitle
            bodyView = adBody
            callToActionView = btnCTA
            iconView = icon
            priceView = price
            starRatingView = adRating
            storeView = adStore
            advertiserView = adAdvertiser

            with(builder) {
                adTitle?.isVisible = showHeadline
                adBody?.isVisible = showBody
                btnCTA?.isVisible = showCallToAction
                icon?.isVisible = iconEnabled
                price?.isVisible = showPrice
                adRating?.isVisible = showRating
                adStore?.isVisible = showStore
                adAdvertiser?.isVisible = showAdvertiser
            }

            (headlineView as? TextView)?.text = nativeAd.headline
            (bodyView as? TextView)?.apply {
                visibility =
                    if (nativeAd.body == null || !builder.showBody) View.INVISIBLE else View.VISIBLE
                text = nativeAd.body
            }
            (callToActionView as? AppCompatButton)?.apply {
                visibility =
                    if (nativeAd.callToAction == null || !builder.showCallToAction) View.INVISIBLE else View.VISIBLE
                text = nativeAd.callToAction
            }
            (iconView as? ImageView)?.apply {
                visibility =
                    if (nativeAd.icon == null || !builder.iconEnabled) View.GONE else View.VISIBLE
                setImageDrawable(nativeAd.icon?.drawable)
            }
            (priceView as? TextView)?.apply {
                visibility =
                    if (nativeAd.price == null || !builder.showPrice) View.INVISIBLE else View.VISIBLE
                text = nativeAd.price
            }
            (storeView as? TextView)?.apply {
                visibility =
                    if (nativeAd.store == null || !builder.showStore) View.INVISIBLE else View.VISIBLE
                text = nativeAd.store
            }
            (starRatingView as? RatingBar)?.apply {
                visibility = if (nativeAd.starRating == null) View.INVISIBLE else View.VISIBLE
                rating = nativeAd.starRating?.toFloat() ?: 0.0f
            }
            (advertiserView as? TextView)?.apply {
                visibility =
                    if (nativeAd.advertiser == null || !builder.showAdvertiser) View.INVISIBLE else View.VISIBLE
                text = nativeAd.advertiser
            }

            adView.findViewById<ImageView>(R.id.ad_close)?.setOnClickListener {
                Log.d(TAG, "Ad Close Clicked")
                adView.visibility = View.GONE
                adView.removeAllViews()
                adView.destroy()
                // Close the ad the user dismissed - which is this view's ad, not whatever happens
                // to be sitting in the preload cache for another placement.
                nativeAd.destroy()
                adsByFrame.entries.removeAll { it.value === nativeAd }
                cache.entries.removeAll { it.value.ad === nativeAd }
            }

            // setNativeAd(ad) became registerNativeAd(ad, mediaView) - the MediaView is passed in
            // rather than assigned to adView.mediaView beforehand. Null when this layout has no
            // media slot, or when the builder asked for media to be hidden.
            registerNativeAd(nativeAd, mediaViewToRegister)
        }

        // Three changes here: hasVideoContent moved off VideoController onto MediaContent and is a
        // property rather than a method, and VideoLifecycleCallbacks is an interface rather than an
        // abstract class, so there is no super call to make.
        nativeAd.mediaContent
            ?.takeIf { it.hasVideoContent }
            ?.videoController
            ?.apply {
                videoLifecycleCallbacks = object : VideoController.VideoLifecycleCallbacks {
                    override fun onVideoEnd() {
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Populate Error", "populateNativeAdView: ${e.message}")
        }
    }

    fun loadExitNativeAd(
        @ValidateAdUnitId adUnitId: String,
        onSuccessListener: OnSuccessListenerNative<Boolean, NativeAd?>?
    ) {
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            onSuccessListener?.onSuccess(false, null)
            return
        }

        context.showToast("Loading exit native ad")
        // This path used to emit nothing at all, so exit natives were spent without appearing
        // anywhere in the funnel - the one placement most likely to match and never render.
        emit(adUnitId, AdEvent.REQUEST_STARTED)
        val request = NativeAdRequest.Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))
            .setVideoOptions(videoOptions())
            .build()

        NativeAdLoader.load(request, object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(nativeAd: NativeAd) = onMainThread {
                attachAdEventCallback(nativeAd, adUnitId, "ExitNative", "ExitNative")
                exitNativeAd = nativeAd
                onSuccessListener?.onSuccess(true, nativeAd)
            }

            override fun onAdLoadingCompleted() = onMainThread {
                Log.d("ExitNative", "Monetization :- onExitNativeLoaded: Admob")
                context.showToast("Exit native ad loaded")
                emit(adUnitId, AdEvent.LOAD_SUCCESS)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "ExitNative")
            }

            override fun onAdFailedToLoad(adError: LoadAdError) = onMainThread {
                Log.d("ExitNative", "Monetization :- onExitNativeAdFailedToLoad() " + adError.message)
                context.showToast("Failed to load exit native ad")
                emit(adUnitId, AdEvent.LOAD_FAILURE, adError.message)
                onSuccessListener?.onSuccess(false, null)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "ExitNative")
            }
        })
    }

    fun showExitNativeAd(
        builder: NativeAdBuilder,
        @ValidateAdUnitId adUnitId: String
    ) {
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            return
        }
        exitNativeAd?.let {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            builder.let { builder ->
                val adView = LayoutInflater.from(context).inflate(if (builder.layout == 0) R.layout.admob_small_native_media else builder.layout, null) as NativeAdView
                populateNativeAdView(it, adView, builder)
                builder.frameLayout?.removeAllViews()
                builder.frameLayout?.addView(adView)
                if (builder.frameLayout?.visibility == View.GONE) {
                    builder.frameLayout?.visibility = View.VISIBLE
                }
            }
            // Impression, click and revenue reporting were attached to this ad in
            // loadExitNativeAd; onAdPaid lives on the ad's NativeAdEventCallback now, so there is
            // no separate paid-event listener to set here.
        }
    }

    private companion object {
        /**
         * Google recommends showing a native within an hour of loading it. Past that the creative
         * may no longer be served, so a stale cache entry is a guaranteed non-impression.
         */
        const val NATIVE_EXPIRY_MS = 55 * 60 * 1000L

        /**
         * How many units may hold a preloaded ad at once. Two covers the real pattern - the screen
         * on display plus the next one being warmed - without letting a host accumulate creatives.
         */
        const val MAX_CACHED_UNITS = 2
    }
}