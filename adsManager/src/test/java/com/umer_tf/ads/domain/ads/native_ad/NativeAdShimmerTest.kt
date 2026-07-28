package com.umer_tf.ads.domain.ads.native_ad

import com.umer_tf.ads.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdShimmerTest {

    @Test
    fun `every bundled native layout has a shimmer of its own`() {
        // The bug this guards: an unpaired layout silently fell back to a wrongly-shaped placeholder.
        val bundledLayouts = listOf(
            R.layout.admob_native_fullscreen,
            R.layout.admob_small_native_media,
            R.layout.admob_large_native_media,
            R.layout.admob_native_banner_type,
            R.layout.layout_native_ad_banner,
            R.layout.layout_native_ad_large,
            R.layout.layout_native_ad_large_5a,
            R.layout.layout_native_ad_large_6a,
            R.layout.layout_native_ad_large_6b,
            R.layout.layout_native_ad_large_v2,
            R.layout.layout_native_ad_large_v3,
            R.layout.layout_native_ad_small_1a,
            R.layout.layout_native_ad_small_1b,
            R.layout.layout_native_ad_small_1c,
            R.layout.layout_native_ad_small_1d,
            R.layout.layout_native_ad_small_3a,
            R.layout.layout_native_ad_small_3b,
            R.layout.layout_native_ad_small_4,
            R.layout.layout_native_ad_small_7a,
            R.layout.layout_native_ad_small_7b,
            R.layout.layout_native_ad_small_7c
        )
        val unpaired = bundledLayouts.filterNot { NativeAdShimmer.hasShimmerFor(it) }
        assertTrue("Layouts with no registered shimmer: $unpaired", unpaired.isEmpty())
    }

    @Test
    fun `resolves layout zero to the default layout's shimmer`() {
        // NativeAdBuilder treats layout == 0 as "use the library default".
        assertEquals(
            NativeAdShimmer.resolveShimmerLayout(R.layout.admob_small_native_media),
            NativeAdShimmer.resolveShimmerLayout(0)
        )
    }

    @Test
    fun `unregistered layout falls back instead of throwing`() {
        val unknown = 0x7f123456
        assertFalse(NativeAdShimmer.hasShimmerFor(unknown))
        assertEquals(
            R.layout.adlibrary_shimmer_native_media_small,
            NativeAdShimmer.resolveShimmerLayout(unknown)
        )
    }

    @Test
    fun `register pairs a custom layout`() {
        val custom = 0x7f654321
        NativeAdShimmer.register(custom, R.layout.adlibrary_shimmer_native_small_1a)
        assertTrue(NativeAdShimmer.hasShimmerFor(custom))
        assertEquals(
            R.layout.adlibrary_shimmer_native_small_1a,
            NativeAdShimmer.resolveShimmerLayout(custom)
        )
    }
}
