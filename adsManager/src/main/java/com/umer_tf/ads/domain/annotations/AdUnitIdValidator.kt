package com.umer_tf.ads.domain.annotations

object AdUnitIdValidator {
    // AdMob ad unit ID patterns
    private const val BANNER_AD_PATTERN = "^ca-app-pub-\\d{16,}/\\d{10}$"
    private const val INTERSTITIAL_AD_PATTERN = "^ca-app-pub-\\d{16,}/\\d{10}$"
    private const val REWARDED_AD_PATTERN = "^ca-app-pub-\\d{16,}/\\d{10}$"
    private const val NATIVE_AD_PATTERN = "^ca-app-pub-\\d{16,}/\\d{10}$"
    private const val APP_OPEN_AD_PATTERN = "^ca-app-pub-\\d{16,}/\\d{10}$"
    
    // General pattern that matches all AdMob ad unit IDs
    private const val GENERAL_AD_PATTERN = "^ca-app-pub-\\d{16,}/\\d{10}$"
    
    // Test ad unit ID pattern (for testing purposes)
    private const val TEST_AD_PATTERN = "^ca-app-pub-3940256099942544/\\d{10}$"

    /**
     * Validates an ad unit ID using general pattern.
     * @param adUnitId The ad unit ID to validate.
     */
    fun validateAdUnitId(adUnitId: String) {
        if (!isValidAdUnitId(adUnitId)) {
            throw IllegalArgumentException(
                "Invalid Ad Unit ID format. Expected format: ca-app-pub-{publisher-id}/{ad-id}, " +
                "provided: $adUnitId"
            )
        }
    }
    
    /**
     * Validates a banner ad unit ID.
     * @param adUnitId The banner ad unit ID to validate.
     */
    fun validateBannerAdUnitId(adUnitId: String) {
        validateAdUnitId(adUnitId)
    }
    
    /**
     * Validates an interstitial ad unit ID.
     * @param adUnitId The interstitial ad unit ID to validate.
     */
    fun validateInterstitialAdUnitId(adUnitId: String) {
        validateAdUnitId(adUnitId)
    }
    
    /**
     * Validates a rewarded ad unit ID.
     * @param adUnitId The rewarded ad unit ID to validate.
     */
    fun validateRewardedAdUnitId(adUnitId: String) {
        validateAdUnitId(adUnitId)
    }
    
    /**
     * Validates a native ad unit ID.
     * @param adUnitId The native ad unit ID to validate.
     */
    fun validateNativeAdUnitId(adUnitId: String) {
        validateAdUnitId(adUnitId)
    }
    
    /**
     * Validates an app open ad unit ID.
     * @param adUnitId The app open ad unit ID to validate.
     */
    fun validateAppOpenAdUnitId(adUnitId: String) {
        validateAdUnitId(adUnitId)
    }
    
    /**
     * Checks if an ad unit ID is valid.
     * @param adUnitId The ad unit ID to check.
     * @return true if valid, false otherwise.
     */
    fun isValidAdUnitId(adUnitId: String): Boolean {
        return adUnitId.matches(GENERAL_AD_PATTERN.toRegex())
    }
    
    /**
     * Checks if an ad unit ID is a test ad unit ID.
     * @param adUnitId The ad unit ID to check.
     * @return true if it's a test ad unit ID, false otherwise.
     */
    fun isTestAdUnitId(adUnitId: String): Boolean {
        return adUnitId.matches(TEST_AD_PATTERN.toRegex())
    }
}
