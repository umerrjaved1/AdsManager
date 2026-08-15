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

    fun showLoadingDialog(isCancelable: Boolean=true) {
        try {
            val context = contextRef.get() ?: return
            
            // Prevent BadTokenException if Activity is finishing
            if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
                return
            }
            
            if (loadingDialog != null && loadingDialog?.isShowing == true) {
                return // Already showing
            }
            
            loadingDialog = Dialog(context).apply {
                setContentView(R.layout.progress_dialog)
                window?.let { window ->
                    window.setBackgroundDrawableResource(android.R.color.transparent)
                    val layoutParams = window.attributes
                    layoutParams.width = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                    window.attributes = layoutParams
                }
                setCancelable(isCancelable) // Make it cancelable to prevent ANR
                setCanceledOnTouchOutside(isCancelable) // Prevent accidental dismissal on outside touch
                show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showLoadingDialog:", e)
        }
    }

    fun hideLoadingDialog() {
        try {
            loadingDialog?.takeIf { it.isShowing }?.dismiss()
        } catch (e: Exception) {
            Log.e(TAG, "hideLoadingDialog:", e)
        } finally {
            // Always drop the reference, not only when the dialog happened to still be showing.
            //
            // A Dialog holds its Activity context strongly, and this object is retained by the
            // interstitial loader - a process singleton - until the next loadAndShowAd() call.
            // Whenever the Activity went away before the dialog was dismissed, isShowing was
            // already false, the old code skipped the assignment, and the destroyed Activity
            // stayed reachable from the singleton for the rest of the session.
            loadingDialog = null
        }
    }

    fun destroy() {
        hideLoadingDialog()
    }
}