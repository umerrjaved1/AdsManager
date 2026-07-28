package com.umer_tf.ads.domain.ads.native_ad

import android.content.Context
import androidx.annotation.MainThread
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.utils.AdsLog
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd

/**
 * A small pool of native ads for lists - RecyclerView, ViewPager, anything that recycles.
 *
 * The single-ad [NativeAd] loader cannot serve a list: every row would fight over one cached ad. This
 * keeps [size] ads in rotation and, crucially, **remembers which ad a position was given**. Handing a
 * position a different ad on every rebind would re-bill an impression each time the row scrolled back
 * into view and make the list visibly flicker.
 *
 * ```kotlin
 * // once, e.g. in the Fragment
 * private val adPool = NativeAdPool(requireContext(), AD_UNIT_ID, size = 3).also { it.preload() }
 *
 * // in onBindViewHolder for an ad row
 * val ad = adPool.acquire(position)
 * if (ad != null) holder.render(ad) else holder.showPlaceholder()
 *
 * // in onDestroyView
 * adPool.destroy()
 * ```
 *
 * Not thread-safe; call from the main thread.
 *
 * @param size How many distinct ads to keep. Google's guidance is a handful at most - more requests do
 *   not mean more revenue, and unshown ads still count against your request-to-impression ratio.
 * @param ttlMs Freshness window per ad; expired ads are destroyed and refetched.
 */
class NativeAdPool @JvmOverloads constructor(
    private val context: Context,
    private val adUnitId: String,
    private val size: Int = 3,
    private val ttlMs: Long = 60 * 60 * 1000L,
    private val adChoicesPlacement: Int = NativeAdOptions.ADCHOICES_TOP_RIGHT
) {

    private val TAG = "AdsManager_NativePool"

    private class Entry(val ad: NativeAd, val loadedAtMs: Long)

    private val available = ArrayDeque<Entry>()

    /** Position -> the ad it was given, so rebinds are stable. */
    private val assigned = LinkedHashMap<Int, Entry>()

    private var inFlight = 0
    private var destroyed = false

    /** Ads currently held, assigned or not. */
    val loadedCount: Int get() = available.size + assigned.size

    /**
     * Fills the pool up to [size]. Safe to call repeatedly - it only requests the shortfall, counting
     * requests already in flight.
     */
    @MainThread
    fun preload() {
        if (destroyed) return
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) return
        if (!shouldShowAd(context)) {
            AdsLog.d(TAG, "preload skipped (shouldShowAd false)")
            return
        }
        val shortfall = size - loadedCount - inFlight
        if (shortfall <= 0) return
        AdsLog.d(TAG, "preload requesting $shortfall ad(s), have $loadedCount, $inFlight in flight")
        repeat(shortfall) { requestOne() }
    }

    /**
     * Returns the ad for [position], loading more in the background as the pool drains.
     *
     * A position keeps the same ad for as long as the pool lives, so rebinding a recycled row does not
     * consume a new ad or re-bill an impression. Returns null when nothing is ready yet - render a
     * placeholder and the next rebind will have one.
     */
    @MainThread
    fun acquire(position: Int): NativeAd? {
        if (destroyed) return null

        assigned[position]?.let { existing ->
            if (!isExpired(existing)) return existing.ad
            // Stale: drop it and fall through to hand this position a fresh one.
            AdsLog.d(TAG, "acquire($position): assigned ad expired, replacing")
            assigned.remove(position)
            existing.ad.destroy()
        }

        pruneExpired()

        val entry = available.removeFirstOrNull()
        if (entry == null) {
            preload()
            return null
        }

        // Bound the map so a long list cannot grow it without limit; the oldest assignment is the one
        // furthest from the viewport.
        if (assigned.size >= size * 4) {
            assigned.keys.firstOrNull()?.let { oldest ->
                assigned.remove(oldest)?.ad?.destroy()
            }
        }

        assigned[position] = entry
        preload()
        return entry.ad
    }

    /** Releases the ad held for [position]. Call from `onViewRecycled` when a row is genuinely gone. */
    @MainThread
    fun release(position: Int) {
        assigned.remove(position)?.ad?.destroy()
    }

    @MainThread
    fun destroy() {
        destroyed = true
        available.forEach { it.ad.destroy() }
        available.clear()
        assigned.values.forEach { it.ad.destroy() }
        assigned.clear()
        AdsLog.d(TAG, "destroy: pool released")
    }

    private fun isExpired(entry: Entry): Boolean =
        System.currentTimeMillis() - entry.loadedAtMs > ttlMs

    private fun pruneExpired() {
        val stale = available.filter { isExpired(it) }
        if (stale.isEmpty()) return
        stale.forEach {
            available.remove(it)
            it.ad.destroy()
        }
        AdsLog.d(TAG, "pruned ${stale.size} expired ad(s)")
    }

    private fun requestOne() {
        inFlight++
        val loader = AdLoader.Builder(context, adUnitId)
            .forNativeAd { nativeAd ->
                if (destroyed) {
                    // The pool went away mid-request; do not leak the ad.
                    nativeAd.destroy()
                } else {
                    available.addLast(Entry(nativeAd, System.currentTimeMillis()))
                    nativeAd.setOnPaidEventListener { adValue ->
                        AdEvents.revenue(context.applicationContext, adUnitId, AdType.NATIVE, adValue)
                    }
                }
            }
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                    .setAdChoicesPlacement(adChoicesPlacement)
                    .build()
            )
            .withAdListener(object : AdListener() {
                override fun onAdLoaded() {
                    inFlight--
                    AdsLog.d(TAG, "onAdLoaded, pool now holds $loadedCount")
                    AdEvents.loaded(context, adUnitId, AdType.NATIVE)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    inFlight--
                    // No retry here on purpose: a failing unit would otherwise spin forever. The next
                    // acquire() triggers a fresh preload attempt.
                    AdsLog.e(TAG, "onAdFailedToLoad error=${error.message}")
                    AdEvents.failedToLoad(context, adUnitId, AdType.NATIVE, error)
                }

                override fun onAdImpression() {
                    AdEvents.impression(context, adUnitId, AdType.NATIVE)
                }

                override fun onAdClicked() {
                    AdEvents.clicked(context, adUnitId, AdType.NATIVE)
                }
            })
            .build()

        loader.loadAd(AdRequest.Builder().build())
    }
}
