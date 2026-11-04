package com.varuna.rustify.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Task 11 item a: Audio Lab and Chat feature gating.
 *
 * Audio Lab has an Android implementation (AudioTrack effects, ExoPlayer); iOS and desktop do not.
 * Chat is supported on Android (Gemini Nano) and desktop (DJ model server), but null on iOS.
 */
class AudioLabFeatureTest {

    @Test
    fun audioLabFeatureDisabledOnDesktop() {
        val platform = DesktopAppPlatform("ES")
        val services = DesktopAppServices(platform)
        assertFalse(services.can(AppServices.Feature.AUDIO_LAB), "Desktop must not advertise AUDIO_LAB")
    }

    @Test
    fun chatModelAvailableOnDesktop() {
        val platform = DesktopAppPlatform("ES")
        val services = DesktopAppServices(platform)
        assertNotNull(services.chatModel(), "Desktop has the DJ model chat")
    }
}
