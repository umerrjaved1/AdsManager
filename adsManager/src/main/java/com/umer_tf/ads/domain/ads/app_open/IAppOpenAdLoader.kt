package com.umer_tf.ads.domain.ads.app_open

import android.content.Context
import androidx.annotation.MainThread

interface IAppOpenAdLoader {

    @MainThread
    fun loadResumeAd(context: Context, onAdLoaded: ((Boolean) -> Unit)? = null)

    @MainThread
    fun loadAppOpenAd(context: Context, onAdLoaded: ((Boolean) -> Unit)? = null)

    @MainThread
    fun showResumeAdIfAvailable(onShowAdCompleteListener: ((Boolean) -> Unit)? = null)

    @MainThread
    fun showAppOpenAdIfAvailable(onShowAdCompleteListener: ((Boolean) -> Unit)? = null)

    @MainThread
    fun destroyAds()

    @MainThread
    fun isStartAdAvailable(): Boolean
}