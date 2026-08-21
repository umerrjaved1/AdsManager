package com.umer_tf.ads.domain.core

import android.content.Context
import android.provider.Settings
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
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
 *     .initialize { /* ready */ }
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

    /**
     * Builds the SDK-level configuration.
     *
     * Returned rather than applied, because the Next-Gen SDK has no
     * `MobileAds.setRequestConfiguration()`: the configuration is a field of `InitializationConfig`
     * and must be handed to `MobileAds.initialize()`. `AdMobManager.initialize` folds this into the
     * config it builds, which is why [AdMobManager.setRequestConfig] only has an effect before
     * initialization.
     */
    internal fun toRequestConfiguration(): RequestConfiguration {
        val builder = RequestConfiguration.Builder()

        tagForChildDirectedTreatment?.let {
            builder.setTagForChildDirectedTreatment(
                if (it) RequestConfiguration.TagForChildDirectedTreatment.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                else RequestConfiguration.TagForChildDirectedTreatment.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
            )
        }
        tagForUnderAgeOfConsent?.let {
            builder.setTagForUnderAgeOfConsent(
                if (it) RequestConfiguration.TagForUnderAgeOfConsent.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
                else RequestConfiguration.TagForUnderAgeOfConsent.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE
            )
        }
        maxAdContentRating?.let { rating ->
            val resolved = RequestConfiguration.MaxAdContentRating.entries
                .firstOrNull { it.value == rating }
            if (resolved == null) {
                AdsLog.e(TAG, "Unknown maxAdContentRating \"$rating\"; use the RATING_* constants")
            } else {
                builder.setMaxAdContentRating(resolved)
            }
        }
        if (testDeviceIds.isNotEmpty()) builder.setTestDeviceIds(testDeviceIds)

        AdsLog.d(
            TAG,
            "RequestConfiguration applied: childDirected=$tagForChildDirectedTreatment, " +
                "underAge=$tagForUnderAgeOfConsent, maxRating=$maxAdContentRating, " +
                "testDevices=${testDeviceIds.size}"
        )
        return builder.build()
    }

    companion object {
        private const val TAG = "AdsManager_RequestConfig"

        @JvmField
        val RATING_G: String = RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_G.value
        @JvmField
        val RATING_PG: String = RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_PG.value
        @JvmField
        val RATING_T: String = RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_T.value
        @JvmField
        val RATING_MA: String = RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_MA.value

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
