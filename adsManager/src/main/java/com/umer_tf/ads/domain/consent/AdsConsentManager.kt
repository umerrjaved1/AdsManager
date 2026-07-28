package com.umer_tf.ads.domain.consent

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import com.umer_tf.ads.domain.utils.AdsLog
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*

/**
 * 
 * Principal Software Engineer - Android
 * Created on 27 Aug,2025 10:53
 * Copyright (c) All rights reserved.
 * @see "<a href="https://github.com/ProHussain">Github Profile</a>"
 * @see "<a href="https://linkedin.com/in/prohussain/">Linkedin Profile</a>"
 */

/**
 * Manages user consent for personalized ads in compliance with GDPR and other privacy regulations
 * using Google's User Messaging Platform (UMP) SDK.
 */
class AdsConsentManager(val context: Context) {

    private val TAG = AdsConsentManager::class.java.simpleName

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    /**
     * Preloads consent information and updates the consent status for the user.
     *
     * @param activity The activity context used to request consent information.
     * @param isTest Whether to enable test mode for debugging consent flows.
     */
    fun preLoadConsent(activity: Activity, isTest: Boolean) {
        val params = ConsentRequestParameters.Builder().run {
            if (isTest) {
                reset()
                val android_id = Settings.Secure.getString(activity?.contentResolver, Settings.Secure.ANDROID_ID)
                val deviceId = md5(android_id).uppercase(Locale.getDefault())
                AdsLog.d(TAG, "Consent test device hashed id: $deviceId")
                // For testing purposes, you can force a DebugGeography of EEA or NOT_EEA.
                val debugSettings = ConsentDebugSettings.Builder(activity)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .setForceTesting(true)
                    .addTestDeviceHashedId(deviceId).build()
                this.setConsentDebugSettings(debugSettings)
            }
            build()
        }

        AdsConsentGate.isEnforced = true
        consentInformation.requestConsentInfoUpdate(activity, params, {
            AdsLog.d(TAG, "Consent info updated successfully.")
            AdsConsentGate.update(consentInformation.canRequestAds())
        }, { error ->
            AdsLog.e(TAG, "Error updating consent info: ${error.errorCode} - ${error.message}")
            // A failed update leaves the previous UMP decision in place; publish whatever it says
            // rather than blocking ads forever on a transient network error.
            AdsConsentGate.update(consentInformation.canRequestAds())
        })
    }

