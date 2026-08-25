package com.umer_tf.ads.domain.ads.native_ad

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListenerNative

interface INativeAdLoader {
    fun loadAndShow(
        adUnitId: String,
        builder: NativeAdBuilder,
        activity: Activity,
        onSuccessListener: OnSuccessListener<Boolean>?
    )

    fun loadAd(
        adUnitId: String,
        activity: Activity,
        onSuccessListener: OnSuccessListenerNative<Boolean,NativeAd?>?=null
    )

    fun showLoadedAd(builder: NativeAdBuilder, adUnitId: String,activity: Activity)

    fun destroy()

    fun isAdLoaded(): Boolean

}