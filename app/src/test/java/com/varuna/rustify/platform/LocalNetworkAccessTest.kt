package com.varuna.rustify.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {

    @Test
    fun permissionOnlyNeededOnAndroid17AndHigher() {
        assertFalse("Android 16 (SDK 36)", LocalNetworkAccess.needed(36))
        assertFalse("Android 15 (SDK 35)", LocalNetworkAccess.needed(35))
        assertFalse("Android 14 (SDK 34)", LocalNetworkAccess.needed(34))
        assertTrue("Android 17 (SDK 37)", LocalNetworkAccess.needed(37))
        assertTrue("Android 18 (SDK 38)", LocalNetworkAccess.needed(38))
    }

    @Test
    fun isGrantedAlwaysTrueBeforeAndroid17() {
        // Even when callback says false, on SDK < 37 it must return true
        assertTrue("SDK 36", LocalNetworkAccess.isGranted(isGrantedCheck = { false }, sdkInt = 36))
        assertTrue("SDK 35", LocalNetworkAccess.isGranted(isGrantedCheck = { false }, sdkInt = 35))

        // On SDK 37, it reflects the check
        assertTrue("SDK 37 granted", LocalNetworkAccess.isGranted(isGrantedCheck = { true }, sdkInt = 37))
        assertFalse("SDK 37 denied", LocalNetworkAccess.isGranted(isGrantedCheck = { false }, sdkInt = 37))
    }

    @Test
    fun shouldRequestOnlyWhenActionUsesLocalNetworkOnSdk37AndNotGranted() {
        // Internet-only actions (Spotify, YouTube, Deezer, Drive) should NEVER request local network permission
        assertFalse("Spotify on SDK 37", LocalNetworkAccess.shouldRequest(actionUsesLocalNetwork = false, isGranted = false, sdkInt = 37))
        assertFalse("Drive on SDK 37", LocalNetworkAccess.shouldRequest(actionUsesLocalNetwork = false, isGranted = false, sdkInt = 37))

        // On older Android, local network action never asks
        assertFalse("Casting on SDK 36", LocalNetworkAccess.shouldRequest(actionUsesLocalNetwork = true, isGranted = false, sdkInt = 36))

        // On SDK 37, if already granted, do not ask
        assertFalse("Subsonic on SDK 37 already granted", LocalNetworkAccess.shouldRequest(actionUsesLocalNetwork = true, isGranted = true, sdkInt = 37))

        // On SDK 37, if not granted, MUST request
        assertTrue("Casting on SDK 37 not granted", LocalNetworkAccess.shouldRequest(actionUsesLocalNetwork = true, isGranted = false, sdkInt = 37))
        assertTrue("Subsonic on SDK 37 not granted", LocalNetworkAccess.shouldRequest(actionUsesLocalNetwork = true, isGranted = false, sdkInt = 37))
    }
}
