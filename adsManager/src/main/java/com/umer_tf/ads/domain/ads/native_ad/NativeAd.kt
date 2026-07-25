package com.umer_tf.ads.domain.ads.native_ad

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
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
import com.umer_tf.ads.domain.apps_flyer.AdsAnalytics
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_CLICKED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_FAILED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_LOADED
import com.umer_tf.ads.domain.utils.AnalyticsConstants.AD_SHOWN
import com.umer_tf.ads.domain.utils.AnalyticsManager
import com.umer_tf.ads.domain.utils.Utilities.shouldShowAd
import com.umer_tf.ads.domain.utils.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.graphics.toColorInt

class NativeAd(
    private val context: Context
): INativeAdLoader {

    private val TAG = "AdsManager_Native"

    private var loadedNativeAd: NativeAd? = null
    private var loadAndShowNativeAd: NativeAd? = null

    private var exitNativeAd: NativeAd? = null

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
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "NativeAd: loadAndShow requested for adUnitId=$adUnitId")
        if (!shouldShowAd(context)) {
            Log.e(TAG, "NativeAd: loadAndShow skipped (shouldShowAd returns false)")
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
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
            adView = LayoutInflater.from(context).inflate(if (builder.layout == 0) R.layout.admob_small_native_media else builder.layout, null) as NativeAdView

            populateNativeAdView(nativeAd, adView, builder)
            builder.frameLayout?.removeAllViews()
            builder.frameLayout?.addView(adView)
            if (builder.frameLayout?.visibility == View.GONE) {
                builder.frameLayout?.visibility = View.VISIBLE
            }

            nativeAd.setOnPaidEventListener { adValue ->
                CoroutineScope(Dispatchers.IO).launch {
                    AdsAnalytics.logAppsFlyerRevenue(
                        adUnitId,
                        "Native",
                        adValue,
                        context.applicationContext
                    )
                }
            }
        }

        val videoOptions = VideoOptions.Builder().setStartMuted(false).build()

        val adOptions = NativeAdOptions.Builder().setVideoOptions(videoOptions).build()

        adBuilder.withNativeAdOptions(adOptions)

        val adLoader = adBuilder.withAdListener(object : AdListener() {
            override fun onAdClicked() {
                super.onAdClicked()
                Log.e(TAG, "NativeAd: onAdClicked()")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_CLICKED, "NativeAd")
            }

            override fun onAdClosed() {
                super.onAdClosed()
                Log.e(TAG, "NativeAd: onAdClosed()")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                Log.e(TAG, "NativeAd: onAdFailedToLoad() error=${loadAdError.message}")
                onAdLoaded?.invoke(false)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "NativeAd")
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                Log.e(TAG, "NativeAd: onAdLoaded (loadAndShow) successfully for adUnitId=$adUnitId")
                builder.shimmerFrameLayout?.stopShimmer()
                builder.shimmerFrameLayout?.visibility = View.GONE
                onAdLoaded?.invoke(true)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "NativeAd")
            }

            override fun onAdImpression() {
                super.onAdImpression()
                Log.e(TAG, "NativeAd: onAdImpression()")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_SHOWN, "NativeAd")
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
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "NativeAd: loadAd requested for adUnitId=$adUnitId")

        if (!shouldShowAd(context)) {
            Log.e(TAG, "NativeAd: loadAd skipped (shouldShowAd returns false)")
            onAdLoadedNative?.invoke(false, null)
            return
        }

        val adBuilder = AdLoader.Builder(context, adUnitId)
        adBuilder.forNativeAd { nativeAd ->
            loadedNativeAd = nativeAd
            Log.e(TAG, "NativeAd: forNativeAd callback received for adUnitId=$adUnitId")
            onAdLoadedNative?.invoke(true, nativeAd)
        }

        val videoOptions = VideoOptions.Builder().setStartMuted(false).build()
        val adOptions = NativeAdOptions.Builder().setVideoOptions(videoOptions).build()
        adBuilder.withNativeAdOptions(adOptions)

        val adLoader = adBuilder.withAdListener(object : AdListener() {
            override fun onAdClicked() {
                super.onAdClicked()
                Log.e(TAG, "NativeAd: onAdClicked()")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                Log.e(TAG, "NativeAd: onAdFailedToLoad() error=${loadAdError.message}")
                context.showToast("Failed to load native ad")
                onAdLoadedNative?.invoke(false, null)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "NativeAd")
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                Log.e(TAG, "NativeAd: onAdLoaded (loadAd) successfully for adUnitId=$adUnitId")
                context.showToast("Native ad loaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "NativeAd")
            }

            override fun onAdImpression() {
                super.onAdImpression()
                Log.e(TAG, "NativeAd: onAdImpression()")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_SHOWN, "NativeAd")
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
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "NativeAd: showLoadedAd requested for adUnitId=$adUnitId")
        if (!shouldShowAd(context)) {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            return
        }
        loadedNativeAd?.let {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            builder.let { builder ->
                val adView = LayoutInflater.from(context).inflate(if (builder.layout == 0) R.layout.admob_small_native_media else builder.layout, null) as NativeAdView
                populateNativeAdView(it, adView, builder)
                builder.frameLayout?.removeAllViews()
                builder.frameLayout?.addView(adView)
                if (builder.frameLayout?.visibility == View.GONE) {
                    builder.frameLayout?.visibility = View.VISIBLE
                }
            }
            it.setOnPaidEventListener { adValue ->
                CoroutineScope(Dispatchers.IO).launch {
                    AdsAnalytics.logAppsFlyerRevenue(
                        adUnitId,
                        "Native",
                        adValue,
                        context.applicationContext
                    )
                }
            }
        }
    }

    override fun destroy() {
        loadedNativeAd?.destroy()
        loadedNativeAd = null
        loadAndShowNativeAd?.destroy()
        loadAndShowNativeAd = null
        exitNativeAd?.destroy()
        exitNativeAd = null
    }

    override fun isAdLoaded(): Boolean {
        return loadedNativeAd != null
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

            builder.adBgColor?.let { adBgColor ->
                clAdBg?.background = GradientDrawable().also { it ->
                    if (builder.showBgStroke) {
                        it.shape = GradientDrawable.RECTANGLE
                        val color = builder.strokeColor?.toColorInt() ?: Color.GRAY
                        val width = builder.strokeWidth ?: 1
                        it.setStroke(width, color)
                    }
                    it.setColor(adBgColor.toColorInt())
                }
            }

            val density = context.resources.displayMetrics.density
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
                visibility =
                    if (nativeAd.store.isNullOrEmpty() || !builder.showPrice) View.GONE else View.VISIBLE
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
                Log.d(TAG, "Ad Close Clicked")
                adView.visibility = View.GONE
                adView.removeAllViews()
                adView.destroy()
                loadedNativeAd?.destroy()
                loadedNativeAd = null
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
            Log.e("Populate Error", "populateNativeAdView: ${e.message}")
        }
    }

    fun loadExitNativeAd(
        @ValidateAdUnitId adUnitId: String,
        onAdLoadedNative: ((Boolean, NativeAd?) -> Unit)? = null
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        Log.e(TAG, "NativeAd: loadExitNativeAd requested for adUnitId=$adUnitId")

        if (!shouldShowAd(context)) {
            Log.e(TAG, "NativeAd: loadExitNativeAd skipped (shouldShowAd returns false)")
            onAdLoadedNative?.invoke(false, null)
            return
        }

        context.showToast("Loading exit native ad")
        val adBuilder = AdLoader.Builder(context, adUnitId)
        adBuilder.forNativeAd { nativeAd ->
            exitNativeAd = nativeAd
            Log.e(TAG, "NativeAd: loadExitNativeAd forNativeAd callback received")
            onAdLoadedNative?.invoke(true, nativeAd)
        }

        val videoOptions = VideoOptions.Builder().setStartMuted(false).build()
        val adOptions = NativeAdOptions.Builder().setVideoOptions(videoOptions).build()
        adBuilder.withNativeAdOptions(adOptions)

        val adLoader = adBuilder.withAdListener(object : AdListener() {
            override fun onAdClicked() {
                super.onAdClicked()
                Log.e(TAG, "NativeAd: onExitNativeAdClicked()")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                Log.e(TAG, "NativeAd: onExitNativeAdFailedToLoad() error=${loadAdError.message}")
                context.showToast("Failed to load exit native ad")
                onAdLoadedNative?.invoke(false, null)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "ExitNative")
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                Log.e(TAG, "NativeAd: onExitNativeLoaded successfully")
                context.showToast("Exit native ad loaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "ExitNative")
            }

            override fun onAdImpression() {
                super.onAdImpression()
                Log.e(TAG, "NativeAd: onExitNativeImpression()")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_SHOWN, "ExitNative")
            }
        }).build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    fun showExitNativeAd(
        builder: NativeAdBuilder,
        @ValidateAdUnitId adUnitId: String
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        if (!shouldShowAd(context)) {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            return
        }
        exitNativeAd?.let {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            builder.let { builder ->
                val adView = LayoutInflater.from(context).inflate(if (builder.layout == 0) R.layout.admob_small_native_media else builder.layout, null) as NativeAdView
                populateNativeAdView(it, adView, builder)
                builder.frameLayout?.removeAllViews()
                builder.frameLayout?.addView(adView)
                if (builder.frameLayout?.visibility == View.GONE) {
                    builder.frameLayout?.visibility = View.VISIBLE
                }
            }
            it.setOnPaidEventListener { adValue ->
                CoroutineScope(Dispatchers.IO).launch {
                    AdsAnalytics.logAppsFlyerRevenue(
                        adUnitId,
                        "ExitNative",
                        adValue,
                        context.applicationContext
                    )
                }
            }
        }
    }


}