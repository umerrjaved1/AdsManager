package com.umer_tf.ads.domain.utils

import android.content.Context
import android.util.Log
import android.widget.Toast

fun Context.showToast(message: String) {
    try {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.d("Toast", message)
    } catch (e: Exception) {
        Log.e("Toast", "Failed to show toast: ${e.message}")
    }
}