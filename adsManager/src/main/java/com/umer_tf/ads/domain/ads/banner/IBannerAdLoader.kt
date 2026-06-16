package com.umer_tf.ads.domain.ads.banner

import android.app.Activity
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout

interface IBannerAdLoader {

    fun showAdaptiveBanner(
        activity: Activity,
        shimmerFrameLayout: ShimmerFrameLayout?,
        frameLayout: FrameLayout,
        adUnitId: String
    )


    fun showMemRecBanner(
        activity: Activity,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?=null,
        adUnitId: String
    )

    fun showCollapsableBanner(
        activity: Activity,
        frameLayout: FrameLayout,
        shimmerFrameLayout: ShimmerFrameLayout?=null,
        adUnitId: String,
        isTop: Boolean
    )
}