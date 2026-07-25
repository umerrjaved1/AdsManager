package com.umer_tf.ads.domain.utils

import android.app.Dialog
import android.content.Context
import android.util.Log
import androidx.annotation.LayoutRes
import com.umer_tf.ads.R
import java.lang.ref.WeakReference

class LoadingDialogUtil private constructor(
    private val contextRef: WeakReference<Context>,
    @LayoutRes private var customLayoutResId: Int? = null
) {
    companion object {
        const val TAG = "LoadingDialogUtil"

        @JvmStatic
        @LayoutRes
        var customLoadingLayoutResId: Int? = null

        fun create(context: Context, @LayoutRes customLayoutResId: Int? = null): LoadingDialogUtil {
            return LoadingDialogUtil(WeakReference(context), customLayoutResId)
        }

        fun setGlobalLoadingLayoutResId(@LayoutRes layoutResId: Int?) {
            customLoadingLayoutResId = layoutResId
        }
    }

    private var loadingDialog: Dialog? = null

    fun setCustomLayout(@LayoutRes layoutResId: Int?): LoadingDialogUtil {
        this.customLayoutResId = layoutResId
        return this
    }

    fun showLoadingDialog(isCancelable: Boolean = true, @LayoutRes layoutResId: Int? = null) {
        try {
            val context = contextRef.get() ?: return
            
            // Prevent BadTokenException if Activity is finishing
            if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
                return
            }
            
            if (loadingDialog != null && loadingDialog?.isShowing == true) {
                return // Already showing
            }

            val targetLayoutResId = layoutResId
                ?: customLayoutResId
                ?: customLoadingLayoutResId
                ?: R.layout.progress_dialog

            loadingDialog = Dialog(context).apply {
                setContentView(targetLayoutResId)
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