package com.varuna.rustify.ui

import com.varuna.rustify.bridge.SpotifyEngineException
import com.varuna.rustify.util.ErrorKind
import com.varuna.rustify.util.classifyError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What decides whether a Canvas lookup that failed is worth a warning in the log.
 */
class TrackErrorStateTest {

    @Test
    fun unauthenticatedCanvasErrorIsClassifiedAsAuth() {
        val exWithKind = SpotifyEngineException("canvas lookup failed: User not authenticated", "auth")
        assertEquals(ErrorKind.AUTH, classifyError(exWithKind))

        val exWithoutKind = SpotifyEngineException("canvas lookup failed: User not authenticated", null)
        assertEquals(ErrorKind.AUTH, classifyError(exWithoutKind))
    }

    @Test
    fun networkFailureOnCanvasIsClassifiedAsTransientOrPermanent() {
        val networkEx = SpotifyEngineException("canvas lookup failed: network timeout", "transient")
        assertEquals(ErrorKind.TRANSIENT, classifyError(networkEx))
    }

}
