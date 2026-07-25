package com.umer_tf.ads.domain.ads.interstitial

import android.app.Activity
import androidx.annotation.LayoutRes

interface IInterstitialAdLoader {
    fun showAd(
        activity: Activity,
        adUnitId: String,
        onAdDismissed: (() -> Unit)? = null,
        onAdFailedToShow: ((String) -> Unit)? = null
    )
    fun showAndLoadAd(
        activity: Activity,
        adUnitId: String,
        onAdDismissed: (() -> Unit)? = null,
        onAdFailedToShow: ((String) -> Unit)? = null
    )
    fun showAdWithTimeAndCounter(
        activity: Activity,
        adUnitId: String,
        showForcefully: Boolean = false,
        onAdDismissed: (() -> Unit)? = null,
        onAdFailedToShow: ((String) -> Unit)? = null
    )
    fun loadAndShowAd(
        activity: Activity,
        adUnitId: String,
        showDialog: Boolean = true,
        onAdLoaded: ((Boolean) -> Unit)? = null,
        onAdDismissed: (() -> Unit)? = null
    )
    fun loadAndShowAd(
        activity: Activity,
        adUnitId: String,
        showDialog: Boolean = true,
        @LayoutRes customLoadingLayoutResId: Int?,
        onAdLoaded: ((Boolean) -> Unit)? = null,
        onAdDismissed: (() -> Unit)? = null
    )
    fun isAdLoaded(): Boolean
    fun loadAd(adUnitId: String, onAdLoaded: ((Boolean) -> Unit)? = null)
    fun loadAdWithTimeOut(adUnitId: String, timeOut: Long, onAdLoaded: ((Boolean) -> Unit)? = null)
    fun destroy()
}