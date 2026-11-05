package com.varuna.rustify.platform

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The commands the DJ's desktop voice runs, checked without running them: which one each system
 * gets, that the text reaches it as data (never as an option or as script), and the two voice
 * listings it reads.
 */
class SystemSpeechTest {

    private fun lookupOf(vararg found: String): (String) -> String? = { name -> if (name in found) "/bin/$name" else null }

    @Test
    fun `each system gets its own command`() {
        assertEquals(SystemSpeech.Kind.WINDOWS_SAPI, SystemSpeech.find("Windows 11", lookupOf("powershell"))?.kind)
        assertEquals(SystemSpeech.Kind.MACOS_SAY, SystemSpeech.find("Mac OS X", lookupOf("say"))?.kind)
        assertEquals(SystemSpeech.Kind.SPEECH_DISPATCHER, SystemSpeech.find("Linux", lookupOf("spd-say", "espeak-ng"))?.kind)
        assertEquals(SystemSpeech.Kind.ESPEAK_NG, SystemSpeech.find("Linux", lookupOf("espeak-ng"))?.kind)
        assertNull(SystemSpeech.find("Linux", lookupOf()))
    }

    @Test
    fun `the text is never an option`() {
        val text = "-v Zarvox --help"
        val say = SystemSpeech.speakInvocation(SystemSpeech.Kind.MACOS_SAY, "say", text, "", "")
        assertEquals(listOf("say", "-f", "-"), say.command)
        assertEquals(text, say.stdin)

        val spd = SystemSpeech.speakInvocation(SystemSpeech.Kind.SPEECH_DISPATCHER, "spd-say", text, "es-ES", "")
        assertEquals(listOf("spd-say", "-w", "-l", "es", "--", text), spd.command)

        val espeak = SystemSpeech.speakInvocation(SystemSpeech.Kind.ESPEAK_NG, "espeak-ng", text, "en", "")
        assertEquals(listOf("espeak-ng", "-b", "1", "-v", "en", "--stdin"), espeak.command)
        assertEquals(text, espeak.stdin)
    }

    @Test
    fun `a quote in the text stays inside the PowerShell literal`() {
        val script = SystemSpeech.windowsScript("It's on'); Remove-Item x; ('", "", "")
        assertTrue(script.endsWith("\$s.Speak('It''s on''); Remove-Item x; (''')"), script)
    }

    @Test
    fun `the PowerShell script travels as UTF-16LE base64`() {
        val invocation = SystemSpeech.speakInvocation(SystemSpeech.Kind.WINDOWS_SAPI, "powershell.exe", "Canción", "es", "")
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand"), invocation.command.take(4))
        val script = String(Base64.getDecoder().decode(invocation.command[4]), Charsets.UTF_16LE)
        assertTrue(script.contains("TwoLetterISOLanguageName -eq 'es'"), script)
        assertTrue(script.endsWith("\$s.Speak('Canción')"), script)
    }

    @Test
    fun `a chosen voice wins over the language`() {
        val script = SystemSpeech.windowsScript("Hi", "es", "Microsoft Helena Desktop")
        assertTrue(script.contains("SelectVoice('Microsoft Helena Desktop')"), script)
        assertTrue(!script.contains("TwoLetterISOLanguageName"), script)
        val say = SystemSpeech.speakInvocation(SystemSpeech.Kind.MACOS_SAY, "say", "Hi", "es", "Mónica")
        assertEquals(listOf("say", "-v", "Mónica", "-f", "-"), say.command)
    }

    @Test
    fun `the voices say lists are read with spaces and brackets in their names`() {
        val output = """
            Albert              en_US    # Hello! My name is Albert.
            Eddy (Spanish (Spain)) es_ES    # ¡Hola! Me llamo Eddy.
            Mónica              es_ES    # ¡Hola! Me llamo Mónica.
        """.trimIndent()
        assertEquals(
            listOf(
                SystemSpeech.SystemVoice("Albert", "en_US"),
                SystemSpeech.SystemVoice("Eddy (Spanish (Spain))", "es_ES"),
                SystemSpeech.SystemVoice("Mónica", "es_ES"),
            ),
            SystemSpeech.parseSayVoices(output),
        )
    }

    @Test
    fun `the voices SAPI lists are read and blank lines skipped`() {
        val output = "Microsoft David Desktop|en-US\r\n\r\nMicrosoft Helena Desktop|es-ES\r\n"
        assertEquals(
            listOf(
                SystemSpeech.SystemVoice("Microsoft David Desktop", "en-US"),
                SystemSpeech.SystemVoice("Microsoft Helena Desktop", "es-ES"),
            ),
            SystemSpeech.parseWindowsVoices(output),
        )
    }
}
