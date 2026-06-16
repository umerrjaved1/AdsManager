package com.umer_tf.ads.domain.ads.app_open

import android.content.Context
import androidx.annotation.MainThread
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener

interface IAppOpenAdLoader {

    @MainThread
    fun loadResumeAd(context: Context, onSuccessListener: OnSuccessListener<Boolean>?)

    @MainThread
    fun loadAppOpenAd(context: Context, onSuccessListener: OnSuccessListener<Boolean>?)

    @MainThread
    fun showResumeAdIfAvailable(onShowAdCompleteListener: OnSuccessListener<Boolean>)

    @MainThread
    fun showAppOpenAdIfAvailable(onShowAdCompleteListener: OnSuccessListener<Boolean>)

    @MainThread
    fun destroyAds()

    @MainThread
    fun isStartAdAvailable(): Boolean
}