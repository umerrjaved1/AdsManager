package com.umer_tf.ads.domain.ads.native_ad

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.appcompat.widget.AppCompatButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoController
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.umer_tf.ads.R
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.analytics.AdType
import com.umer_tf.ads.domain.analytics.AdEvents
import com.umer_tf.ads.domain.utils.AdsLog
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.core.graphics.toColorInt

class NativeAd(
    private val context: Context
): INativeAdLoader {

    private val TAG = "AdsManager_Native"

    private var loadedNativeAd: NativeAd? = null
        set(value) {
            field = value
            loadedAtMs = if (value == null) 0L else System.currentTimeMillis()
        }

    /** When [loadedNativeAd] was cached, for the freshness check in [isAdLoaded]. */
    private var loadedAtMs: Long = 0L

    /**
     * TTL for the ad cached by [loadAd]. Google documents roughly an hour of freshness for native ads.
     * Seeded from `AdController.nativeAdTtlMs` when this loader is used through `AdMobManager`.
     */
    @JvmField
    var adTtlMs: Long = 60 * 60 * 1000L

    private var loadAndShowNativeAd: NativeAd? = null

    private var exitNativeAd: NativeAd? = null

    // One scope for the whole loader; the old code spawned a fresh CoroutineScope per paid event and
    // never cancelled any of them.
    // Recreated rather than left cancelled by destroy(): this loader is a long-lived singleton, and a
    // cancelled scope would silently swallow every later revenue event.
    private var analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ad ids ("Ad" attribution label) used across the library's layouts, old and new. */
    private val adBadgeIds = intArrayOf(
        R.id.ad_badge, R.id.adText, R.id.attribution, R.id.tvAd
    )

    private fun buildNativeAdOptions(builder: NativeAdBuilder): NativeAdOptions =
        NativeAdOptions.Builder()
            .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
            .setAdChoicesPlacement(builder.adChoicesPlacement)
            .build()

    /**
     * Loads and shows a native ad.
     * @param builder The builder for the native ad.
     * @param onAdLoaded The callback for the load event.
     */
    @MainThread
    override fun loadAndShow(
        @ValidateAdUnitId adUnitId: String,
        builder: NativeAdBuilder,
        onAdLoaded: ((Boolean) -> Unit)?
    ) {
        AdsLog.d(TAG, "NativeAd: loadAndShow requested for adUnitId=$adUnitId")
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            AdsLog.e(TAG, "NativeAd: loadAndShow skipped (invalid id or shouldShowAd returns false)")
            builder.shimmerFrameLayout.stopAndHide()
            builder.frameLayout?.visibility = View.GONE
            onAdLoaded?.invoke(false)
            return
        }
        if (loadAndShowNativeAd!=null){
            loadAndShowNativeAd?.destroy()
            loadAndShowNativeAd=null
        }
        builder.frameLayout?.visibility = View.GONE
        builder.shimmerFrameLayout?.startShimmer()
        builder.shimmerFrameLayout?.visibility = View.VISIBLE

        val adBuilder = context.let { AdLoader.Builder(it, adUnitId) }
        lateinit var adView: NativeAdView
        // OnLoadedListener implementation.
        adBuilder.forNativeAd { nativeAd ->
            loadAndShowNativeAd=nativeAd
            adView = inflateAdView(builder)

            populateNativeAdView(nativeAd, adView, builder)
            builder.frameLayout?.removeAllViews()
            builder.frameLayout?.addView(adView)
            if (builder.frameLayout?.visibility == View.GONE) {
                builder.frameLayout?.visibility = View.VISIBLE
            }

            nativeAd.setOnPaidEventListener { adValue ->
                analyticsScope.launch {
                    AdEvents.revenue(context.applicationContext, adUnitId, AdType.NATIVE, adValue)
                }
            }
        }

        adBuilder.withNativeAdOptions(buildNativeAdOptions(builder))

        val adLoader = adBuilder.withAdListener(object : AdListener() {
            override fun onAdClicked() {
                super.onAdClicked()
                AdsLog.d(TAG, "NativeAd: onAdClicked()")
                AdEvents.clicked(context, adUnitId, AdType.NATIVE)
            }

            override fun onAdClosed() {
                super.onAdClosed()
                AdsLog.d(TAG, "NativeAd: onAdClosed()")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                AdsLog.e(TAG, "NativeAd: onAdFailedToLoad() error=${loadAdError.message}")
                // The shimmer used to keep animating forever over an empty slot on every failure.
                builder.shimmerFrameLayout.stopAndHide()
                builder.frameLayout?.visibility = View.GONE
                onAdLoaded?.invoke(false)
                AdEvents.failedToLoad(context, adUnitId, AdType.NATIVE, loadAdError)
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                AdsLog.d(TAG, "NativeAd: onAdLoaded (loadAndShow) successfully for adUnitId=$adUnitId")
                builder.shimmerFrameLayout.stopAndHide()
                onAdLoaded?.invoke(true)
                AdEvents.loaded(context, adUnitId, AdType.NATIVE)
            }

            override fun onAdImpression() {
                super.onAdImpression()
                AdsLog.d(TAG, "NativeAd: onAdImpression()")
                AdEvents.impression(context, adUnitId, AdType.NATIVE)
            }
        }).build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    /**
     * Loads a native ad.
     * @param adUnitId The ad unit ID for the native ad.
     * @param onAdLoadedNative The callback for the success event.
     */
    @MainThread
    override fun loadAd(
        @ValidateAdUnitId adUnitId: String,
        onAdLoadedNative: ((Boolean, NativeAd?) -> Unit)?,
    ) {
        AdsLog.d(TAG, "NativeAd: loadAd requested for adUnitId=$adUnitId")

        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            AdsLog.e(TAG, "NativeAd: loadAd skipped (invalid id or shouldShowAd returns false)")
            onAdLoadedNative?.invoke(false, null)
            return
        }

        val adBuilder = AdLoader.Builder(context, adUnitId)
        adBuilder.forNativeAd { nativeAd ->
            loadedNativeAd = nativeAd
            AdsLog.d(TAG, "NativeAd: forNativeAd callback received for adUnitId=$adUnitId")
            onAdLoadedNative?.invoke(true, nativeAd)
        }

        adBuilder.withNativeAdOptions(
            NativeAdOptions.Builder()
                .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                .build()
        )

        val adLoader = adBuilder.withAdListener(object : AdListener() {
            override fun onAdClicked() {
                super.onAdClicked()
                AdsLog.d(TAG, "NativeAd: onAdClicked()")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                AdsLog.e(TAG, "NativeAd: onAdFailedToLoad() error=${loadAdError.message}")
                onAdLoadedNative?.invoke(false, null)
                AdEvents.failedToLoad(context, adUnitId, AdType.NATIVE, loadAdError)
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                AdsLog.d(TAG, "NativeAd: onAdLoaded (loadAd) successfully for adUnitId=$adUnitId")
                AdEvents.loaded(context, adUnitId, AdType.NATIVE)
            }

            override fun onAdImpression() {
                super.onAdImpression()
                AdsLog.d(TAG, "NativeAd: onAdImpression()")
                AdEvents.impression(context, adUnitId, AdType.NATIVE)
            }
        }).build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    /**
     * Shows the loaded native ad.
     * @param builder The builder for the native ad.
     * @param adUnitId The ad unit ID for Appsflyer.
     */
    @MainThread
    override fun showLoadedAd(builder: NativeAdBuilder, @ValidateAdUnitId adUnitId: String) {
        AdsLog.d(TAG, "NativeAd: showLoadedAd requested for adUnitId=$adUnitId")
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            builder.shimmerFrameLayout.stopAndHide()
            builder.frameLayout?.visibility = View.GONE
            return
        }
        val ad = if (isAdLoaded()) loadedNativeAd else null
        if (ad == null) {
            // Nothing cached to show, so the placeholder has to come down rather than spin forever.
            AdsLog.e(TAG, "NativeAd: showLoadedAd has no cached ad, hiding shimmer")
            builder.shimmerFrameLayout.stopAndHide()
            builder.frameLayout?.visibility = View.GONE
            return
        }
        builder.shimmerFrameLayout.stopAndHide()
        renderInto(ad, builder)
        ad.setOnPaidEventListener { adValue ->
            analyticsScope.launch {
                AdEvents.revenue(context.applicationContext, adUnitId, AdType.NATIVE, adValue)
            }
        }
    }

    private fun inflateAdView(builder: NativeAdBuilder): NativeAdView =
        LayoutInflater.from(context).inflate(
            if (builder.layout == 0) R.layout.admob_small_native_media else builder.layout,
            null
        ) as NativeAdView

    private fun renderInto(nativeAd: NativeAd, builder: NativeAdBuilder) {
        val adView = inflateAdView(builder)
        populateNativeAdView(nativeAd, adView, builder)
        builder.frameLayout?.removeAllViews()
        builder.frameLayout?.addView(adView)
        builder.frameLayout?.visibility = View.VISIBLE
    }

    override fun destroy() {
        loadedNativeAd?.destroy()
        loadedNativeAd = null
        loadAndShowNativeAd?.destroy()
        loadAndShowNativeAd = null
        exitNativeAd?.destroy()
        exitNativeAd = null
        analyticsScope.cancel()
        analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * True when a cached ad is present and still fresh.
     *
     * A stale native ad renders but is worth less and may show outdated creative, so it is destroyed
     * and reported as absent rather than handed to [showLoadedAd].
     */
    override fun isAdLoaded(): Boolean {
        if (loadedNativeAd == null) return false
        if (loadedAtMs != 0L && System.currentTimeMillis() - loadedAtMs > adTtlMs) {
            AdsLog.d(TAG, "NativeAd: cached ad expired after ${adTtlMs}ms, discarding")
            loadedNativeAd?.destroy()
            loadedNativeAd = null
            return false
        }
        return true
    }


    /**
     * Populates the native ad view.
     * @param nativeAd The native ad.
     * @param adView The native ad view.
     * @param builder The builder for the native ad.
     */
    @MainThread
    private fun populateNativeAdView(
        nativeAd: NativeAd,
        adView: NativeAdView,
        builder: NativeAdBuilder
    ) {
        try {

            val clAdBg = adView.findViewById<ConstraintLayout>(R.id.clAd)
            val btnCTA = adView.findViewById<AppCompatButton>(R.id.ad_call_to_action)
            val adTitle = adView.findViewById<TextView>(R.id.ad_headline)
            val adBody = adView.findViewById<TextView>(R.id.ad_body)
            val icon = adView.findViewById<ImageView>(R.id.ad_app_icon)
            val price = adView.findViewById<TextView>(R.id.ad_price)
            val adMedia = adView.findViewById<MediaView>(R.id.ad_media)
            val adRating = adView.findViewById<RatingBar>(R.id.ad_stars)
            val adStore = adView.findViewById<TextView>(R.id.ad_store)
            val adAdvertiser = adView.findViewById<TextView>(R.id.ad_advertiser)

            val density = context.resources.displayMetrics.density

            builder.adBgColor?.let { adBgColor ->
                clAdBg?.background = GradientDrawable().also { bg ->
                    bg.shape = GradientDrawable.RECTANGLE
                    // Replacing the layout's background used to also throw away its rounded corners,
                    // so every themed ad rendered as a hard-edged rectangle.
                    bg.cornerRadius = builder.adCornerRadius * density
                    if (builder.showBgStroke) {
                        val color = builder.strokeColor?.toColorInt() ?: Color.GRAY
                        // Stroke width is in dp in the builder; setStroke wants pixels.
                        bg.setStroke((builder.strokeWidth * density).toInt().coerceAtLeast(1), color)
                    }
                    bg.setColor(adBgColor.toColorInt())
                }
                clAdBg?.clipToOutline = true
            }

            applyAdBadgeStyling(adView, builder)

            val radiusInPx = builder.ctaRadius * density

            if (btnCTA != null) {
                val ctaColor = builder.ctaBgColor?.let { kotlin.runCatching { it.toColorInt() }.getOrNull() }
                val existingDrawable = btnCTA.background?.mutate()

                if (ctaColor != null) {
                    val drawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radiusInPx
                        setColor(ctaColor)
                    }
                    btnCTA.background = drawable
                } else if (existingDrawable is GradientDrawable) {
                    existingDrawable.cornerRadius = radiusInPx
                    btnCTA.background = existingDrawable
                } else {
                    val defaultCtaColor = androidx.core.content.ContextCompat.getColor(context, R.color.ad_cta_background)
                    val drawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radiusInPx
                        setColor(defaultCtaColor)
                    }
                    btnCTA.background = drawable
                }

                builder.ctaTextColor?.let { ctaTextColor ->
                    btnCTA.setTextColor(ctaTextColor.toColorInt())
                }
            }

            builder.adTitleColor?.let { adTitleColor ->
                adTitle?.setTextColor(adTitleColor.toColorInt())
            }

            builder.adBodyColor?.let { adBodyColor ->
                adBody?.setTextColor(adBodyColor.toColorInt())
            }

            kotlin.runCatching {
                androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)?.let { font ->
                    adTitle?.typeface = font
                }
                androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter)?.let { font ->
                    adBody?.typeface = font
                }
                androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_bold)?.let { font ->
                    btnCTA?.typeface = font
                }
            }

        val adMediaCard = adView.findViewById<View>(R.id.ad_media_card)
        val adMediaContainer = adView.findViewById<View>(R.id.constraintLayoutMedia)
        val hasMediaContent = nativeAd.mediaContent != null

        if (builder.showMedia && hasMediaContent) {
            adMedia?.visibility = View.VISIBLE
            adMediaCard?.visibility = View.VISIBLE
            adMediaContainer?.visibility = View.VISIBLE
            adView.mediaView = adMedia?.apply {
                mediaContent = nativeAd.mediaContent
            }
        } else {
            adMedia?.visibility = View.GONE
            adMediaCard?.visibility = View.GONE
            adMediaContainer?.visibility = View.GONE
        }

        adView.apply {
            headlineView = adTitle
            bodyView = adBody
            callToActionView = btnCTA
            iconView = icon
            priceView = price
            starRatingView = adRating
            storeView = adStore
            advertiserView = adAdvertiser

            val headlineContainer = adView.findViewById<View>(R.id.headline_container)
            if (headlineContainer != null) {
                val hasHeadline = builder.showHeadline && !nativeAd.headline.isNullOrEmpty()
                headlineContainer.visibility = if (hasHeadline) View.VISIBLE else View.GONE
            }

            with(builder) {
                adTitle?.isVisible = showHeadline
                adBody?.isVisible = showBody
                btnCTA?.isVisible = showCallToAction
                icon?.isVisible = iconEnabled
                price?.isVisible = showPrice
                adRating?.isVisible = showRating
                adStore?.isVisible = showStore
                adAdvertiser?.isVisible = showAdvertiser
            }

            (headlineView as? TextView)?.text = nativeAd.headline
            (bodyView as? TextView)?.apply {
                visibility =
                    if (nativeAd.body.isNullOrEmpty() || !builder.showBody) View.GONE else View.VISIBLE
                text = nativeAd.body
            }
            (callToActionView as? AppCompatButton)?.apply {
                visibility =
                    if (nativeAd.callToAction.isNullOrEmpty() || !builder.showCallToAction) View.GONE else View.VISIBLE
                text = nativeAd.callToAction
            }
            (iconView as? ImageView)?.apply {
                val hasIcon = builder.iconEnabled && nativeAd.icon != null && nativeAd.icon?.drawable != null
                visibility = if (hasIcon) View.VISIBLE else View.GONE
                if (hasIcon) {
                    setImageDrawable(nativeAd.icon?.drawable)
                }
            }
            (priceView as? TextView)?.apply {
                visibility =
                    if (nativeAd.price.isNullOrEmpty() || !builder.showPrice) View.GONE else View.VISIBLE
                text = nativeAd.price
            }
            (storeView as? TextView)?.apply {
                // Was gated on showPrice, so setShowStore(true) alone never displayed the store.
                visibility =
                    if (nativeAd.store.isNullOrEmpty() || !builder.showStore) View.GONE else View.VISIBLE
                text = nativeAd.store
            }
            (starRatingView as? RatingBar)?.apply {
                visibility = if (nativeAd.starRating == null || !builder.showRating) View.GONE else View.VISIBLE
                rating = nativeAd.starRating?.toFloat() ?: 0.0f
            }
            (advertiserView as? TextView)?.apply {
                visibility =
                    if (nativeAd.advertiser.isNullOrEmpty() || !builder.showAdvertiser) View.GONE else View.VISIBLE
                text = nativeAd.advertiser
            }

            adView.findViewById<ImageView>(R.id.ad_close)?.setOnClickListener {
                AdsLog.d(TAG, "Ad Close Clicked")
                builder.frameLayout?.visibility = View.GONE
                builder.frameLayout?.removeAllViews()
                adView.destroy()
                // Destroy the ad that is actually on screen. This used to always destroy
                // loadedNativeAd, so closing a loadAndShow ad threw away the cached ad instead.
                when (nativeAd) {
                    loadedNativeAd -> loadedNativeAd = null
                    loadAndShowNativeAd -> loadAndShowNativeAd = null
                    exitNativeAd -> exitNativeAd = null
                }
                nativeAd.destroy()
            }

            setNativeAd(nativeAd)
        }

        nativeAd.mediaContent?.videoController?.takeIf { it.hasVideoContent() }?.apply {
            videoLifecycleCallbacks = object : VideoController.VideoLifecycleCallbacks() {
                override fun onVideoEnd() {
                    super.onVideoEnd()
                }
            }
        }
        } catch (e: Exception) {
            AdsLog.d("Populate Error", "populateNativeAdView: ${e.message}")
        }
    }

    /**
     * Themes the "Ad" attribution label and forces it visible.
     *
     * AdMob's native ad policy requires every native ad to be labelled as an ad, so visibility is
     * deliberately not configurable here - only the colours are. Layouts in the library use one of
     * several historical ids for this label ([adBadgeIds]), all of which are handled.
     */
    @MainThread
    private fun applyAdBadgeStyling(adView: NativeAdView, builder: NativeAdBuilder) {
        val textColor = builder.badgeTextColor?.let { runCatching { it.toColorInt() }.getOrNull() }
        val strokeColor = builder.badgeStrokeColor?.let { runCatching { it.toColorInt() }.getOrNull() }
        val density = context.resources.displayMetrics.density

        var found = false
        for (id in adBadgeIds) {
            val badge = adView.findViewById<TextView>(id) ?: continue
            found = true
            badge.visibility = View.VISIBLE
            if (badge.text.isNullOrBlank()) badge.setText(R.string.ad)
            textColor?.let(badge::setTextColor)
            if (strokeColor != null) {
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4 * density
                    setStroke((1.2f * density).toInt().coerceAtLeast(1), strokeColor)
                }
            }
        }
        if (!found) {
            AdsLog.d(
                TAG,
                "applyAdBadgeStyling: layout ${builder.layout} has no \"Ad\" attribution label. " +
                    "AdMob policy requires one - add a TextView with id @+id/ad_badge."
            )
        }
    }

    fun loadExitNativeAd(
        @ValidateAdUnitId adUnitId: String,
        onAdLoadedNative: ((Boolean, NativeAd?) -> Unit)? = null
    ) {
        AdsLog.d(TAG, "NativeAd: loadExitNativeAd requested for adUnitId=$adUnitId")

        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context)) {
            AdsLog.e(TAG, "NativeAd: loadExitNativeAd skipped (invalid id or shouldShowAd returns false)")
            onAdLoadedNative?.invoke(false, null)
            return
        }

        val adBuilder = AdLoader.Builder(context, adUnitId)
        adBuilder.forNativeAd { nativeAd ->
            exitNativeAd = nativeAd
            AdsLog.d(TAG, "NativeAd: loadExitNativeAd forNativeAd callback received")
            onAdLoadedNative?.invoke(true, nativeAd)
        }

        adBuilder.withNativeAdOptions(
            NativeAdOptions.Builder()
                .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                .build()
        )

        val adLoader = adBuilder.withAdListener(object : AdListener() {
            override fun onAdClicked() {
                super.onAdClicked()
                AdsLog.d(TAG, "NativeAd: onExitNativeAdClicked()")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                AdsLog.e(TAG, "NativeAd: onExitNativeAdFailedToLoad() error=${loadAdError.message}")
                onAdLoadedNative?.invoke(false, null)
                AdEvents.failedToLoad(context, adUnitId, AdType.NATIVE_EXIT, loadAdError)
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                AdsLog.d(TAG, "NativeAd: onExitNativeLoaded successfully")
                AdEvents.loaded(context, adUnitId, AdType.NATIVE_EXIT)
            }

            override fun onAdImpression() {
                super.onAdImpression()
                AdsLog.d(TAG, "NativeAd: onExitNativeImpression()")
                AdEvents.impression(context, adUnitId, AdType.NATIVE_EXIT)
            }
        }).build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    fun showExitNativeAd(
        builder: NativeAdBuilder,
        @ValidateAdUnitId adUnitId: String
    ) {
        builder.shimmerFrameLayout.stopAndHide()
        val ad = exitNativeAd
        if (!AdUnitIdValidator.validateAdUnitId(adUnitId) || !shouldShowAd(context) || ad == null) {
            builder.frameLayout?.visibility = View.GONE
            return
        }
        renderInto(ad, builder)
        ad.setOnPaidEventListener { adValue ->
            analyticsScope.launch {
                AdEvents.revenue(context.applicationContext, adUnitId, AdType.NATIVE_EXIT, adValue)
            }
        }
    }


}