package com.umer_tf.ads.domain.consent

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdsConsentGateTest {

    @Before
    @After
    fun reset() {
        AdsConsentGate.reset()
    }

    @Test
    fun `allows requests before any consent flow runs`() {
        // Apps that never gather consent must keep working; they get a warning, not a block.
        assertTrue(AdsConsentGate.allowsAdRequests())
    }

    @Test
    fun `blocks requests once enforcement starts and consent is not yet granted`() {
        // This is the window that mattered: enforcement is on, but UMP has not answered yet.
        AdsConsentGate.isEnforced = true
        assertFalse(AdsConsentGate.allowsAdRequests())
    }

    @Test
    fun `allows requests after consent permits them`() {
        AdsConsentGate.update(canRequestAds = true)
        assertTrue(AdsConsentGate.isEnforced)
        assertTrue(AdsConsentGate.allowsAdRequests())
    }

    @Test
    fun `blocks requests after consent denies them`() {
        AdsConsentGate.update(canRequestAds = false)
        assertFalse(AdsConsentGate.allowsAdRequests())
    }

    @Test
    fun `update flips back and forth`() {
        AdsConsentGate.update(canRequestAds = true)
        assertTrue(AdsConsentGate.allowsAdRequests())
        AdsConsentGate.update(canRequestAds = false)
        assertFalse(AdsConsentGate.allowsAdRequests())
    }

    @Test
    fun `reset returns the gate to unenforced`() {
        AdsConsentGate.update(canRequestAds = false)
        AdsConsentGate.reset()
        assertFalse(AdsConsentGate.isEnforced)
        assertTrue(AdsConsentGate.allowsAdRequests())
    }
}
