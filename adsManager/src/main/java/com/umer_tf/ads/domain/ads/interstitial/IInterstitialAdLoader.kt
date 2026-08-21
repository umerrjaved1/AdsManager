package com.umer_tf.ads.domain.ads.interstitial

import android.app.Activity
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener

/**
 * Interstitial loading and display.
 *
 * ### The three callbacks, and which one to use
 * Every show method takes the same trio. They are additive, not alternatives - pass only what you
 * need:
 *
 * - **[OnSuccessListener]** - the legacy single boolean: `true` on dismissal, `false` on every
 *   failure path. Kept so existing integrations compile unchanged. It cannot tell "the ad failed to
 *   show" from "there was no ad", and it carries no reason.
 * - **`onAdFailedToShow(reason)`** - fires **at most once**, only when no ad was displayed, with the
 *   SDK's message or the library's own refusal reason. Diagnostics, not control flow.
 * - **`onAdDismissed()`** - fires **exactly once, on every terminal path**, including the paths
 *   where nothing was ever shown. It is always the last callback: a failure reports
 *   `onAdFailedToShow` and then `onAdDismissed`.
 *
 * That last guarantee is the point of the split. Gate navigation on `onAdDismissed` alone and it can
 * never stall - not on a no-fill, a malformed ad unit id, a premium user, a dead Activity, a
 * consent-blocked request, or an SDK show failure. Use `onAdFailedToShow` purely to log or report
 * *why* the user was sent onward without seeing an ad.
 *
 * ```kotlin
 * interstitialAdLoader.loadAndShowAd(
 *     activity, AD_UNIT,
 *     onAdFailedToShow = { reason -> Log.w(TAG, "no interstitial: $reason") },
 *     onAdDismissed = { goToNextScreen() }
 * )
 * ```
 */
interface IInterstitialAdLoader {

    /**
     * Shows the cached interstitial for [adUnitId]. Does not load one - see [loadAndShowAd].
     *
     * @param onAdDismissed Fires exactly once, after the ad closes or immediately after a refusal.
     * @param onAdFailedToShow Fires at most once, before [onAdDismissed], when nothing was shown.
     */
    fun showAd(
        activity: Activity,
        adUnitId: String,
        onSuccessListener: OnSuccessListener<Boolean>? = null,
        onAdDismissed: (() -> Unit)? = null,
        onAdFailedToShow: ((String) -> Unit)? = null
    )

    fun showAndLoadAd(
        activity: Activity,
        adUnitId: String,
        onSuccessListener: OnSuccessListener<Boolean>? = null,
        onAdDismissed: (() -> Unit)? = null,
        onAdFailedToShow: ((String) -> Unit)? = null
    )

    /**
     * Shows only when the frequency cap allows it.
     *
     * A capped call is a refusal like any other: [onAdFailedToShow] names the cap and
     * [onAdDismissed] still fires, so a screen that navigates on dismissal behaves identically
     * whether or not this particular trigger was eligible.
     */
    fun showAdWithTimeAndCounter(
        activity: Activity,
        adUnitId: String,
        showForcefully: Boolean = false,
        onSuccessListener: OnSuccessListener<Boolean>? = null,
        onAdDismissed: (() -> Unit)? = null,
        onAdFailedToShow: ((String) -> Unit)? = null
    )

    /**
     * Loads (or reuses) and then shows, optionally behind a loading dialog.
     *
     * A load failure reaches [onAdFailedToShow] as well - from the caller's side "no fill" and
     * "could not display" are the same outcome, and both still end at [onAdDismissed].
     */
    fun loadAndShowAd(
        activity: Activity,
        adUnitId: String,
        showDialog: Boolean = true,
        onSuccessListener: OnSuccessListener<Boolean>? = null,
        onAdDismissed: (() -> Unit)? = null,
        onAdFailedToShow: ((String) -> Unit)? = null
    )

    fun isAdLoaded(): Boolean
    fun loadAd(adUnitId: String, onSuccessListener: OnSuccessListener<Boolean>?)
    fun loadAdWithTimeOut(adUnitId: String, timeOut: Long, onSuccessListener: OnSuccessListener<Boolean>?)
    fun destroy()
}
