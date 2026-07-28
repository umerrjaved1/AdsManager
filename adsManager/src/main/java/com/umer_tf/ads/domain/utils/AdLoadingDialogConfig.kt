package com.umer_tf.ads.domain.utils

import android.app.Activity
import android.app.Dialog
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.StyleRes

/**
 * Describes the "loading ad" dialog shown while an interstitial / rewarded ad is being fetched.
 *
 * Three levels of customisation, cheapest first:
 *
 * 1. Keep the built-in layout and just change the copy:
 *    `AdLoadingDialogConfig(message = "Almost there…")`
 * 2. Supply your own layout - optionally naming the `TextView` that carries the message so the
 *    library can still set it:
 *    `AdLoadingDialogConfig(layoutResId = R.layout.my_loader, messageViewId = R.id.tvMessage)`
 * 3. Build the whole [Dialog] yourself when you need a branded/animated loader. The library only
 *    calls `show()`/`dismiss()` on whatever you return:
 *    `AdLoadingDialogConfig(dialogProvider = { activity -> MyBrandedLoader(activity) })`
 *
 * @param layoutResId Custom content layout. `null` uses the library's `progress_dialog`.
 * @param messageViewId `TextView` inside [layoutResId] that receives [message]. `null` leaves the
 *   layout's own text untouched.
 * @param message Text to display. `null` leaves the layout's own text untouched.
 * @param themeResId Dialog theme. `null` uses the platform default.
 * @param cancelable Whether back/outside-touch can dismiss the loader. Defaults to `false`: the
 *   dialog auto-dismisses when the ad resolves, and letting the user dismiss it early only hides the
 *   fact that a full-screen ad is about to appear.
 * @param dimAmount Background dim, `0f`..`1f`.
 * @param dialogProvider Escape hatch that takes over dialog creation entirely. When set, every other
 *   field except [cancelable] is ignored.
 */
data class AdLoadingDialogConfig @JvmOverloads constructor(
    @LayoutRes val layoutResId: Int? = null,
    @IdRes val messageViewId: Int? = null,
    val message: CharSequence? = null,
    @StyleRes val themeResId: Int? = null,
    val cancelable: Boolean = false,
    val dimAmount: Float = 0.6f,
    val dialogProvider: ((Activity) -> Dialog)? = null
) {
    companion object {
        /** Built-in spinner + "Loading Ad…" label. */
        @JvmField
        val DEFAULT = AdLoadingDialogConfig()

        /** Convenience for the common "my own layout, nothing else changes" case. */
        @JvmStatic
        fun withLayout(@LayoutRes layoutResId: Int): AdLoadingDialogConfig =
            AdLoadingDialogConfig(layoutResId = layoutResId)
    }
}
