package com.umer_tf.ads.domain.utils

import androidx.annotation.LayoutRes

class AdController {
    @JvmField
    var openAdResumeTime: Long = 5

    @JvmField
    var interstitialAdMinTime: Long = 0

    @JvmField
    var interstitialAdMaxTime: Long = 0

    @JvmField
    var interstitialCounter: Int = 0

    @JvmField
    var shouldShowOpenAd: Boolean = true

    @JvmField
    var shouldShowResumeAd: Boolean = true

    @JvmField
    var appOpenAdStartId: String = ""

    @JvmField
    var appOpenAdResumeId: String = ""

    @JvmField
    var isSplash: Boolean = false

    @JvmField
    @LayoutRes
    var loadingDialogLayoutResId: Int? = null
}