package com.umer_tf.ads.domain.ads.native_ad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.shimmer.ShimmerFrameLayout
import com.umer_tf.ads.R
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.core.AdMobManager
import java.util.UUID

/**
 * Dedicated Activity for presenting full-screen Native Ads with package protection,
 * edge-to-edge system insets padding, matching shimmer layouts, disabled system back button,
 * post-ad-load close button delay timer, and automatic activity dismissal on ad load failure.
 */
class FullScreenNativeAdActivity : AppCompatActivity() {

    private val TAG = "AdsManager_FullScreen"
    private val handler = Handler(Looper.getMainLooper())
    private var isDismissed = false
    private var closeButtonRunnable: Runnable? = null

    private lateinit var adContainer: FrameLayout
    private lateinit var shimmerContainer: ShimmerFrameLayout
    private lateinit var btnClose: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security check: Ensure activity is launched only via official companion launcher
        val launchToken = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        if (launchToken == null || launchToken != activeLaunchToken) {
            Log.e(TAG, "FullScreenNativeAdActivity blocked: Attempted to start activity directly or outside companion launcher!")
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_full_screen_native_ad)

        val rootView = findViewById<View>(R.id.rootView)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            rootView.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        adContainer = findViewById(R.id.adContainer)
        btnClose = findViewById(R.id.btnClose)

        val adUnitId = intent.getStringExtra(EXTRA_AD_UNIT_ID) ?: ""
        val closeDelayMs = intent.getLongExtra(EXTRA_CLOSE_DELAY_MS, 2000L)
        val layoutResId = intent.getIntExtra(EXTRA_LAYOUT_RES_ID, R.layout.admob_native_fullscreen)

        // Dynamically inflate matching full-screen/large shimmer layout for the requested native layout
        val shimmerHost = findViewById<FrameLayout>(R.id.shimmerHost)
        val shimmerLayoutResId = getMatchingShimmerLayoutResId(layoutResId)
        val inflatedShimmerView = layoutInflater.inflate(shimmerLayoutResId, shimmerHost, false)
        shimmerHost.addView(inflatedShimmerView)

        shimmerContainer = (inflatedShimmerView as? ShimmerFrameLayout)
            ?: (shimmerHost.getChildAt(0) as ShimmerFrameLayout)

        val theme = reconstructThemeFromIntent(intent)

        // Apply NativeAdTheme background & title text color to activity root, containers, and close button
        kotlin.runCatching {
            val bgInt = theme.adBgColor.toColorInt()
            rootView.setBackgroundColor(bgInt)
            adContainer.setBackgroundColor(bgInt)
            shimmerHost.setBackgroundColor(bgInt)
        }

        kotlin.runCatching {
            val titleColorInt = theme.adTitleColor.toColorInt()
            btnClose.setColorFilter(titleColorInt)
        }

        theme.applyShimmerTo(shimmerContainer)

        shimmerContainer.startShimmer()
        shimmerContainer.visibility = View.VISIBLE
        adContainer.visibility = View.GONE
        btnClose.visibility = View.GONE

        // Build NativeAdBuilder with target full-screen layout and theme
        val builder = NativeAdBuilder.Builder(layoutResId, adContainer, shimmerContainer)
            .setShowMedia(true)
            .setShowBody(true)
            .setShowCallToAction(true)
            .setIconEnabled(true)
            .setTheme(theme)
            .build()

        // Load & Show native ad using NativeAdLoader
        val adMobManager = AdMobManager.getInstance(application)
        adMobManager.nativeAdLoader.loadAndShow(
            adUnitId = adUnitId,
            builder = builder,
            onAdLoaded = { success ->
                if (success) {
                    Log.e(TAG, "Full screen ad loaded and shown successfully. Starting close button delay timer (${closeDelayMs}ms)")
                    // Start close button delay timer ONLY AFTER ad is loaded and shown
                    closeButtonRunnable = Runnable {
                        if (!isFinishing && !isDestroyed) {
                            btnClose.visibility = View.VISIBLE
                        }
                    }
                    handler.postDelayed(closeButtonRunnable!!, closeDelayMs)
                } else {
                    Log.e(TAG, "Full screen ad failed to load, finishing activity immediately")
                    shimmerContainer.stopShimmer()
                    shimmerContainer.visibility = View.GONE
                    dismissAd()
                }
            }
        )

