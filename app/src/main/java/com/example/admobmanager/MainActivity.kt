package com.example.admobmanager

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.facebook.shimmer.ShimmerFrameLayout
import com.umer_tf.ads.domain.ads.native_ad.FullScreenNativeAdActivity
import com.umer_tf.ads.domain.ads.native_ad.NativeAdTheme
import com.umer_tf.ads.domain.ads.native_ad.createNativeAdBuilder
import com.umer_tf.ads.domain.core.AdMobManager
import com.umer_tf.ads.domain.viewmodel.AdViewModel
import com.umer_tf.ads.domain.viewmodel.InterstitialAdUiState
import com.umer_tf.ads.domain.viewmodel.NativeAdUiState
import com.umer_tf.ads.domain.viewmodel.RewardedAdUiState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adMobManager: AdMobManager
    private val adViewModel: AdViewModel by viewModels()

    private val nativeTestAdUnitId = "ca-app-pub-3940256099942544/2247696110"
    private val interstitialTestAdUnitId = "ca-app-pub-3940256099942544/1033173712"
    private val rewardedTestAdUnitId = "ca-app-pub-3940256099942544/5224354917"
    private val appOpenTestAdUnitId = "ca-app-pub-3940256099942544/9257395921"
    private val bannerTestAdUnitId = "ca-app-pub-3940256099942544/6300978111"
    private val collapsibleBannerTestAdUnitId = "ca-app-pub-3940256099942544/2014213617"

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
            .setAppOpenAdStartId(appOpenTestAdUnitId)
            .setAppOpenAdResumeId(appOpenTestAdUnitId)

        setupButtons()

        val defaultTheme = NativeAdTheme.auto(this)

        val adConfigs = listOf(
            Triple(R.id.adContainer_1a, R.id.shimmerContainer_1a, com.umer_tf.ads.R.layout.layout_native_ad_small_1a),
            Triple(R.id.adContainer_1b, R.id.shimmerContainer_1b, com.umer_tf.ads.R.layout.layout_native_ad_small_1b),
            Triple(R.id.adContainer_1c, R.id.shimmerContainer_1c, com.umer_tf.ads.R.layout.layout_native_ad_small_1c),
            Triple(R.id.adContainer_3a, R.id.shimmerContainer_3a, com.umer_tf.ads.R.layout.layout_native_ad_small_3a),
            Triple(R.id.adContainer_3b, R.id.shimmerContainer_3b, com.umer_tf.ads.R.layout.layout_native_ad_small_3b),
            Triple(R.id.adContainer_4, R.id.shimmerContainer_4, com.umer_tf.ads.R.layout.layout_native_ad_small_4),
            Triple(R.id.adContainer_5a, R.id.shimmerContainer_5a, com.umer_tf.ads.R.layout.layout_native_ad_large_5a),
            Triple(R.id.adContainer_6a, R.id.shimmerContainer_6a, com.umer_tf.ads.R.layout.layout_native_ad_large_6a),
            Triple(R.id.adContainer_6b, R.id.shimmerContainer_6b, com.umer_tf.ads.R.layout.layout_native_ad_large_6b),
            Triple(R.id.adContainer_7a, R.id.shimmerContainer_7a, com.umer_tf.ads.R.layout.layout_native_ad_small_7a),
            Triple(R.id.adContainer_7b, R.id.shimmerContainer_7b, com.umer_tf.ads.R.layout.layout_native_ad_small_7b),
            Triple(R.id.adContainer_7c, R.id.shimmerContainer_7c, com.umer_tf.ads.R.layout.layout_native_ad_small_7c)
        )

        // Single-line extension function usage: FrameLayout.createNativeAdBuilder(...)
        val builders = adConfigs.map { (containerId, shimmerId, layoutResId) ->
            findViewById<FrameLayout>(containerId).createNativeAdBuilder(
                shimmerFrameLayout = findViewById(shimmerId),
                layoutResId = layoutResId,
                theme = defaultTheme
            )
        }

        // Custom theme override example for full screen activity
        val customFullscreenTheme = NativeAdTheme.auto(
            context = this,
            darkTheme = NativeAdTheme.dark().copy(ctaBgColor = "#E11D48"),
            lightTheme = NativeAdTheme.light().copy(ctaBgColor = "#DC2626")
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    adViewModel.nativeAdState.collect { state ->
                        when (state) {
                            is NativeAdUiState.Idle -> {
                                adViewModel.loadNativeAd(adMobManager, nativeTestAdUnitId)
                            }
                            is NativeAdUiState.Loading -> {
                                // Shimmers active
                            }
                            is NativeAdUiState.Success -> {
                                builders.forEach { builder ->
                                    adViewModel.showNativeAd(adMobManager, builder, nativeTestAdUnitId)
                                }
                            }
                            is NativeAdUiState.Error -> {
                                builders.forEach { builder ->
                                    builder.shimmerFrameLayout?.stopShimmer()
                                    builder.shimmerFrameLayout?.visibility = View.GONE
                                }
                            }
                        }
                    }
                }

                launch {
                    adViewModel.interstitialAdState.collect { state ->
                        when (state) {
                            is InterstitialAdUiState.Loaded -> Toast.makeText(this@MainActivity, "Interstitial Ad Loaded", Toast.LENGTH_SHORT).show()
                            is InterstitialAdUiState.Error -> Toast.makeText(this@MainActivity, "Interstitial Error: ${state.message}", Toast.LENGTH_SHORT).show()
                            else -> {}
                        }
                    }
                }

                launch {
                    adViewModel.rewardedAdState.collect { state ->
                        when (state) {
                            is RewardedAdUiState.Loaded -> Toast.makeText(this@MainActivity, "Rewarded Ad Loaded", Toast.LENGTH_SHORT).show()
                            is RewardedAdUiState.Shown -> Toast.makeText(this@MainActivity, "Reward Earned: ${state.rewardEarned}", Toast.LENGTH_SHORT).show()
                            is RewardedAdUiState.Error -> Toast.makeText(this@MainActivity, "Rewarded Error: ${state.message}", Toast.LENGTH_SHORT).show()
                            else -> {}
                        }
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnShowFullscreenNative).setOnClickListener {
            FullScreenNativeAdActivity.start(
                context = this,
                adUnitId = nativeTestAdUnitId,
                theme = customFullscreenTheme,
                closeButtonDelayMs = 2000L,
                onAdDismissed = {
                    Toast.makeText(this, "Full screen native ad dismissed", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun setupButtons() {
        // Interstitial Ad Controls
        findViewById<Button>(R.id.btnLoadInterstitial).setOnClickListener {
            adViewModel.loadInterstitialAd(adMobManager, interstitialTestAdUnitId)
        }
        findViewById<Button>(R.id.btnShowInterstitial).setOnClickListener {
            if (adMobManager.interstitialAdLoader.isAdLoaded()) {
                adViewModel.showInterstitialAd(this, adMobManager, interstitialTestAdUnitId)
            } else {
                Toast.makeText(this, "Interstitial ad not loaded. Load first!", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnLoadAndShowInterstitial).setOnClickListener {
            adMobManager.interstitialAdLoader.loadAndShowAd(
                activity = this,
                adUnitId = interstitialTestAdUnitId,
                showDialog = true
            )
        }

        // Rewarded Ad Controls
        findViewById<Button>(R.id.btnLoadRewarded).setOnClickListener {
            adViewModel.loadRewardedAd(this, adMobManager, rewardedTestAdUnitId)
        }
        findViewById<Button>(R.id.btnShowRewarded).setOnClickListener {
            if (adMobManager.rewardedAdLoader.isAdLoaded()) {
                adViewModel.showRewardedAd(this, adMobManager)
            } else {
                Toast.makeText(this, "Rewarded ad not loaded. Load first!", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnLoadAndShowRewarded).setOnClickListener {
            adMobManager.rewardedAdLoader.loadAndShowAd(
                activity = this,
                adUnitId = rewardedTestAdUnitId,
                showDialog = true
            )
        }

        // App Open Ad Controls
        findViewById<Button>(R.id.btnLoadAppOpen).setOnClickListener {
            adMobManager.appOpenAdLoader.loadAppOpenAd(applicationContext) { loaded ->
                Toast.makeText(this, if (loaded) "App Open Ad Loaded" else "App Open Ad Failed to Load", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnShowAppOpen).setOnClickListener {
            if (adMobManager.appOpenAdLoader.isStartAdAvailable()) {
                adMobManager.appOpenAdLoader.showAppOpenAdIfAvailable {
                    Toast.makeText(this, "App Open Ad Closed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "App Open Ad not available. Load first!", Toast.LENGTH_SHORT).show()
            }
        }

        // Banner Ad Controls
        findViewById<Button>(R.id.btnShowAdaptiveBanner).setOnClickListener {
            val frameLayout = findViewById<FrameLayout>(R.id.adContainer_adaptive_banner)
            val shimmerLayout = findViewById<ShimmerFrameLayout>(R.id.shimmerContainer_adaptive_banner)
            adMobManager.bannerAdLoader.showAdaptiveBanner(
                activity = this,
                shimmerFrameLayout = shimmerLayout,
                frameLayout = frameLayout,
                adUnitId = bannerTestAdUnitId
            )
        }

        findViewById<Button>(R.id.btnShowMemRecBanner).setOnClickListener {
            val frameLayout = findViewById<FrameLayout>(R.id.adContainer_memrec_banner)
            val shimmerLayout = findViewById<ShimmerFrameLayout>(R.id.shimmerContainer_memrec_banner)
            adMobManager.bannerAdLoader.showMemRecBanner(
                activity = this,
                frameLayout = frameLayout,
                shimmerFrameLayout = shimmerLayout,
                adUnitId = bannerTestAdUnitId
            )
        }

        findViewById<Button>(R.id.btnShowCollapsibleBanner).setOnClickListener {
            val frameLayout = findViewById<FrameLayout>(R.id.adContainer_collapsible_banner)
            val shimmerLayout = findViewById<ShimmerFrameLayout>(R.id.shimmerContainer_collapsible_banner)
            adMobManager.bannerAdLoader.showCollapsableBanner(
                activity = this,
                frameLayout = frameLayout,
                shimmerFrameLayout = shimmerLayout,
                adUnitId = collapsibleBannerTestAdUnitId,
                isTop = false
            )
        }
    }
}