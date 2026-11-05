package com.varuna.rustify.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the app writes to register `rustify://` for the user, where the installer cannot: the `.reg`
 * file `reg import` reads on Windows, and the handler entry on Linux. Running the tools is not tested
 * here; what they are given is.
 */
class UrlSchemeTest {
    private val exe = """C:\Users\Ana María\AppData\Local\Rustify\Rustify.exe"""

    @Test
    fun theCommandHandsTheLinkToTheLauncher() {
        assertEquals("\"$exe\" \"%1\"", UrlScheme.windowsCommand(exe))
    }

    @Test
    fun theRegFileEscapesBackslashesAndQuotes() {
        val reg = UrlScheme.windowsRegFile("""C:\R\Rustify.exe""")
        val lines = reg.split("\r\n")
        assertEquals("Windows Registry Editor Version 5.00", lines.first())
        assertEquals(
            listOf(
                "[HKEY_CURRENT_USER\\Software\\Classes\\rustify]",
                "@=\"URL:Rustify\"",
                "\"URL Protocol\"=\"\"",
                "",
                "[HKEY_CURRENT_USER\\Software\\Classes\\rustify\\DefaultIcon]",
                "@=\"\\\"C:\\\\R\\\\Rustify.exe\\\",0\"",
                "",
                "[HKEY_CURRENT_USER\\Software\\Classes\\rustify\\shell\\open\\command]",
                "@=\"\\\"C:\\\\R\\\\Rustify.exe\\\" \\\"%1\\\"\"",
                "",
            ),
            lines.drop(2),
        )
    }

    @Test
    fun theRegisteredCommandIsReadOutOfRegQuery() {
        val output = "\r\nHKEY_CURRENT_USER\\Software\\Classes\\rustify\\shell\\open\\command\r\n" +
            "    (Predeterminado)    REG_SZ    \"$exe\" \"%1\"\r\n\r\n"
        assertEquals("\"$exe\" \"%1\"", UrlScheme.windowsRegisteredCommand(output))
        assertNull(UrlScheme.windowsRegisteredCommand("ERROR: The system was unable to find the specified registry key or value."))
    }

    @Test
    fun theProgramIsTheCommandsFirstWord() {
        assertEquals(exe, UrlScheme.windowsCommandProgram("\"$exe\" \"%1\""))
        assertEquals("""C:\R\Rustify.exe""", UrlScheme.windowsCommandProgram("""C:\R\Rustify.exe "%1""""))
    }

    @Test
    fun theLinuxEntryOpensTheLinkWithTheLauncher() {
        val entry = UrlScheme.linuxDesktopEntry("/opt/rustify/bin/Rustify")!!
        val lines = entry.lines()
        assertEquals("[Desktop Entry]", lines.first())
        assertEquals(true, "Exec=\"/opt/rustify/bin/Rustify\" %u" in lines)
        assertEquals(true, "MimeType=x-scheme-handler/rustify;" in lines)
        assertEquals(true, "NoDisplay=true" in lines)
    }

    @Test
    fun aPathTheExecKeyCannotHoldPlainlyIsNotRegistered() {
        assertNull(UrlScheme.linuxDesktopEntry("/home/a\"b/Rustify.AppImage"))
        assertNull(UrlScheme.linuxDesktopEntry("/home/\$HOME/Rustify.AppImage"))
        assertNull(UrlScheme.linuxDesktopEntry("/home/100%/Rustify.AppImage"))
        assertEquals(true, UrlScheme.linuxDesktopEntry("/home/ana maría/Apps/Rustify.AppImage") != null)
    }
}
