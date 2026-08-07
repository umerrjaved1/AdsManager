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
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListenerNative
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

    private val TAG = "NativeAd"

    private var loadedNativeAd: NativeAd? = null

    private var exitNativeAd: NativeAd? = null

    /**
     * Loads and shows a native ad.
     * @param builder The builder for the native ad.
     * @param onSuccessListener The listener for the success event.
     */
    @MainThread
    override fun loadAndShow(
        @ValidateAdUnitId adUnitId: String,
        builder: NativeAdBuilder,
        onSuccessListener: OnSuccessListener<Boolean>?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)
        if (!shouldShowAd(context)) {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE
            onSuccessListener?.onSuccess(false)
            return
        }

        if (loadedNativeAd != null) {
            builder.shimmerFrameLayout?.stopShimmer()
            builder.shimmerFrameLayout?.visibility = View.GONE

            val adView = LayoutInflater.from(context).inflate(
                if (builder.layout == 0)
                    R.layout.admob_small_native_media
                else
                    builder.layout,
                null
            ) as NativeAdView

            populateNativeAdView(loadedNativeAd!!, adView, builder)

            builder.frameLayout?.removeAllViews()
            builder.frameLayout?.addView(adView)
            builder.frameLayout?.visibility = View.VISIBLE

            onSuccessListener?.onSuccess(true)
            return
        }

        builder.frameLayout?.visibility = View.GONE
        builder.shimmerFrameLayout?.startShimmer()
        builder.shimmerFrameLayout?.visibility = View.VISIBLE

        val adBuilder = context.let { AdLoader.Builder(it, adUnitId) }
        lateinit var adView: NativeAdView
        // OnLoadedListener implementation.
        adBuilder.forNativeAd { nativeAd ->
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
                Log.d(TAG, "Monetization :- onAdClicked()")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_CLICKED, "NativeAd")
            }

            override fun onAdClosed() {
                super.onAdClosed()
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                Log.d("AdmobNative", "Monetization :- onAdFailedToLoad() " + loadAdError.message)
                onSuccessListener?.onSuccess(false)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "NativeAd")
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                Log.d("AdmobNative", "Monetization :- onAdLoaded (loadAndShow): Admob")
                builder.shimmerFrameLayout?.stopShimmer()
                builder.shimmerFrameLayout?.visibility = View.GONE
                onSuccessListener?.onSuccess(true)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "NativeAd")
            }

            override fun onAdImpression() {
                super.onAdImpression()
                AnalyticsManager.getInstance(context).sendAnalytics(AD_SHOWN, "NativeAd")
            }
        }).build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    /**
     * Loads a native ad.
     * @param adUnitId The ad unit ID for the native ad.
     * @param onSuccessListener The listener for the success event.
     */
    @MainThread
    override fun loadAd(
        @ValidateAdUnitId adUnitId: String,
        onSuccessListener: OnSuccessListenerNative<Boolean,NativeAd?>?,
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)

        if (!shouldShowAd(context)) {
            onSuccessListener?.onSuccess(false,null)
            return
        }
        if (loadedNativeAd != null){
            onSuccessListener?.onSuccess(true, loadedNativeAd!!)
            return
        }

        val adBuilder = AdLoader.Builder(context, adUnitId)
        adBuilder.forNativeAd { nativeAd ->
            loadedNativeAd = nativeAd
            onSuccessListener?.onSuccess(true,nativeAd)
        }

        val videoOptions = VideoOptions.Builder().setStartMuted(false).build()
        val adOptions = NativeAdOptions.Builder().setVideoOptions(videoOptions).build()
        adBuilder.withNativeAdOptions(adOptions)

        val adLoader = adBuilder.withAdListener(object : AdListener() {
            override fun onAdClicked() {
                super.onAdClicked()
                Log.d(TAG, "onAdClicked()")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                Log.d("AdmobNative", "Monetization :- onAdFailedToLoad() " + loadAdError.message)
                context.showToast("Failed to load native ad")
                onSuccessListener?.onSuccess(false,null)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "NativeAd")
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                Log.d("AdmobNative", "Monetization :- onAdLoaded (loadAd): Admob")
                context.showToast("Native ad loaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "NativeAd")
            }

            override fun onAdImpression() {
                super.onAdImpression()
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

            builder.ctaBgColor?.let { ctaBgColor ->
                btnCTA?.background = GradientDrawable().also {
                    it.shape = GradientDrawable.RECTANGLE
                    it.cornerRadius = builder.ctaRadius.toFloat()
                    it.setColor(ctaBgColor.toColorInt())
                    it.setTint(ctaBgColor.toColorInt())
                }
            }

            builder.ctaTextColor?.let { ctaTextColor ->
                btnCTA?.setTextColor(ctaTextColor.toColorInt())
            }

            builder.adTitleColor?.let { adTitleColor ->
                adTitle?.setTextColor(adTitleColor.toColorInt())
            }

            builder.adBodyColor?.let { adBodyColor ->
                adBody?.setTextColor(adBodyColor.toColorInt())
            }

        if (builder.showMedia) {
            adMedia?.let {
                it.visibility = View.VISIBLE
                val adMediaContainer = adView.findViewById<ConstraintLayout>(R.id.constraintLayoutMedia)
                adMediaContainer?.visibility = View.VISIBLE
                adView.mediaView = it.apply {
                    mediaContent = nativeAd.mediaContent
                }
            }
        } else {
            adMedia?.visibility = View.GONE
            val adMediaContainer = adView.findViewById<ConstraintLayout>(R.id.constraintLayoutMedia)
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
                    if (nativeAd.body == null || !builder.showBody) View.INVISIBLE else View.VISIBLE
                text = nativeAd.body
            }
            (callToActionView as? AppCompatButton)?.apply {
                visibility =
                    if (nativeAd.callToAction == null || !builder.showCallToAction) View.INVISIBLE else View.VISIBLE
                text = nativeAd.callToAction
            }
            (iconView as? ImageView)?.apply {
                visibility =
                    if (nativeAd.icon == null || !builder.iconEnabled) View.GONE else View.VISIBLE
                setImageDrawable(nativeAd.icon?.drawable)
            }
            (priceView as? TextView)?.apply {
                visibility =
                    if (nativeAd.price == null || !builder.showPrice) View.INVISIBLE else View.VISIBLE
                text = nativeAd.price
            }
            (storeView as? TextView)?.apply {
                visibility =
                    if (nativeAd.store == null || !builder.showStore) View.INVISIBLE else View.VISIBLE
                text = nativeAd.store
            }
            (starRatingView as? RatingBar)?.apply {
                visibility = if (nativeAd.starRating == null) View.INVISIBLE else View.VISIBLE
                rating = nativeAd.starRating?.toFloat() ?: 0.0f
            }
            (advertiserView as? TextView)?.apply {
                visibility =
                    if (nativeAd.advertiser == null || !builder.showAdvertiser) View.INVISIBLE else View.VISIBLE
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
        onSuccessListener: OnSuccessListenerNative<Boolean, NativeAd?>?
    ) {
        AdUnitIdValidator.validateAdUnitId(adUnitId)

        if (!shouldShowAd(context)) {
            onSuccessListener?.onSuccess(false, null)
            return
        }

        context.showToast("Loading exit native ad")
        val adBuilder = AdLoader.Builder(context, adUnitId)
        adBuilder.forNativeAd { nativeAd ->
            exitNativeAd = nativeAd
            onSuccessListener?.onSuccess(true, nativeAd)
        }

        val videoOptions = VideoOptions.Builder().setStartMuted(false).build()
        val adOptions = NativeAdOptions.Builder().setVideoOptions(videoOptions).build()
        adBuilder.withNativeAdOptions(adOptions)

        val adLoader = adBuilder.withAdListener(object : AdListener() {
            override fun onAdClicked() {
                super.onAdClicked()
                Log.d(TAG, "onExitNativeAdClicked()")
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                super.onAdFailedToLoad(loadAdError)
                Log.d("ExitNative", "Monetization :- onExitNativeAdFailedToLoad() " + loadAdError.message)
                context.showToast("Failed to load exit native ad")
                onSuccessListener?.onSuccess(false, null)
                AnalyticsManager.getInstance(context).sendAnalytics(AD_FAILED, "ExitNative")
            }

            override fun onAdLoaded() {
                super.onAdLoaded()
                Log.d("ExitNative", "Monetization :- onExitNativeLoaded: Admob")
                context.showToast("Exit native ad loaded")
                AnalyticsManager.getInstance(context).sendAnalytics(AD_LOADED, "ExitNative")
            }

            override fun onAdImpression() {
                super.onAdImpression()
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