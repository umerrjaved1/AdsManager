package com.umer_tf.ads.domain.ads.native_ad

import com.google.android.gms.ads.nativead.NativeAd

interface INativeAdLoader {
    fun loadAndShow(
        adUnitId: String,
        builder: NativeAdBuilder,
        onAdLoaded: ((Boolean) -> Unit)? = null
    )

    fun loadAd(
        adUnitId: String,
        onAdLoadedNative: ((Boolean, NativeAd?) -> Unit)? = null
    )

    fun showLoadedAd(builder: NativeAdBuilder, adUnitId: String)

    fun destroy()

    fun isAdLoaded(): Boolean
}