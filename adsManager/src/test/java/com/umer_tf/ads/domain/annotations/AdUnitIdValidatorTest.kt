package com.umer_tf.ads.domain.annotations

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdUnitIdValidatorTest {

    private var originalStrictMode = false

    @Before
    fun setUp() {
        originalStrictMode = AdUnitIdValidator.strictMode
    }

    @After
    fun tearDown() {
        AdUnitIdValidator.strictMode = originalStrictMode
    }

    @Test
    fun `accepts a well formed ad unit id`() {
        assertTrue(AdUnitIdValidator.isValidAdUnitId("ca-app-pub-3940256099942544/1033173712"))
    }

    @Test
    fun `rejects ids with the wrong shape`() {
        // Too few publisher digits, too few ad digits, missing prefix, missing separator, empty.
        assertFalse(AdUnitIdValidator.isValidAdUnitId("ca-app-pub-394025609994/1033173712"))
        assertFalse(AdUnitIdValidator.isValidAdUnitId("ca-app-pub-3940256099942544/103317371"))
        assertFalse(AdUnitIdValidator.isValidAdUnitId("3940256099942544/1033173712"))
        assertFalse(AdUnitIdValidator.isValidAdUnitId("ca-app-pub-3940256099942544-1033173712"))
        assertFalse(AdUnitIdValidator.isValidAdUnitId(""))
    }

    @Test
    fun `rejects ids padded with whitespace`() {
        // A trailing newline is the classic Remote Config / config-file copy-paste mistake.
        assertFalse(AdUnitIdValidator.isValidAdUnitId(" ca-app-pub-3940256099942544/1033173712"))
        assertFalse(AdUnitIdValidator.isValidAdUnitId("ca-app-pub-3940256099942544/1033173712\n"))
    }

    @Test
    fun `non strict mode reports failure instead of throwing`() {
        AdUnitIdValidator.strictMode = false
        assertFalse(AdUnitIdValidator.validateAdUnitId("nonsense"))
        assertTrue(AdUnitIdValidator.validateAdUnitId("ca-app-pub-3940256099942544/1033173712"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `strict mode throws so typos surface during development`() {
        AdUnitIdValidator.strictMode = true
        AdUnitIdValidator.validateAdUnitId("nonsense")
    }

    @Test
    fun `identifies Google test ad unit ids`() {
        assertTrue(AdUnitIdValidator.isTestAdUnitId("ca-app-pub-3940256099942544/1033173712"))
        assertFalse(AdUnitIdValidator.isTestAdUnitId("ca-app-pub-1234567890123456/1033173712"))
    }
}