        btnClose.setOnClickListener {
            dismissAd()
        }

        // Disable system back button completely so the user must tap the cross button to exit
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // System back press is disabled by design. Do nothing.
            }
        })
    }

    private fun getMatchingShimmerLayoutResId(@LayoutRes layoutResId: Int): Int {
        return when (layoutResId) {
            R.layout.admob_native_fullscreen -> R.layout.adlibrary_shimmer_native_fullscreen
            R.layout.admob_large_native_media -> R.layout.adlibrary_shimmer_native_large_v2
            R.layout.layout_native_ad_large_5a -> R.layout.adlibrary_shimmer_native_large_5a
            R.layout.layout_native_ad_large_6a -> R.layout.adlibrary_shimmer_native_large_6a
            R.layout.layout_native_ad_large_6b -> R.layout.adlibrary_shimmer_native_large_6b
            R.layout.layout_native_ad_large_v2 -> R.layout.adlibrary_shimmer_native_large_v2
            R.layout.layout_native_ad_large_v3 -> R.layout.adlibrary_shimmer_native_large_v2
            R.layout.layout_native_ad_small_1a -> R.layout.adlibrary_shimmer_native_small_1a
            R.layout.layout_native_ad_small_1b -> R.layout.adlibrary_shimmer_native_small_1b
            R.layout.layout_native_ad_small_1c -> R.layout.adlibrary_shimmer_native_small_1c
            R.layout.layout_native_ad_small_3a -> R.layout.adlibrary_shimmer_native_small_3a
            R.layout.layout_native_ad_small_3b -> R.layout.adlibrary_shimmer_native_small_3b
            R.layout.layout_native_ad_small_4  -> R.layout.adlibrary_shimmer_native_small_4
            R.layout.layout_native_ad_small_7a -> R.layout.adlibrary_shimmer_native_small_7a
            R.layout.layout_native_ad_small_7b -> R.layout.adlibrary_shimmer_native_small_7b
            R.layout.layout_native_ad_small_7c -> R.layout.adlibrary_shimmer_native_small_7c
            else -> R.layout.adlibrary_shimmer_native_fullscreen
        }
    }

    private fun dismissAd() {
        if (!isDismissed) {
            isDismissed = true
            closeButtonRunnable?.let { handler.removeCallbacks(it) }
            onDismissCallback?.invoke()
            onDismissCallback = null
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeButtonRunnable?.let { handler.removeCallbacks(it) }
        if (!isDismissed) {
            onDismissCallback?.invoke()
            onDismissCallback = null
        }
    }

    private fun reconstructThemeFromIntent(intent: Intent): NativeAdTheme {
        val autoDefault = NativeAdTheme.auto(this)
        return NativeAdTheme(
            adBgColor = intent.getStringExtra(EXTRA_BG_COLOR) ?: autoDefault.adBgColor,
            adTitleColor = intent.getStringExtra(EXTRA_TITLE_COLOR) ?: autoDefault.adTitleColor,
            adBodyColor = intent.getStringExtra(EXTRA_BODY_COLOR) ?: autoDefault.adBodyColor,
            ctaBgColor = intent.getStringExtra(EXTRA_CTA_BG_COLOR) ?: autoDefault.ctaBgColor,
            ctaTextColor = intent.getStringExtra(EXTRA_CTA_TEXT_COLOR) ?: autoDefault.ctaTextColor,
            strokeColor = intent.getStringExtra(EXTRA_STROKE_COLOR) ?: autoDefault.strokeColor,
            showBgStroke = intent.getBooleanExtra(EXTRA_SHOW_STROKE, true),
            strokeWidth = intent.getIntExtra(EXTRA_STROKE_WIDTH, 1),
            ctaRadius = intent.getIntExtra(EXTRA_CTA_RADIUS, 16),
            shimmerBaseColor = intent.getStringExtra(EXTRA_SHIMMER_BASE) ?: autoDefault.shimmerBaseColor,
            shimmerHighlightColor = intent.getStringExtra(EXTRA_SHIMMER_HIGHLIGHT) ?: autoDefault.shimmerHighlightColor
        )
    }

    companion object {
        private var activeLaunchToken: String? = null
        private var onDismissCallback: (() -> Unit)? = null

        private const val EXTRA_LAUNCH_TOKEN = "extra_launch_token"
        private const val EXTRA_AD_UNIT_ID = "extra_ad_unit_id"
        private const val EXTRA_CLOSE_DELAY_MS = "extra_close_delay_ms"
        private const val EXTRA_LAYOUT_RES_ID = "extra_layout_res_id"
        private const val EXTRA_BG_COLOR = "extra_bg_color"
        private const val EXTRA_TITLE_COLOR = "extra_title_color"
        private const val EXTRA_BODY_COLOR = "extra_body_color"
        private const val EXTRA_CTA_BG_COLOR = "extra_cta_bg_color"
        private const val EXTRA_CTA_TEXT_COLOR = "extra_cta_text_color"
        private const val EXTRA_STROKE_COLOR = "extra_stroke_color"
        private const val EXTRA_SHOW_STROKE = "extra_show_stroke"
        private const val EXTRA_STROKE_WIDTH = "extra_stroke_width"
        private const val EXTRA_CTA_RADIUS = "extra_cta_radius"
        private const val EXTRA_SHIMMER_BASE = "extra_shimmer_base"
        private const val EXTRA_SHIMMER_HIGHLIGHT = "extra_shimmer_highlight"

        /**
         * Official launcher function for FullScreenNativeAdActivity.
         * Package protected and secure against external direct intents.
         */
        @JvmStatic
        fun start(
            context: Context,
            @ValidateAdUnitId adUnitId: String,
            theme: NativeAdTheme = NativeAdTheme.auto(context),
            closeButtonDelayMs: Long = 2000L,
            @LayoutRes layoutResId: Int = R.layout.admob_native_fullscreen,
            onAdDismissed: (() -> Unit)? = null
        ) {
            AdUnitIdValidator.validateAdUnitId(adUnitId)
            onDismissCallback = onAdDismissed

            val launchToken = UUID.randomUUID().toString()
            activeLaunchToken = launchToken

            val intent = Intent(context, FullScreenNativeAdActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_TOKEN, launchToken)
                putExtra(EXTRA_AD_UNIT_ID, adUnitId)
                putExtra(EXTRA_CLOSE_DELAY_MS, closeButtonDelayMs)
                putExtra(EXTRA_LAYOUT_RES_ID, layoutResId)
                putExtra(EXTRA_BG_COLOR, theme.adBgColor)
                putExtra(EXTRA_TITLE_COLOR, theme.adTitleColor)
                putExtra(EXTRA_BODY_COLOR, theme.adBodyColor)
                putExtra(EXTRA_CTA_BG_COLOR, theme.ctaBgColor)
                putExtra(EXTRA_CTA_TEXT_COLOR, theme.ctaTextColor)
                putExtra(EXTRA_STROKE_COLOR, theme.strokeColor)
                putExtra(EXTRA_SHOW_STROKE, theme.showBgStroke)
                putExtra(EXTRA_STROKE_WIDTH, theme.strokeWidth)
                putExtra(EXTRA_CTA_RADIUS, theme.ctaRadius)
                putExtra(EXTRA_SHIMMER_BASE, theme.shimmerBaseColor)
                putExtra(EXTRA_SHIMMER_HIGHLIGHT, theme.shimmerHighlightColor)

                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
