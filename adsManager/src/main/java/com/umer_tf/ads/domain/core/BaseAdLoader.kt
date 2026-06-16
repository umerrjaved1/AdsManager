package com.umer_tf.ads.domain.core

interface BaseAdLoader {
    fun loadAd(adUnitId: String)
    fun isAdLoaded(): Boolean
}