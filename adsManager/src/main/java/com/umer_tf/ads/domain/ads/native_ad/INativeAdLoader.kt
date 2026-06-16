package com.umer_tf.ads.domain.ads.native_ad

import com.google.android.gms.ads.nativead.NativeAd
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListenerNative

interface INativeAdLoader {
    fun loadAndShow(
        adUnitId: String,
        builder: NativeAdBuilder,
        onSuccessListener: OnSuccessListener<Boolean>?
    )

    fun loadAd(
        adUnitId: String,
        onSuccessListener: OnSuccessListenerNative<Boolean,NativeAd?>?=null
    )

    fun showLoadedAd(builder: NativeAdBuilder, adUnitId: String)

    fun destroy()

    fun isAdLoaded(): Boolean

}