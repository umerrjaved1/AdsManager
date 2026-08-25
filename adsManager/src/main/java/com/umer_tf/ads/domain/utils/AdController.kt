package com.umer_tf.ads.domain.utils

import android.app.Activity

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

    /**
     * Activity classes (and subclasses) on which app-open ads must not be shown.
     * [com.google.android.libraries.ads.mobile.sdk.common.AdActivity] is always excluded separately.
     */
    @JvmField
    val openAdExcludedActivities: MutableSet<Class<out Activity>> = mutableSetOf()
}