package com.umer_tf.ads.domain.ads.native_ad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.umer_tf.ads.domain.ads.listeners.OnSuccessListener
import com.umer_tf.ads.domain.annotations.AdUnitIdValidator
import com.umer_tf.ads.domain.annotations.ValidateAdUnitId
import com.umer_tf.ads.domain.core.AdMobManager
import com.umer_tf.ads.domain.utils.AdsLog
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
    private var adResolved = false
    private var closeButtonRunnable: Runnable? = null
    private var loadTimeoutRunnable: Runnable? = null

    /** Identifies this instance's entry in [pendingDismissCallbacks]. */
    private var launchToken: String? = null

    private lateinit var adContainer: FrameLayout
    private lateinit var shimmerContainer: ShimmerFrameLayout
    private lateinit var btnClose: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security check: only the companion launcher hands out valid tokens, so a direct external
        // intent has nothing to present here.
        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        if (token == null || !pendingLaunchTokens.remove(token)) {
            AdsLog.e(TAG, "FullScreenNativeAdActivity blocked: Attempted to start activity directly or outside companion launcher!")
            finish()
            return
        }
        launchToken = token

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

        val shimmerHost = findViewById<FrameLayout>(R.id.shimmerHost)
        val theme = reconstructThemeFromIntent(intent)

        // Shimmer shaped like the requested native layout, resolved from the shared registry so it
        // stays in sync with whatever layouts the app registers.
        val attachedShimmer = NativeAdShimmer.attachTo(shimmerHost, layoutResId, theme)
        if (attachedShimmer == null) {
            AdsLog.e(TAG, "Unable to inflate a shimmer for layout $layoutResId, finishing")
            finish()
            return
        }
        shimmerContainer = attachedShimmer

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

        // Hard deadline. System back is disabled and btnClose only appears once an ad has loaded, so
        // an SDK callback that never arrives would otherwise trap the user on a blank screen with no
        // way out at all.
        val loadTimeoutMs = intent.getLongExtra(EXTRA_LOAD_TIMEOUT_MS, DEFAULT_LOAD_TIMEOUT_MS)
        loadTimeoutRunnable = Runnable {
            if (!adResolved && !isFinishing && !isDestroyed) {
                AdsLog.e(TAG, "Full screen ad did not resolve within ${loadTimeoutMs}ms, closing")
                shimmerContainer.stopAndHide()
                dismissAd()
            }
        }
        handler.postDelayed(loadTimeoutRunnable!!, loadTimeoutMs)

        // Load & Show native ad using NativeAdLoader
        val adMobManager = AdMobManager.getInstance(application)
        adMobManager.nativeAdLoader.loadAndShow(
            adUnitId,
            builder,
            this,
            // The loader delivers this on the main thread (every SDK callback body is funnelled
            // through onMainThread), so touching views straight from here is safe.
            OnSuccessListener { success ->
                adResolved = true
                loadTimeoutRunnable?.let { handler.removeCallbacks(it) }
                loadTimeoutRunnable = null
                if (success) {
                    AdsLog.d(TAG, "Full screen ad loaded and shown successfully. Starting close button delay timer (${closeDelayMs}ms)")
                    // Start close button delay timer ONLY AFTER ad is loaded and shown
                    closeButtonRunnable = Runnable {
                        if (!isFinishing && !isDestroyed) {
                            btnClose.visibility = View.VISIBLE
                        }
                    }
                    handler.postDelayed(closeButtonRunnable!!, closeDelayMs)
                } else {
                    AdsLog.e(TAG, "Full screen ad failed to load, finishing activity immediately")
                    shimmerContainer.stopAndHide()
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

    private fun dismissAd() {
        if (!isDismissed) {
            isDismissed = true
            cancelPendingCallbacks()
            consumeDismissCallback()?.invoke()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingCallbacks()
        if (!isDismissed) {
            isDismissed = true
            consumeDismissCallback()?.invoke()
        }
        // Belt and braces: never leave this instance's entry behind, even on an abnormal teardown.
        launchToken?.let {
            pendingDismissCallbacks.remove(it)
            pendingLaunchTokens.remove(it)
        }
    }

    private fun cancelPendingCallbacks() {
        closeButtonRunnable?.let { handler.removeCallbacks(it) }
        closeButtonRunnable = null
        loadTimeoutRunnable?.let { handler.removeCallbacks(it) }
        loadTimeoutRunnable = null
    }

    /** Removes and returns this instance's callback, so it can only ever fire once. */
    private fun consumeDismissCallback(): (() -> Unit)? =
        launchToken?.let { pendingDismissCallbacks.remove(it) }

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
        /**
         * Single-use launch tokens, and the dismissal callback belonging to each.
         *
         * These were previously two plain statics, so a second [start] call overwrote the first
         * launch's token - invalidating an Activity that had not started yet - and replaced its
         * callback, which then never fired. Keying by token makes concurrent launches independent, and
         * each entry is removed as soon as it is consumed so no launching Activity is retained.
         */
        private val pendingLaunchTokens: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf())
        private val pendingDismissCallbacks =
            java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

        /** How long to wait for the ad to resolve before closing the Activity. */
        const val DEFAULT_LOAD_TIMEOUT_MS: Long = 15_000L

        private const val EXTRA_LAUNCH_TOKEN = "extra_launch_token"
        private const val EXTRA_LOAD_TIMEOUT_MS = "extra_load_timeout_ms"
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
         *
         * @param loadTimeoutMs Deadline for the ad to resolve. The Activity closes itself and invokes
         *   [onAdDismissed] if the SDK never calls back, so the user is never trapped.
         */
        @JvmStatic
        @JvmOverloads
        fun start(
            context: Context,
            @ValidateAdUnitId adUnitId: String,
            theme: NativeAdTheme = NativeAdTheme.auto(context),
            closeButtonDelayMs: Long = 2000L,
            @LayoutRes layoutResId: Int = R.layout.admob_native_fullscreen,
            loadTimeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
            onAdDismissed: (() -> Unit)? = null
        ) {
            // A false return means "skip": strictMode has already thrown if the host wanted a malformed
            // id to be fatal. Here it must still close the loop via onAdDismissed, or gated navigation stalls.
            if (!AdUnitIdValidator.validateAdUnitId(adUnitId)) {
                AdsLog.e(TAG_STATIC, "start: malformed ad unit id \"$adUnitId\"")
                // Nothing was started, so the caller still needs to be released.
                onAdDismissed?.invoke()
                return
            }

            val launchToken = UUID.randomUUID().toString()
            pendingLaunchTokens.add(launchToken)
            onAdDismissed?.let { pendingDismissCallbacks[launchToken] = it }

            val intent = Intent(context, FullScreenNativeAdActivity::class.java).apply {
                putExtra(EXTRA_LOAD_TIMEOUT_MS, loadTimeoutMs)
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
            runCatching { context.startActivity(intent) }.onFailure { error ->
                // Nothing will consume the token, so clean up here instead of leaking the callback.
                AdsLog.e(TAG_STATIC, "start: unable to launch full screen ad -> ${error.message}")
                pendingLaunchTokens.remove(launchToken)
                pendingDismissCallbacks.remove(launchToken)?.invoke()
            }
        }

        private const val TAG_STATIC = "AdsManager_FullScreen"
    }
}
