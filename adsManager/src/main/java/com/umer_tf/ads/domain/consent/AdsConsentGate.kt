package com.umer_tf.ads.domain.consent

import com.umer_tf.ads.domain.utils.AdsLog

/**
 * Process-wide record of whether ad requests are currently permitted by the user's consent status.
 *
 * `Utilities.shouldShowAd` consults this before every request. It exists because the consent state
 * lives in [AdsConsentManager], which needs an `Activity`, while ad requests happen from anywhere.
 *
 * ### Why enforcement is opt-in
 * `ConsentInformation.canRequestAds()` returns false until `requestConsentInfoUpdate` has completed,
 * so gating on it unconditionally would mean zero ads for any app that never runs a consent flow.
 * Enforcement therefore switches on the moment consent gathering is started through
 * `AdMobManager.gatherConsent(...)`, and from then on ad requests wait for a real answer.
 *
 * An app that never gathers consent keeps the previous behaviour but gets a warning on its first ad
 * request, because shipping to the EEA without a consent flow is an AdMob policy violation.
 */
object AdsConsentGate {

    private const val TAG = "AdsManager_Consent"

    /** True once a consent flow has been started through the library. */
    @Volatile
    var isEnforced: Boolean = false
        internal set

    /** Mirrors `ConsentInformation.canRequestAds()`. Only meaningful while [isEnforced]. */
    @Volatile
    var canRequestAds: Boolean = false
        internal set

    @Volatile
    private var warned = false

    /**
     * Whether an ad request may proceed right now.
     *
     * Returns true when consent is not being enforced (logging a one-time warning), or when the user's
     * consent status permits ad requests.
     */
    @JvmStatic
    fun allowsAdRequests(): Boolean {
        if (!isEnforced) {
            if (!warned) {
                warned = true
                AdsLog.w(
                    TAG,
                    "Ads are being requested without a consent flow. Call " +
                        "AdMobManager.gatherConsent(activity) before requesting ads, or EEA traffic " +
                        "will breach AdMob's consent policy."
                )
            }
            return true
        }
        return canRequestAds
    }

    /** Called by the consent flow whenever the underlying UMP state may have changed. */
    internal fun update(canRequestAds: Boolean) {
        this.canRequestAds = canRequestAds
        isEnforced = true
        AdsLog.d(TAG, "Consent gate updated: canRequestAds=$canRequestAds")
    }

    /** Test/debug helper that returns the gate to its initial, unenforced state. */
    @JvmStatic
    fun reset() {
        isEnforced = false
        canRequestAds = false
        warned = false
    }
}