    /**
     * Runs the whole consent flow: updates consent info, shows the form when required, and publishes
     * the result to [AdsConsentGate] so ad requests are gated correctly.
     *
     * This is what [com.umer_tf.ads.domain.core.AdMobManager.gatherConsent] calls.
     *
     * @param onComplete Invoked with `canRequestAds` once the flow settles, successfully or not.
     */
    fun gatherConsent(activity: Activity, isTest: Boolean = false, onComplete: (Boolean) -> Unit) {
        AdsConsentGate.isEnforced = true

        val params = ConsentRequestParameters.Builder().apply {
            setTagForUnderAgeOfConsent(false)
            if (isTest) {
                consentInformation.reset()
                val androidId = Settings.Secure.getString(
                    activity.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                val deviceId = md5(androidId).uppercase(Locale.getDefault())
                AdsLog.d(TAG, "Consent test device hashed id: $deviceId")
                setConsentDebugSettings(
                    ConsentDebugSettings.Builder(activity)
                        .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                        .setForceTesting(true)
                        .addTestDeviceHashedId(deviceId)
                        .build()
                )
            }
        }.build()

        val settle = {
            val allowed = consentInformation.canRequestAds()
            AdsConsentGate.update(allowed)
            onComplete(allowed)
        }

        consentInformation.requestConsentInfoUpdate(activity, params, {
            if (activity.isFinishing || activity.isDestroyed) {
                // The form needs a live window; settle on the status we already have.
                AdsLog.e(TAG, "gatherConsent: activity gone before the form could be shown")
                settle()
            } else {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        AdsLog.e(TAG, "Consent form failed: ${formError.errorCode} - ${formError.message}")
                    } else {
                        AdsLog.d(TAG, "Consent gathering complete.")
                    }
                    settle()
                }
            }
        }, { error ->
            AdsLog.e(TAG, "Consent info update failed: ${error.errorCode} - ${error.message}")
            settle()
        })
    }

    /**
     * Loads and shows the GDPR consent form if required, based on the user's consent status.
     *
     * @param activity The activity context used to display the consent form.
     * @param isTest Whether to enable test mode for debugging consent flows.
     * @param onConsentFormLoaded Callback invoked with true if the form was loaded and shown successfully, false otherwise.
     */
    fun showPreLoadGDPRConsent(activity: Activity, isTest: Boolean, onConsentFormLoaded: (Boolean) -> Unit) {
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(
            activity,
            { formError ->
                if (formError != null) {
                    AdsLog.d(TAG, "Error loading consent form: ${formError.errorCode} - ${formError.message}")
                    onConsentFormLoaded(false)
                } else {
                    AdsLog.d(TAG, "Consent form loaded and shown successfully.")
                    onConsentFormLoaded(true)
                }
                AdsConsentGate.update(consentInformation.canRequestAds())
            }
        )
    }

    /**
     * Requests and displays the GDPR consent form to gather user consent for ads.
     *
     * @deprecated The `showGDPRConsent` method is deprecated. Use `preLoadConsent` followed by
     * `showPreLoadGDPRConsent` for a more efficient consent flow, as it separates consent information
     * updates from form display, reducing redundant calls and improving performance.
     *
     * @param activity The activity context used to request and display the consent form.
     * @param isTest Whether to enable test mode for debugging consent flows.
     * @param onConsentGatheringCompleteListener Callback invoked when consent gathering is complete,
     * providing a [FormError] if an error occurs, or null if successful.
     */
    @Deprecated("Use preLoadConsent and showPreLoadGDPRConsent instead for better performance.")
    fun showGDPRConsent(activity: Activity, isTest: Boolean, onConsentGatheringCompleteListener: (FormError?) -> Unit) {
        // Set tag for under age of consent. false means users are not under age
        // of consent.
        val builder = ConsentRequestParameters
            .Builder()
            .setTagForUnderAgeOfConsent(false)

        if (isTest) {
            consentInformation.reset()
            val android_id = Settings.Secure.getString(activity?.contentResolver, Settings.Secure.ANDROID_ID)
            val deviceId = md5(android_id).uppercase(Locale.getDefault())
            val debugSettings = activity.let {
                ConsentDebugSettings.Builder(it)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .addTestDeviceHashedId(deviceId)
                    .build()
            }
            builder.setConsentDebugSettings(debugSettings)
        }

        val params = builder.build()
        consentInformation.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                activity
            ) { loadAndShowError ->
                // Consent gathering failed.
                AdsLog.d(
                    TAG, String.format(
                        "%s: %s", loadAndShowError?.errorCode, loadAndShowError?.message
                    )
                )
                // Consent gathered.
                onConsentGatheringCompleteListener(loadAndShowError)
            }
        }, { requestConsentError ->
            // Consent gathering failed.
            Log.w(
                TAG, String.format(
                    "%s: %s", requestConsentError.errorCode, requestConsentError.message
                )
            )
            onConsentGatheringCompleteListener(requestConsentError)
        })
    }

    /**
     * Indicates whether the app is allowed to request ads based on the user's consent status.
     *
     * @return true if ads can be requested, false otherwise. Note that this always returns false
     * until [requestConsentInfoUpdate] is called.
     */
    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    /**
     * Resets the consent state for testing purposes, simulating a user's first install experience.
     * Useful for debugging and testing the UMP SDK consent flow.
     */
    fun reset() {
        consentInformation.reset()
        AdsConsentGate.reset()
    }

    /**
     * Generates an MD5 hash of the input string, used for creating a test device ID.
     *
     * @param s The input string to hash (typically the Android ID).
     * @return The MD5 hash as a hexadecimal string, or an empty string if hashing fails.
     */
    private fun md5(s: String): String {
        try {
            // Create MD5 Hash
            val digest = MessageDigest.getInstance("MD5")
            digest.update(s.toByteArray())
            val messageDigest = digest.digest()

            // Create Hex String
            val hexString = StringBuffer()
            for (i in messageDigest.indices) hexString.append(Integer.toHexString(0xFF and messageDigest[i].toInt()))
            return hexString.toString()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }
        return ""
    }
}