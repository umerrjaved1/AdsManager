package com.umer_tf.ads.domain.viewmodel

import com.google.android.gms.ads.nativead.NativeAd

sealed interface NativeAdUiState {
    object Idle : NativeAdUiState
    object Loading : NativeAdUiState
    data class Success(val nativeAd: NativeAd?, val adUnitId: String) : NativeAdUiState
    data class Error(val message: String) : NativeAdUiState
}

sealed interface InterstitialAdUiState {
    object Idle : InterstitialAdUiState
    object Loading : InterstitialAdUiState
    object Loaded : InterstitialAdUiState
    object Shown : InterstitialAdUiState
    object Dismissed : InterstitialAdUiState
    data class Error(val message: String) : InterstitialAdUiState
}

sealed interface RewardedAdUiState {
    object Idle : RewardedAdUiState
    object Loading : RewardedAdUiState
    object Loaded : RewardedAdUiState
    data class Shown(val rewardEarned: Boolean) : RewardedAdUiState
    object Dismissed : RewardedAdUiState
    data class Error(val message: String) : RewardedAdUiState
}
