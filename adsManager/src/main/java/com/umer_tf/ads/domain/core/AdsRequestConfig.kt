package com.umer_tf.ads.domain.core

import android.content.Context
import android.provider.Settings
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.umer_tf.ads.domain.utils.AdsLog
import java.security.MessageDigest
import java.util.Locale

/**
 * Global AdMob request configuration: audience tagging, content rating and test devices.
 *
 * None of this was previously reachable, which meant any app built from this library shipped without
 * COPPA / Play Families tagging and with no way to register a test device short of editing the
 * library. All of it is mandatory for a Families-targeted app.
 *
 * ```kotlin
 * AdMobManager.getInstance(app)
 *     .setRequestConfig(
 *         AdsRequestConfig(
 *             tagForChildDirectedTreatment = true,   // COPPA
 *             maxAdContentRating = AdsRequestConfig.RATING_G,
 *             testDeviceIds = listOf("33BE2250B43518CCDA7DE426D04EE231")
 *         )
 *     )
 *     .initialize()
 * ```
 *
 * @param tagForChildDirectedTreatment COPPA. `true`/`false` tag the traffic; `null` leaves it unset.
 * @param tagForUnderAgeOfConsent Whether the user is under the age of consent (GDPR). `null` unsets.
 * @param maxAdContentRating One of the `RATING_*` constants, or `null` for no cap.
 * @param testDeviceIds Advertising-ID hashes that should receive test ads. Find yours in logcat -
 *   the AdMob SDK prints it on the first request, or use [logTestDeviceId].
 */
data class AdsRequestConfig @JvmOverloads constructor(
    val tagForChildDirectedTreatment: Boolean? = null,
    val tagForUnderAgeOfConsent: Boolean? = null,
    val maxAdContentRating: String? = null,
    val testDeviceIds: List<String> = emptyList()
) {

    /** Builds the SDK-level configuration and applies it. Called by `AdMobManager.initialize`. */
    internal fun applyToSdk() {
        val builder = RequestConfiguration.Builder()

        tagForChildDirectedTreatment?.let {
            builder.setTagForChildDirectedTreatment(
                if (it) RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                else RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
            )
        }
        tagForUnderAgeOfConsent?.let {
            builder.setTagForUnderAgeOfConsent(
                if (it) RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
                else RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE
            )
        }
        maxAdContentRating?.let(builder::setMaxAdContentRating)
        if (testDeviceIds.isNotEmpty()) builder.setTestDeviceIds(testDeviceIds)

        MobileAds.setRequestConfiguration(builder.build())
        AdsLog.d(
            TAG,
            "RequestConfiguration applied: childDirected=$tagForChildDirectedTreatment, " +
                "underAge=$tagForUnderAgeOfConsent, maxRating=$maxAdContentRating, " +
                "testDevices=${testDeviceIds.size}"
        )
    }

    companion object {
        private const val TAG = "AdsManager_RequestConfig"

        @JvmField val RATING_G: String = RequestConfiguration.MAX_AD_CONTENT_RATING_G
        @JvmField val RATING_PG: String = RequestConfiguration.MAX_AD_CONTENT_RATING_PG
        @JvmField val RATING_T: String = RequestConfiguration.MAX_AD_CONTENT_RATING_T
        @JvmField val RATING_MA: String = RequestConfiguration.MAX_AD_CONTENT_RATING_MA

        /**
         * Ready-made configuration for a Play Families / child-directed app: COPPA on, under-age on,
         * content capped at G.
         */
        @JvmStatic
        @JvmOverloads
        fun forChildDirectedApp(testDeviceIds: List<String> = emptyList()) = AdsRequestConfig(
            tagForChildDirectedTreatment = true,
            tagForUnderAgeOfConsent = true,
            maxAdContentRating = RATING_G,
            testDeviceIds = testDeviceIds
        )

        /**
         * Logs this device's hashed id so it can be pasted into [testDeviceIds].
         *
         * This is the same MD5-of-ANDROID_ID hash the UMP debug settings use. Debug-only output, so it
         * is silent in release builds unless `AdsLog.isEnabled` is turned on.
         */
        @JvmStatic
        fun logTestDeviceId(context: Context) {
            runCatching {
                val androidId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: return
                val digest = MessageDigest.getInstance("MD5").digest(androidId.toByteArray())
                val hashed = digest.joinToString("") { "%02x".format(it) }
                    .uppercase(Locale.getDefault())
                AdsLog.d(TAG, "Test device hashed id: $hashed")
            }.onFailure {
                AdsLog.e(TAG, "logTestDeviceId failed: ${it.message}")
            }
        }
    }
}
