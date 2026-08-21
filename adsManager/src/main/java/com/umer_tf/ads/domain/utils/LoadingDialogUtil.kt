package com.umer_tf.ads.domain.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.umer_tf.ads.R
import java.lang.ref.WeakReference

/**
 * The "loading ad" dialog shown while an interstitial or rewarded ad is being fetched.
 *
 * What it renders is described by [AdLoadingDialogConfig] rather than hard-coded here, so a host can
 * change the copy, supply its own layout, or take over dialog creation entirely - see
 * `AdMobManager.setLoadingDialog`. Call sites that pass no config get [globalConfig], which is what
 * makes one call in `Application.onCreate` cover every loader.
 */
class LoadingDialogUtil private constructor(
    private val contextRef: WeakReference<Context>,
    private var config: AdLoadingDialogConfig
) {
    companion object {
        const val TAG = "AdsManager_LoadingDialog"

        /**
         * Fallback configuration for every call site that does not pass one. Set once, from
         * `AdMobManager.setLoadingDialog(...)`.
         *
         * This is the single source of truth for the dialog's appearance. It deliberately does not
         * also live on `AdController`: the util is what actually reads it, and a second copy in the
         * settings bag would only be a field that could disagree with the one being used.
         */
        @JvmStatic
        @Volatile
        var globalConfig: AdLoadingDialogConfig = AdLoadingDialogConfig.DEFAULT

        @JvmStatic
        @JvmOverloads
        fun create(context: Context, config: AdLoadingDialogConfig? = null): LoadingDialogUtil =
            LoadingDialogUtil(WeakReference(context), config ?: globalConfig)

        /** Replaces only the layout on [globalConfig], leaving the rest of it alone. */
        @JvmStatic
        fun setGlobalLoadingLayoutResId(@LayoutRes layoutResId: Int?) {
            globalConfig = globalConfig.copy(layoutResId = layoutResId)
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

            // A Dialog needs a live Activity window token. Built on the Application context it
            // throws BadTokenException from show(), which the catch below would swallow - so the
            // dialog would silently never appear. Name it instead of hiding it.
            val activity = context as? Activity
            if (activity == null) {
                AdsLog.e(
                    TAG,
                    "showLoadingDialog: skipped, LoadingDialogUtil was created with a " +
                        "${context.javaClass.simpleName} rather than an Activity"
                )
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
            AdsLog.e(TAG, "showLoadingDialog: ${e.message}", e)
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

            // Only when the host asked for different copy: with no message the layout keeps its own
            // text, which is what makes a fully custom layout work without naming a TextView.
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
            AdsLog.e(TAG, "hideLoadingDialog: ${e.message}", e)
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
