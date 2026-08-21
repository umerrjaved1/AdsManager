package com.umer_tf.ads.domain.ads.rewarded

import android.app.Activity
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener

interface IRewardedAdLoader {
    fun showAd(activity: Activity, onRewardEarned: OnSuccessListener<Boolean>?)
    fun loadAndShowAd(activity: Activity, adUnitId: String,showDialog:Boolean=true, onRewardEarned: OnSuccessListener<Boolean>?)
    fun isAdLoaded(): Boolean
    fun loadAd(activity: Activity, adUnitId: String,)
}