package com.umer_tf.ads.domain.ads.interstitial

import android.app.Activity
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener

interface IInterstitialAdLoader {
    fun showAd(activity: Activity, adUnitId: String, onSuccessListener: OnSuccessListener<Boolean>?)
    fun showAndLoadAd(activity: Activity, adUnitId: String, onSuccessListener: OnSuccessListener<Boolean>?)
    fun showAdWithTimeAndCounter(activity: Activity, adUnitId: String, showForcefully: Boolean = false, onSuccessListener: OnSuccessListener<Boolean>?)
    fun loadAndShowAd(activity: Activity, adUnitId: String,showDialog:Boolean=true, onSuccessListener: OnSuccessListener<Boolean>?)
    fun isAdLoaded(): Boolean
    fun loadAd(adUnitId: String, onSuccessListener: OnSuccessListener<Boolean>?)
    fun loadAdWithTimeOut(adUnitId: String, timeOut: Long, onSuccessListener: OnSuccessListener<Boolean>?)
    fun destroy()
}