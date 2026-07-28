package com.umer_tf.ads.domain.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.umer_tf.ads.R
import java.lang.ref.WeakReference

class LoadingDialogUtil private constructor(
    private val contextRef: WeakReference<Context>,
    private var config: AdLoadingDialogConfig
) {
    companion object {
        const val TAG = "LoadingDialogUtil"

        /**
         * Fallback configuration used whenever a call site does not pass one. Set it once from
         * `AdMobManager.setLoadingDialog(...)`.
         */
        @JvmStatic
        var globalConfig: AdLoadingDialogConfig = AdLoadingDialogConfig.DEFAULT

        @JvmStatic
        @JvmOverloads
        fun create(context: Context, config: AdLoadingDialogConfig? = null): LoadingDialogUtil =
            LoadingDialogUtil(WeakReference(context), config ?: globalConfig)

        /** Layout-only overload kept for existing call sites. */
        @JvmStatic
        fun create(context: Context, @LayoutRes customLayoutResId: Int?): LoadingDialogUtil =
            LoadingDialogUtil(
                WeakReference(context),
                customLayoutResId?.let { globalConfig.copy(layoutResId = it) } ?: globalConfig
            )

        @JvmStatic
        fun setGlobalLoadingLayoutResId(@LayoutRes layoutResId: Int?) {
            globalConfig = globalConfig.copy(layoutResId = layoutResId)
        }

        @Deprecated(
            "Use globalConfig / AdMobManager.setLoadingDialog(AdLoadingDialogConfig)",
            ReplaceWith("globalConfig.layoutResId")
        )
        @JvmStatic
        @get:LayoutRes
        @setparam:LayoutRes
        var customLoadingLayoutResId: Int?
            get() = globalConfig.layoutResId
            set(value) {
                globalConfig = globalConfig.copy(layoutResId = value)
            }
    }

    private var loadingDialog: Dialog? = null

    fun setConfig(config: AdLoadingDialogConfig): LoadingDialogUtil = apply { this.config = config }

    fun setCustomLayout(@LayoutRes layoutResId: Int?): LoadingDialogUtil =
        apply { config = config.copy(layoutResId = layoutResId) }

    fun setMessage(message: CharSequence?): LoadingDialogUtil =
        apply { config = config.copy(message = message) }

    fun isShowing(): Boolean = loadingDialog?.isShowing == true

    @JvmOverloads
    fun showLoadingDialog(
        isCancelable: Boolean = config.cancelable,
        @LayoutRes layoutResId: Int? = null
    ) {
        try {
            val context = contextRef.get() ?: return

            // A Dialog needs a live Activity window token; showing on a finishing Activity or on the
            // Application context throws BadTokenException.
            val activity = context as? Activity
            if (activity == null) {
                AdsLog.e(TAG, "showLoadingDialog: skipped, context is not an Activity")
                return
            }
            if (activity.isFinishing || activity.isDestroyed) return
            if (isShowing()) return

            val effective = layoutResId?.let { config.copy(layoutResId = it) } ?: config

            loadingDialog = effective.dialogProvider?.invoke(activity)?.apply {
                setCancelable(isCancelable)
                setCanceledOnTouchOutside(isCancelable)
                show()
            } ?: buildDefaultDialog(activity, effective, isCancelable).apply { show() }
        } catch (e: Exception) {
            AdsLog.d(TAG, "showLoadingDialog:", e)
        }
    }

    private fun buildDefaultDialog(
        activity: Activity,
        config: AdLoadingDialogConfig,
        isCancelable: Boolean
    ): Dialog {
        val dialog = config.themeResId?.let { Dialog(activity, it) } ?: Dialog(activity)
        return dialog.apply {
            setContentView(config.layoutResId ?: R.layout.progress_dialog)

            config.message?.let { message ->
                val viewId = config.messageViewId ?: R.id.tvLoading
                findViewById<TextView>(viewId)?.text = message
            }

            window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.setLayout(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(config.dimAmount.coerceIn(0f, 1f))
            }

            setCancelable(isCancelable)
            setCanceledOnTouchOutside(isCancelable)
        }
    }

    fun hideLoadingDialog() {
        try {
            loadingDialog?.takeIf { it.isShowing }?.dismiss()
        } catch (e: Exception) {
            AdsLog.d(TAG, "hideLoadingDialog:", e)
        } finally {
            loadingDialog = null
        }
    }

    fun destroy() {
        hideLoadingDialog()
    }
}
