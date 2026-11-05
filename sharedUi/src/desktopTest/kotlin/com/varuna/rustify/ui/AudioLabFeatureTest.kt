package com.varuna.rustify.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 11 item a / Task 17: Audio Lab and Chat feature gating.
 *
 * Audio Lab is supported on Android and desktop; iOS keeps it hidden.
 * Chat is supported on Android (Gemini Nano) and desktop (DJ model server), but null on iOS.
 */
class AudioLabFeatureTest {

    @Test
    fun audioLabFeatureEnabledOnDesktop() {
        val platform = DesktopAppPlatform("ES")
        val services = DesktopAppServices(platform)
        assertTrue(services.can(AppServices.Feature.AUDIO_LAB), "Desktop must advertise AUDIO_LAB")
    }

    @Test
    fun chatModelAvailableOnDesktop() {
        val platform = DesktopAppPlatform("ES")
        val services = DesktopAppServices(platform)
        assertNotNull(services.chatModel(), "Desktop has the DJ model chat")
    }
}
