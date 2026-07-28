package com.umer_tf.ads.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdTypeTest {

    @Test
    fun `analytics names are unchanged from the pre-enum strings`() {
        // These are live Firebase event names. Changing one silently splits a dashboard in two, so it
        // must be a deliberate act, not a side effect of tidying the enum.
        assertEquals("banner_ad", AdType.BANNER.analyticsName)
        assertEquals("banner_memrec_ad", AdType.BANNER_MEDIUM_RECTANGLE.analyticsName)
        assertEquals("banner_collapsable_ad", AdType.BANNER_COLLAPSIBLE.analyticsName)
        assertEquals("Interstitial_ad", AdType.INTERSTITIAL.analyticsName)
        assertEquals("rewarded_ad", AdType.REWARDED.analyticsName)
        assertEquals("NativeAd", AdType.NATIVE.analyticsName)
        assertEquals("ExitNative", AdType.NATIVE_EXIT.analyticsName)
        assertEquals("OpenAd_Start", AdType.APP_OPEN_START.analyticsName)
        assertEquals("OpenAd_Resume", AdType.APP_OPEN_RESUME.analyticsName)
    }

    @Test
    fun `revenue names round trip`() {
        AdType.entries.forEach { type ->
            assertEquals(type, AdType.fromRevenueName(type.revenueName))
        }
        assertNull(AdType.fromRevenueName("NotAFormat"))
    }

    @Test
    fun `revenue names are distinct so reporting cannot merge two types`() {
        val names = AdType.entries.map { it.revenueName }
        assertEquals(names.distinct().size, names.size)
    }
}
