package com.umer_tf.ads.domain.viewmodel

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.android.gms.ads.nativead.NativeAd
import com.umer_tf.ads.domain.ads.native_ad.NativeAdBuilder
import com.umer_tf.ads.domain.core.AdMobManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AdViewModel : ViewModel() {

    private val TAG = "AdsManager_AdViewModel"

    private val _nativeAdState = MutableStateFlow<NativeAdUiState>(NativeAdUiState.Idle)
    val nativeAdState: StateFlow<NativeAdUiState> = _nativeAdState.asStateFlow()

    private val _interstitialAdState = MutableStateFlow<InterstitialAdUiState>(InterstitialAdUiState.Idle)
    val interstitialAdState: StateFlow<InterstitialAdUiState> = _interstitialAdState.asStateFlow()

    private val _rewardedAdState = MutableStateFlow<RewardedAdUiState>(RewardedAdUiState.Idle)
    val rewardedAdState: StateFlow<RewardedAdUiState> = _rewardedAdState.asStateFlow()

    private var cachedNativeAd: NativeAd? = null

    /**
     * Loads a Native Ad using AdMobManager and stores it in StateFlow.
     * Retains state across Activity configuration changes / screen rotations.
     */
    fun loadNativeAd(adMobManager: AdMobManager, adUnitId: String) {
        if (_nativeAdState.value is NativeAdUiState.Loading) {
            Log.e(TAG, "AdViewModel: loadNativeAd already in progress for $adUnitId")
            return
        }
        if (_nativeAdState.value is NativeAdUiState.Success && cachedNativeAd != null) {
            Log.e(TAG, "AdViewModel: loadNativeAd cached ad retained across config change for $adUnitId")
            return
        }

        Log.e(TAG, "AdViewModel: loadNativeAd starting load for $adUnitId")
        _nativeAdState.value = NativeAdUiState.Loading

        adMobManager.nativeAdLoader.loadAd(
            adUnitId = adUnitId,
            onAdLoadedNative = { success, nativeAd ->
                if (success && nativeAd != null) {
                    cachedNativeAd = nativeAd
                    Log.e(TAG, "AdViewModel: loadNativeAd success for $adUnitId")
                    _nativeAdState.value = NativeAdUiState.Success(nativeAd, adUnitId)
                } else {
                    Log.e(TAG, "AdViewModel: loadNativeAd failed for $adUnitId")
                    _nativeAdState.value = NativeAdUiState.Error("Failed to load native ad")
                }
            }
        )
    }

    /**
     * Shows a cached Native Ad into the provided NativeAdBuilder container.
     */
    fun showNativeAd(adMobManager: AdMobManager, builder: NativeAdBuilder, adUnitId: String) {
        val currentState = _nativeAdState.value
        if (currentState is NativeAdUiState.Success) {
            Log.e(TAG, "AdViewModel: showNativeAd displaying cached ad into container")
            adMobManager.nativeAdLoader.showLoadedAd(builder, adUnitId)
        } else {
            Log.e(TAG, "AdViewModel: showNativeAd called but native ad is not loaded yet")
        }
    }

    /**
     * Loads an Interstitial Ad using AdMobManager.
     */
    fun loadInterstitialAd(adMobManager: AdMobManager, adUnitId: String) {
        if (_interstitialAdState.value is InterstitialAdUiState.Loading) return
        Log.e(TAG, "AdViewModel: loadInterstitialAd starting for $adUnitId")
        _interstitialAdState.value = InterstitialAdUiState.Loading

        adMobManager.interstitialAdLoader.loadAd(
            adUnitId = adUnitId,
            onAdLoaded = { success ->
                if (success) {
                    Log.e(TAG, "AdViewModel: loadInterstitialAd loaded")
                    _interstitialAdState.value = InterstitialAdUiState.Loaded
                } else {
                    Log.e(TAG, "AdViewModel: loadInterstitialAd error")
                    _interstitialAdState.value = InterstitialAdUiState.Error("Failed to load interstitial ad")
                }
            }
        )
    }

    /**
     * Shows an Interstitial Ad.
     */
    fun showInterstitialAd(activity: Activity, adMobManager: AdMobManager, adUnitId: String) {
        Log.e(TAG, "AdViewModel: showInterstitialAd requested")
        _interstitialAdState.value = InterstitialAdUiState.Shown

        adMobManager.interstitialAdLoader.showAd(
            activity = activity,
            adUnitId = adUnitId,
            onAdDismissed = {
                Log.e(TAG, "AdViewModel: showInterstitialAd dismissed")
                _interstitialAdState.value = InterstitialAdUiState.Dismissed
            },
            onAdFailedToShow = { error ->
                Log.e(TAG, "AdViewModel: showInterstitialAd failed to show error=$error")
                _interstitialAdState.value = InterstitialAdUiState.Error(error)
            }
        )
    }

    /**
     * Loads a Rewarded Ad using AdMobManager.
     */
    fun loadRewardedAd(activity: Activity, adMobManager: AdMobManager, adUnitId: String) {
        if (_rewardedAdState.value is RewardedAdUiState.Loading) return
        Log.e(TAG, "AdViewModel: loadRewardedAd starting for $adUnitId")
        _rewardedAdState.value = RewardedAdUiState.Loading

        adMobManager.rewardedAdLoader.loadAd(
            activity = activity,
            adUnitId = adUnitId,
            onAdLoaded = { success ->
                if (success) {
                    Log.e(TAG, "AdViewModel: loadRewardedAd loaded")
                    _rewardedAdState.value = RewardedAdUiState.Loaded
                } else {
                    Log.e(TAG, "AdViewModel: loadRewardedAd error")
                    _rewardedAdState.value = RewardedAdUiState.Error("Failed to load rewarded ad")
                }
            }
        )
    }

    /**
     * Shows a Rewarded Ad.
     */
    fun showRewardedAd(activity: Activity, adMobManager: AdMobManager) {
        Log.e(TAG, "AdViewModel: showRewardedAd requested")
        adMobManager.rewardedAdLoader.showAd(
            activity = activity,
            onRewardEarned = { earned ->
                Log.e(TAG, "AdViewModel: showRewardedAd rewardEarned=$earned")
                _rewardedAdState.value = RewardedAdUiState.Shown(earned)
            },
            onAdDismissed = {
                Log.e(TAG, "AdViewModel: showRewardedAd dismissed")
                _rewardedAdState.value = RewardedAdUiState.Dismissed
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        Log.e(TAG, "AdViewModel: onCleared - destroying cached ad resources")
        cachedNativeAd?.destroy()
        cachedNativeAd = null
    }
}
