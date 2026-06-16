// AnalyticsManager.kt
package com.umer_tf.ads.domain.utils

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

class AnalyticsManager private constructor(context: Context) {

    private val firebaseAnalytics: FirebaseAnalytics =
        FirebaseAnalytics.getInstance(context)

    companion object {
        private const val ACTION_TYPE = "action_type"

        @Volatile
        private var manager: AnalyticsManager? = null

        @JvmStatic
        fun getInstance(context: Context): AnalyticsManager {
            return manager ?: synchronized(this) {
                manager ?: AnalyticsManager(context).also { manager = it }
            }
        }
    }

    fun sendAnalytics(actionDetail: String, actionName: String) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.CONTENT_TYPE, actionDetail)
            putString(ACTION_TYPE, actionName)
        }
        firebaseAnalytics.logEvent(actionName, bundle)
    }

    fun sendEvent(key: String, bundle: Bundle) {
        Log.d("Analytics", "sendEvent: $key ${bundle.toString()}")
        firebaseAnalytics.logEvent(key, bundle)
    }
}
