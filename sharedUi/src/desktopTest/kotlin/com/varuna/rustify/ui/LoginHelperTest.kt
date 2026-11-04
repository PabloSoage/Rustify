package com.varuna.rustify.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `rustify-login` answers (desktopApp/login/src/main.rs) and what the sign-in screen makes of
 * it. The one case that must never happen is a failure read as a cancel: the screen would close
 * without the pasted cookie it falls back to.
 */
class LoginHelperTest {
    @Test
    fun theCookieIsTheAnswer() {
        assertEquals(LoginOutcome.SignedIn("AQ-x_y"), LoginHelper.outcome("""{"sp_dc":"AQ-x_y"}""", 0))
        // Read before the exit code is known: the answer is enough.
        assertEquals(LoginOutcome.SignedIn("AQ-x_y"), LoginHelper.outcome("""{"sp_dc":"AQ-x_y"}""", null))
    }

    @Test
    fun aClosedWindowIsACancel() {
        assertEquals(LoginOutcome.Closed, LoginHelper.outcome(null, 1))
    }

    @Test
    fun anErrorSaysWhatItWas() {
        assertEquals(
            LoginOutcome.Failed("cannot start the system's webview: \"x\""),
            LoginHelper.outcome("""{"error":"cannot start the system's webview: \"x\""}""", 2),
        )
    }

    @Test
    fun anythingElseIsAFailureNotACancel() {
        assertEquals(LoginOutcome.Failed("it exited with code 0"), LoginHelper.outcome(null, 0))
        assertEquals(LoginOutcome.Failed("it exited with code 139"), LoginHelper.outcome(null, 139))
        assertEquals(LoginOutcome.Failed("it did not exit"), LoginHelper.outcome(null, null))
        assertEquals(LoginOutcome.Failed("unexpected answer: not json"), LoginHelper.outcome("not json", 1))
        assertEquals(LoginOutcome.Failed("unexpected answer: {\"sp_dc\":\"\"}"), LoginHelper.outcome("""{"sp_dc":""}""", 0))
        assertEquals(LoginOutcome.Failed("unexpected answer: {\"sp_dc\":{}}"), LoginHelper.outcome("""{"sp_dc":{}}""", 0))
    }
}
