package com.umer_tf.ads.domain.analytics

import android.content.Context
import android.os.Bundle
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.LoadAdError
import com.google.firebase.analytics.FirebaseAnalytics
import com.umer_tf.ads.domain.utils.AdsLog
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_CLICKED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_DISMISSED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_SHOWN
import com.umer_tf.ads.domain.utils.AnalyticsConstants.SHOWING_AD
import com.umer_tf.ads.domain.utils.AnalyticsManager

/**
 * Central funnel for every ad lifecycle event.
 *
 * Each loader reports here instead of calling Firebase directly, so an event is logged once, with one
 * consistent set of names, and forwarded to the app's [AdEventListener]. Loaders previously duplicated
 * this logic inline with their own type strings, which is how `"banner_ad"`, `"Interstitial_ad"` and
 * `"NativeAd"` ended up in the same dataset.
 *
 * Firebase event names are unchanged from before this object existed - see [AdType.analyticsName].
 */
object AdEvents {

    private const val TAG = "AdsManager_Events"
    private const val EVENT_AD_REVENUE = "ad_revenue_sdk"

    @Volatile
    private var listener: AdEventListener? = null

    /** Registers the app's observer. Pass null to clear. */
    @JvmStatic
    fun setListener(listener: AdEventListener?) {
        this.listener = listener
        AdsLog.d(TAG, "AdEventListener ${if (listener == null) "cleared" else "registered"}")
    }

    @JvmStatic
    fun loaded(context: Context, adUnitId: String, adType: AdType) {
        log(context, AD_LOADED, adType)
        dispatch("onAdLoaded") { it.onAdLoaded(adUnitId, adType) }
    }

    @JvmStatic
    fun impression(context: Context, adUnitId: String, adType: AdType) {
        log(context, AD_SHOWN, adType)
        dispatch("onAdImpression") { it.onAdImpression(adUnitId, adType) }
    }

    @JvmStatic
    fun clicked(context: Context, adUnitId: String, adType: AdType) {
        log(context, AD_CLICKED, adType)
        dispatch("onAdClicked") { it.onAdClicked(adUnitId, adType) }
    }

    @JvmStatic
    fun showed(context: Context, adUnitId: String, adType: AdType) {
        log(context, SHOWING_AD, adType)
        dispatch("onAdShowed") { it.onAdShowed(adUnitId, adType) }
    }

    @JvmStatic
    fun dismissed(context: Context, adUnitId: String, adType: AdType) {
        log(context, AD_DISMISSED, adType)
        dispatch("onAdDismissed") { it.onAdDismissed(adUnitId, adType) }
    }

    /** Reports an SDK load failure. */
    @JvmStatic
    fun failedToLoad(context: Context, adUnitId: String, adType: AdType, error: LoadAdError) {
        failedToLoad(
            context,
            adUnitId,
            adType,
            AdLoadFailure(error.code, error.message.orEmpty(), error.domain)
        )
    }

    /**
     * Reports a full-screen show failure. Distinct from a load failure in cause, but reported through
     * the same callback because the outcome for the app is identical: no ad was displayed.
     */
    @JvmStatic
    fun failedToLoad(context: Context, adUnitId: String, adType: AdType, error: AdError) {
        failedToLoad(
            context,
            adUnitId,
            adType,
            AdLoadFailure(error.code, error.message.orEmpty(), error.domain)
        )
    }

    /**
     * Reports a failure the library raised itself - a malformed ad unit id, or a request suppressed by
     * the consent gate or kill switch. Surfaced so these are visible rather than looking like silence.
     */
    @JvmStatic
    fun failedToLoad(context: Context, adUnitId: String, adType: AdType, reason: String) {
        failedToLoad(context, adUnitId, adType, AdLoadFailure(AdLoadFailure.CODE_LIBRARY, reason))
    }

    @JvmStatic
    fun failedToLoad(context: Context, adUnitId: String, adType: AdType, failure: AdLoadFailure) {
        log(context, AD_FAILED, adType)
        dispatch("onAdFailedToLoad") { it.onAdFailedToLoad(adUnitId, adType, failure) }
    }

    /** Reports an AdMob paid event: Firebase `ad_revenue_sdk` plus [AdEventListener.onAdRevenuePaid]. */
    @JvmStatic
    fun revenue(context: Context, adUnitId: String, adType: AdType, adValue: AdValue) {
        val info = AdRevenueInfo.from(adUnitId, adType, adValue)
        runCatching {
            val params = Bundle().apply {
                putDouble(FirebaseAnalytics.Param.VALUE, info.value)
                // The currency AdMob actually reported. This was hardcoded to USD, which mislabelled
                // revenue for every account not denominated in dollars.
                putString(FirebaseAnalytics.Param.CURRENCY, info.currencyCode)
                putString("ad_format", info.adType.revenueName)
                putString("ad_unit_id", info.adUnitId)
                putString("value_precision", info.precision.name)
            }
            AnalyticsManager.getInstance(context).sendEvent(EVENT_AD_REVENUE, params)
        }.onFailure {
            AdsLog.e(TAG, "revenue: Firebase logging failed for ${adType.revenueName}: ${it.message}")
        }
        dispatch("onAdRevenuePaid") { it.onAdRevenuePaid(info) }
    }

    private fun log(context: Context, action: String, adType: AdType) {
        runCatching {
            AnalyticsManager.getInstance(context).sendAnalytics(action, adType.analyticsName)
        }.onFailure {
            AdsLog.e(TAG, "log: Firebase logging failed for $action/${adType.analyticsName}: ${it.message}")
        }
    }

    private inline fun dispatch(name: String, block: (AdEventListener) -> Unit) {
        val target = listener ?: return
        // A listener that throws must not break ad delivery.
        runCatching { block(target) }.onFailure {
            AdsLog.e(TAG, "AdEventListener.$name threw: ${it.message}", it)
        }
    }
}
