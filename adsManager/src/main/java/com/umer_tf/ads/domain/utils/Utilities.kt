package com.umer_tf.ads.domain.utils

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowMetrics
import com.google.android.gms.ads.AdSize
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
            Log.d("TAG", "isNetworkAvailable: ${e.message}")
            return false
        }
    }

    fun shouldShowAd(context: Context?): Boolean {
        return !AdMobManager.isPremium && isNetworkAvailable(context)
    }

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