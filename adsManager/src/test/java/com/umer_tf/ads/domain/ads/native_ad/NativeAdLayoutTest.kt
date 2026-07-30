package com.umer_tf.ads.domain.ads.native_ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The short-code lookup is the whole public surface for choosing a native ad shape, so a code that
 * silently resolves to the wrong layout - or to nothing - is an integration bug the app cannot see.
 */
class NativeAdLayoutTest {

    @Test
    fun `resolves plain codes`() {
        assertSame(NativeAdLayout.SMALL_1A, NativeAdLayout.from("1a"))
        assertSame(NativeAdLayout.SMALL_4, NativeAdLayout.from("4"))
        assertSame(NativeAdLayout.LARGE_V2, NativeAdLayout.from("v2"))
        assertSame(NativeAdLayout.LARGE, NativeAdLayout.from("large"))
        assertSame(NativeAdLayout.BANNER, NativeAdLayout.from("banner"))
    }

    @Test
    fun `4a is accepted as an alias for the small_4 layout`() {
        assertSame(NativeAdLayout.SMALL_4, NativeAdLayout.from("4a"))
    }

    @Test
    fun `matching ignores case separators and prefixes`() {
        val expected = NativeAdLayout.SMALL_1A
        listOf(
            "1A",
            "small_1a",
            "SMALL_1A",
            "small 1a",
            "Small-1A",
            "layout_native_ad_small_1a",
            "native_ad_small_1a"
        ).forEach { input ->
            assertSame("\"$input\" should resolve to $expected", expected, NativeAdLayout.from(input))
        }
    }

    @Test
    fun `large is not mangled by prefix stripping`() {
        // "large" is both a strippable prefix and a valid code; stripping it would leave nothing.
        assertSame(NativeAdLayout.LARGE, NativeAdLayout.from("large"))
        assertSame(NativeAdLayout.LARGE_5A, NativeAdLayout.from("large_5a"))
        assertSame(NativeAdLayout.LARGE_5A, NativeAdLayout.from("5a"))
    }

    @Test
    fun `unknown codes resolve to null and fall back to the default`() {
        assertNull(NativeAdLayout.from("99z"))
        assertNull(NativeAdLayout.from(""))
        assertNull(NativeAdLayout.from("   "))
        assertSame(NativeAdLayout.DEFAULT, NativeAdLayout.fromOrDefault("99z"))
        assertSame(NativeAdLayout.LARGE, NativeAdLayout.fromOrDefault("99z", NativeAdLayout.LARGE))
    }

    @Test
    fun `every constant round-trips through its own code and name`() {
        NativeAdLayout.entries.forEach { layout ->
            assertSame(
                "code \"${layout.code}\" must resolve back to $layout",
                layout,
                NativeAdLayout.from(layout.code)
            )
            assertSame(
                "name \"${layout.name}\" must resolve back to $layout",
                layout,
                NativeAdLayout.from(layout.name)
            )
        }
    }

    @Test
    fun `codes are unique`() {
        val codes = NativeAdLayout.entries.map { it.code }
        assertEquals("duplicate codes: $codes", codes.size, codes.toSet().size)
    }

    @Test
    fun `every constant points at a real layout`() {
        NativeAdLayout.entries.forEach { layout ->
            assertTrue("${layout.name} has no layout resource", layout.layoutResId != 0)
        }
    }

    @Test
    fun `every constant has a registered shimmer of its own`() {
        // An unregistered layout falls back to the small-media shimmer and flashes a wrong-sized
        // placeholder, which is exactly the failure NativeAdShimmer exists to prevent.
        NativeAdLayout.entries.forEach { layout ->
            assertTrue(
                "${layout.name} (${layout.code}) is not paired in NativeAdShimmer.pairings",
                NativeAdShimmer.hasShimmerFor(layout.layoutResId)
            )
        }
    }

    @Test
    fun `default is a usable layout`() {
        assertNotNull(NativeAdLayout.from(NativeAdLayout.DEFAULT.code))
    }
}
