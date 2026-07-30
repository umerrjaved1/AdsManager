package com.example.admobmanager

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.umer_tf.ads.domain.ads.banner.BannerAdType
import com.umer_tf.ads.domain.ads.native_ad.FullScreenNativeAdActivity
import com.umer_tf.ads.domain.ads.native_ad.NativeAdTheme
import com.umer_tf.ads.domain.core.AdMobManager
import com.umer_tf.ads.domain.viewmodel.AdEvent
import com.umer_tf.ads.domain.viewmodel.AdViewModel
import com.umer_tf.ads.domain.viewmodel.FullScreenAdUiState
import com.umer_tf.ads.domain.viewmodel.bindBanner
import com.umer_tf.ads.domain.viewmodel.bindNativeAd
import kotlinx.coroutines.launch

/**
 * Exercises the library through [AdViewModel] - every format except app open, which stays on
 * `AdMobManager.appOpenAdLoader` because it is driven by the process lifecycle.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var adMobManager: AdMobManager
    private val ads: AdViewModel by viewModels()

    private val nativeTestAdUnitId = "ca-app-pub-3940256099942544/2247696110"
    private val interstitialTestAdUnitId = "ca-app-pub-3940256099942544/1033173712"
    private val rewardedTestAdUnitId = "ca-app-pub-3940256099942544/5224354917"
    private val appOpenTestAdUnitId = "ca-app-pub-3940256099942544/9257395921"
    private val bannerTestAdUnitId = "ca-app-pub-3940256099942544/6300978111"
    private val collapsibleBannerTestAdUnitId = "ca-app-pub-3940256099942544/2014213617"

    /** Every bundled shape, as `layout code to container`. One FrameLayout per slot. */
    private val nativeSlots = listOf(
        "1a" to R.id.adContainer_1a,
        "1b" to R.id.adContainer_1b,
        "1c" to R.id.adContainer_1c,
        "3a" to R.id.adContainer_3a,
        "3b" to R.id.adContainer_3b,
        "4a" to R.id.adContainer_4,
        "5a" to R.id.adContainer_5a,
        "6a" to R.id.adContainer_6a,
        "6b" to R.id.adContainer_6b,
        "7a" to R.id.adContainer_7a,
        "7b" to R.id.adContainer_7b,
        "7c" to R.id.adContainer_7c
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // initialize() is not optional: getInstance() only builds the facade, and nothing else in the
        // library starts MobileAds. Without it every request fails - usually reported as
        // "Network error", which sends you looking at connectivity instead of at this line.
        adMobManager = AdMobManager.getInstance(application)
            .setAppOpenAdStartId(appOpenTestAdUnitId)
            .setAppOpenAdResumeId(appOpenTestAdUnitId)
        adMobManager.initialize()

        val theme = NativeAdTheme.auto(this)

        // The whole native integration: a shape code, an ad unit and one container. The shimmer is
        // inflated into that container and replaced by the ad. Each slot gets its own key (derived
        // from the container's view id), so the twelve shapes load and render independently.
        nativeSlots.forEach { (layoutCode, containerId) ->
            ads.bindNativeAd(
                owner = this,
                adUnitId = nativeTestAdUnitId,
                layout = layoutCode,
                container = findViewById(containerId),
                theme = theme
            )
        }

        observeFullScreenAds()
        setupButtons()

        val customFullscreenTheme = NativeAdTheme.auto(
            context = this,
            darkTheme = NativeAdTheme.dark().copy(ctaBgColor = "#E11D48"),
            lightTheme = NativeAdTheme.light().copy(ctaBgColor = "#DC2626")
        )

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

    private fun observeFullScreenAds() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ads.interstitialState.collect { state ->
                        if (state is FullScreenAdUiState.Loaded) toast("Interstitial loaded")
                        if (state is FullScreenAdUiState.Failed) toast("Interstitial: ${state.failure.message}")
                    }
                }
                launch {
                    ads.rewardedState.collect { state ->
                        if (state is FullScreenAdUiState.Loaded) toast("Rewarded loaded")
                        if (state is FullScreenAdUiState.Failed) toast("Rewarded: ${state.failure.message}")
                    }
                }
                // One-shot outcomes. Navigation belongs here, not in the state flows above, so it
                // cannot fire a second time after a rotation.
                launch {
                    ads.events.collect { event ->
                        when (event) {
                            is AdEvent.RewardEarned -> toast("Reward earned")
                            is AdEvent.ShowFailed -> toast("Show failed: ${event.failure.message}")
                            is AdEvent.Dismissed -> toast("${event.adType.revenueName} dismissed")
                        }
                    }
                }
            }
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnLoadInterstitial).setOnClickListener {
            ads.loadInterstitial(interstitialTestAdUnitId)
        }
        findViewById<Button>(R.id.btnShowInterstitial).setOnClickListener {
            ads.showInterstitial(this, interstitialTestAdUnitId)
        }
        findViewById<Button>(R.id.btnLoadAndShowInterstitial).setOnClickListener {
            ads.loadAndShowInterstitial(this, interstitialTestAdUnitId)
        }

        findViewById<Button>(R.id.btnLoadRewarded).setOnClickListener {
            ads.loadRewarded(this, rewardedTestAdUnitId)
        }
        findViewById<Button>(R.id.btnShowRewarded).setOnClickListener {
            ads.showRewarded(this, rewardedTestAdUnitId)
        }
        findViewById<Button>(R.id.btnLoadAndShowRewarded).setOnClickListener {
            ads.loadAndShowRewarded(this, rewardedTestAdUnitId)
        }

        // App open stays on AdMobManager: it is owned by the process lifecycle, not by this screen.
        findViewById<Button>(R.id.btnLoadAppOpen).setOnClickListener {
            adMobManager.appOpenAdLoader.loadAppOpenAd(applicationContext) { loaded ->
                toast(if (loaded) "App Open loaded" else "App Open failed to load")
            }
        }
        findViewById<Button>(R.id.btnShowAppOpen).setOnClickListener {
            if (adMobManager.appOpenAdLoader.isStartAdAvailable()) {
                adMobManager.appOpenAdLoader.showAppOpenAdIfAvailable { toast("App Open closed") }
            } else {
                toast("App Open not available. Load first!")
            }
        }

        // The adaptive banner is bound once: bindBanner requests it and forwards pause/resume/destroy,
        // so there is no lifecycle boilerplate in this Activity at all.
        ads.bindBanner(
            activity = this,
            owner = this,
            adUnitId = bannerTestAdUnitId,
            container = findViewById(R.id.adContainer_adaptive_banner),
            type = BannerAdType.ADAPTIVE
        )

        // The buttons use the lower-level showBanner so a shape can be re-requested on demand.
        findViewById<Button>(R.id.btnShowAdaptiveBanner).setOnClickListener {
            ads.showBanner(
                activity = this,
                adUnitId = bannerTestAdUnitId,
                container = findViewById(R.id.adContainer_adaptive_banner),
                type = BannerAdType.ADAPTIVE
            )
        }
        findViewById<Button>(R.id.btnShowMemRecBanner).setOnClickListener {
            ads.showBanner(
                activity = this,
                adUnitId = bannerTestAdUnitId,
                container = findViewById(R.id.adContainer_memrec_banner),
                type = BannerAdType.MEDIUM_RECTANGLE
            )
        }
        findViewById<Button>(R.id.btnShowCollapsibleBanner).setOnClickListener {
            ads.showBanner(
                activity = this,
                adUnitId = collapsibleBannerTestAdUnitId,
                container = findViewById(R.id.adContainer_collapsible_banner),
                type = BannerAdType.COLLAPSIBLE_BOTTOM
            )
        }
    }

    override fun onPause() {
        ads.pauseBanners()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        ads.resumeBanners()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
