package com.umer_tf.ads.domain.utils

import android.app.Dialog
import android.content.Context
import android.util.Log
import com.umer_tf.ads.R
import java.lang.ref.WeakReference

class LoadingDialogUtil private constructor(private val contextRef: WeakReference<Context>) {
    companion object {
        const val TAG = "LoadingDialogUtil"
        
        fun create(context: Context): LoadingDialogUtil {
            return LoadingDialogUtil(WeakReference(context))
        }
    }

    private var loadingDialog: Dialog? = null

    fun showLoadingDialog() {
        try {
            val context = contextRef.get() ?: return
            if (loadingDialog != null && loadingDialog?.isShowing == true) {
                return // Already showing
            }
            
            loadingDialog = Dialog(context).apply {
                setContentView(R.layout.progress_dialog)
                // Make sure dialog cover 90% of screen
                window?.let { window ->
                    val layoutParams = window.attributes
                    layoutParams.width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
                    window.attributes = layoutParams
                }
                setCancelable(true) // Make it cancelable to prevent ANR
                show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showLoadingDialog:", e)
        }
    }

    fun hideLoadingDialog() {
        try {
            if (loadingDialog != null && loadingDialog?.isShowing == true) {
                loadingDialog?.dismiss()
                loadingDialog = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "hideLoadingDialog:", e)
        }
    }

    fun destroy() {
        hideLoadingDialog()
    }
}