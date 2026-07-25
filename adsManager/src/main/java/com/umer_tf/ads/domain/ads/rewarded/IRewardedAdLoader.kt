package com.umer_tf.ads.domain.ads.rewarded

import android.app.Activity
import androidx.annotation.LayoutRes

interface IRewardedAdLoader {
    fun showAd(
        activity: Activity,
        onRewardEarned: ((Boolean) -> Unit)? = null,
        onAdDismissed: (() -> Unit)? = null
    )
    fun loadAndShowAd(
        activity: Activity,
        adUnitId: String,
        showDialog: Boolean = true,
        onRewardEarned: ((Boolean) -> Unit)? = null,
        onAdDismissed: (() -> Unit)? = null
    )
    fun loadAndShowAd(
        activity: Activity,
        adUnitId: String,
        showDialog: Boolean = true,
        @LayoutRes customLoadingLayoutResId: Int?,
        onRewardEarned: ((Boolean) -> Unit)? = null,
        onAdDismissed: (() -> Unit)? = null
    )
    fun isAdLoaded(): Boolean
    fun loadAd(
        activity: Activity,
        adUnitId: String,
        onAdLoaded: ((Boolean) -> Unit)? = null
    )
}