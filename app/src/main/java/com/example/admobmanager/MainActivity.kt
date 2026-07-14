package com.example.admobmanager

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.shimmer.ShimmerFrameLayout
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.ads.native_ad.NativeAdBuilder
import com.umer_tf.ads.domain.core.AdMobManager

class MainActivity : AppCompatActivity() {

    private lateinit var adMobManager: AdMobManager
    private val testAdUnitId = "ca-app-pub-3940256099942544/2247696110"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        adMobManager = AdMobManager.getInstance(application)

        loadNativeAd("1a", R.id.adContainer_1a, R.id.shimmerContainer_1a, com.umer_tf.ads.R.layout.layout_native_ad_small_1a)
        loadNativeAd("1b", R.id.adContainer_1b, R.id.shimmerContainer_1b, com.umer_tf.ads.R.layout.layout_native_ad_small_1b)
        loadNativeAd("1c", R.id.adContainer_1c, R.id.shimmerContainer_1c, com.umer_tf.ads.R.layout.layout_native_ad_small_1c)
        
        loadNativeAd("3a", R.id.adContainer_3a, R.id.shimmerContainer_3a, com.umer_tf.ads.R.layout.layout_native_ad_small_3a)
        loadNativeAd("3b", R.id.adContainer_3b, R.id.shimmerContainer_3b, com.umer_tf.ads.R.layout.layout_native_ad_small_3b)
        
        loadNativeAd("4", R.id.adContainer_4, R.id.shimmerContainer_4, com.umer_tf.ads.R.layout.layout_native_ad_small_4)
        
        loadNativeAd("5a", R.id.adContainer_5a, R.id.shimmerContainer_5a, com.umer_tf.ads.R.layout.layout_native_ad_large_5a)
        
        loadNativeAd("6a", R.id.adContainer_6a, R.id.shimmerContainer_6a, com.umer_tf.ads.R.layout.layout_native_ad_large_6a)
        loadNativeAd("6b", R.id.adContainer_6b, R.id.shimmerContainer_6b, com.umer_tf.ads.R.layout.layout_native_ad_large_6b)
        
        loadNativeAd("7a", R.id.adContainer_7a, R.id.shimmerContainer_7a, com.umer_tf.ads.R.layout.layout_native_ad_small_7a)
        loadNativeAd("7b", R.id.adContainer_7b, R.id.shimmerContainer_7b, com.umer_tf.ads.R.layout.layout_native_ad_small_7b)
        loadNativeAd("7c", R.id.adContainer_7c, R.id.shimmerContainer_7c, com.umer_tf.ads.R.layout.layout_native_ad_small_7c)
    }

    private fun loadNativeAd(variant: String, containerId: Int, shimmerId: Int, layoutResId: Int) {
        val frameLayout = findViewById<FrameLayout>(containerId)
        val shimmerFrameLayout = findViewById<ShimmerFrameLayout>(shimmerId)

        val adBgColor = String.format("#%06X", 0xFFFFFF and androidx.core.content.ContextCompat.getColor(this, com.umer_tf.ads.R.color.ad_background))
        val ctaBgColor = String.format("#%06X", 0xFFFFFF and androidx.core.content.ContextCompat.getColor(this, com.umer_tf.ads.R.color.ad_cta_background))
        val ctaTextColor = String.format("#%06X", 0xFFFFFF and androidx.core.content.ContextCompat.getColor(this, com.umer_tf.ads.R.color.ad_cta_text))
        val titleColor = String.format("#%06X", 0xFFFFFF and androidx.core.content.ContextCompat.getColor(this, com.umer_tf.ads.R.color.ad_text_primary))
        val bodyColor = String.format("#%06X", 0xFFFFFF and androidx.core.content.ContextCompat.getColor(this, com.umer_tf.ads.R.color.ad_text_primary))

        val builder = NativeAdBuilder.Builder(layoutResId, frameLayout, shimmerFrameLayout)
            .setShowMedia(true)
            .setShowBody(true)
            .setShowCallToAction(true)
            .setAdBgColor(adBgColor)
            .setCtaBgColor(ctaBgColor)
            .setCtaTextColor(ctaTextColor)
            .setAdTitleColor(titleColor)
            .setAdBodyColor(bodyColor)
            .build()

        adMobManager.nativeAdLoader.loadAndShow(
            adUnitId = testAdUnitId,
            builder = builder,
            onSuccessListener = OnSuccessListener { result ->
                // Handled internally by NativeAdBuilder/NativeAd
            }
        )
    }
}