package com.umer_tf.ads.domain.utils

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowMetrics
import com.google.android.gms.ads.AdSize
import com.umer_tf.ads.domain.consent.AdsConsentGate
import com.umer_tf.ads.domain.core.AdMobManager

object Utilities {

    /**
     * Checks if the device is connected to the internet.
     * @param context The context.
     * @return true if the device is connected to the internet, false otherwise.
     */
    fun isNetworkAvailable(context: Context?): Boolean {
        try {
            if (context == null)
                return false

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            if (connectivityManager != null) {
                if (Build.VERSION.SDK_INT < 23) {
                    val ni = connectivityManager.activeNetworkInfo
                    if (ni != null) {
                        return ni.isConnected && (ni.type == ConnectivityManager.TYPE_WIFI || ni.type == ConnectivityManager.TYPE_MOBILE)
                    }
                } else {
                    val n = connectivityManager.activeNetwork
                    if (n != null) {
                        val nc = connectivityManager.getNetworkCapabilities(n)
                        return nc?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true || nc?.hasTransport(
                            NetworkCapabilities.TRANSPORT_WIFI) == true
                    }
                }
            }
            return false
        } catch (e: Exception) {
            AdsLog.d("TAG", "isNetworkAvailable: ${e.message}")
            return false
        }
    }

    /**
     * Single gate every ad request passes through: not a paying user, online, and permitted by the
     * user's consent status.
     *
     * @see com.umer_tf.ads.domain.consent.AdsConsentGate
     */
    fun shouldShowAd(context: Context?): Boolean {
        // Warn rather than block: an app that has genuinely not initialized is broken either way, and
        // failing the gate here would hide the real reason behind a generic "request blocked".
        AdMobManager.warnIfNotInitialized()
        if (AdMobManager.isPremium) return false
        if (!isNetworkAvailable(context)) return false
        if (!AdsConsentGate.allowsAdRequests()) {
            AdsLog.d(TAG, "shouldShowAd: blocked by consent status")
            return false
        }
        return true
    }

    private const val TAG = "AdsManager_Utilities"

    /**
     * Determines the screen width (less decorations) to use for the ad width and returns the adaptive ad size.
     *
     * @param activity The activity where the ad will be shown.
     * @return The adaptive ad size.
     */
    fun getAdSize(activity: Activity): AdSize {
        val adWidth: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use WindowMetrics for Android 12+
            val windowMetrics: WindowMetrics = activity.windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            val density = activity.resources.displayMetrics.density
            (bounds.width() / density).toInt()
        } else {
            // Fallback to deprecated DisplayMetrics for older versions
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            val outMetrics = DisplayMetrics()
            display.getMetrics(outMetrics)
            (outMetrics.widthPixels / outMetrics.density).toInt()
        }

        // Get adaptive ad size and return for setting on the ad view.
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

}