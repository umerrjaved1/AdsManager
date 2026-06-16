package com.umer_tf.ads.domain.apps_flyer

import android.content.Context
import android.os.Bundle
import com.google.android.gms.ads.AdValue
import com.google.firebase.analytics.FirebaseAnalytics
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.utils.AnalyticsManager
import java.util.Currency
import java.util.Locale

object AdsAnalytics {
    /**
     * Logs the revenue from the AppsFlyer SDK.
     *
     * @param adId The ad ID.
     * @param adType The ad type.
     * @param adValue The ad value.
     * @param context The context.
     */
    fun logAppsFlyerRevenue(@ValidateAdUnitId adId: String, adType: String, adValue: AdValue, context: Context) {
        val price: Double = adValue.valueMicros.toDouble() / 1000000
        val currency: Currency = Currency.getInstance(Locale.US)
        val adRevenueParameters = Bundle().apply {
            putDouble(FirebaseAnalytics.Param.VALUE, price)
            putString(FirebaseAnalytics.Param.CURRENCY, currency.currencyCode)
            putString("ad_format", adType) // Custom parameter
        }
        // Log the event with Firebase Analytics
        AnalyticsManager.getInstance(context).sendEvent("ad_revenue_sdk", adRevenueParameters)
    }
}
