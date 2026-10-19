package com.umer_tf.ads.domain.utils

import android.content.Context
import android.widget.Toast

fun Context.showToast(message: String) {
    try {
        //Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        AdsLog.d("Toast", message)
    } catch (e: Exception) {
        AdsLog.e("Toast", "Failed to show toast: ${e.message}")
    }
}