// shared/src/desktopMain/kotlin/com/varuna/rustify/platform/SystemSpeech.kt
package com.varuna.rustify.platform

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The computer's own text-to-speech, through the command each system ships, so the desktop needs
 * no library and no download for the DJ to speak: Windows' System.Speech (SAPI) through PowerShell,
 * macOS' `say`, and on Linux Speech Dispatcher's `spd-say` or, without it, `espeak-ng`/`espeak`.
 *
 * The text never goes through a shell: it is an argument of its own, standard input, or (Windows)
 * a literal inside a script handed over base64-encoded.
 */
class SystemSpeech(val kind: Kind, val executable: String) {
    enum class Kind { WINDOWS_SAPI, MACOS_SAY, SPEECH_DISPATCHER, ESPEAK_NG, ESPEAK }

    /** What to run: the command line and what to write to its standard input (null: nothing). */
    data class Invocation(val command: List<String>, val stdin: String?)

    /**
     * Starts speaking [text]; the process ends when it is done, and destroying it stops it.
     * [language] is a code ("es", "en-GB"; blank: the system's), [voice] a name from [voices]
     * (blank: the language's first, or the system's).
     */
    fun start(text: String, language: String, voice: String): Process {
        val chosen = voice.ifBlank {
            // `say` picks by name only: the first voice of the language, as Android's TextToSpeech does.
            if (kind == Kind.MACOS_SAY && language.isNotBlank()) voices(language).firstOrNull()?.first.orEmpty() else ""
        }
        return run(speakInvocation(kind, executable, text, language, chosen), capture = false)
    }

    /** The installed voices for [language] (blank: all) as `(name, label)`; empty where the system has no list. */
    fun voices(language: String): List<Pair<String, String>> {
        val invocation = voicesInvocation(kind, executable) ?: return emptyList()
        val output = runCatching {
            val p = run(invocation, capture = true)
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            text
        }.onFailure { Log.w(TAG, "listing the system's voices failed", it) }.getOrDefault("")
        val all = when (kind) {
            Kind.WINDOWS_SAPI -> parseWindowsVoices(output)
            Kind.MACOS_SAY -> parseSayVoices(output)
            else -> emptyList()
        }
        val wanted = language.substringBefore('-').substringBefore('_').lowercase()
        return all.filter { wanted.isBlank() || it.locale.substringBefore('-').substringBefore('_').lowercase() == wanted }
            .map { it.name to "${it.name} · ${it.locale}" }
    }

    // What a speaking process prints is dropped: nobody reads it, and a full pipe would stall it.
    private fun run(invocation: Invocation, capture: Boolean): Process {
        val builder = ProcessBuilder(invocation.command).redirectErrorStream(true)
        if (!capture) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val p = builder.start()
        p.outputStream.use { out -> invocation.stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
        return p
    }

    data class SystemVoice(val name: String, val locale: String)

    companion object {
        private const val TAG = "SystemSpeech"

        /** The speech command this computer has, or null when it has none on its PATH. */
        fun find(
            osName: String = System.getProperty("os.name").orEmpty(),
            lookup: (String) -> String? = { Executables.onPath(it, osName.lowercase().contains("win")) },
        ): SystemSpeech? {
            val os = osName.lowercase()
            return when {
                os.contains("win") -> (lookup("powershell") ?: windowsPowerShell())?.let { SystemSpeech(Kind.WINDOWS_SAPI, it) }
                os.contains("mac") -> lookup("say")?.let { SystemSpeech(Kind.MACOS_SAY, it) }
                else -> lookup("spd-say")?.let { SystemSpeech(Kind.SPEECH_DISPATCHER, it) }
                    ?: lookup("espeak-ng")?.let { SystemSpeech(Kind.ESPEAK_NG, it) }
                    ?: lookup("espeak")?.let { SystemSpeech(Kind.ESPEAK, it) }
            }
        }

        // Windows PowerShell is part of every Windows 10 and 11, even with PATH trimmed.
        private fun windowsPowerShell(): String? {
            val root = System.getenv("SystemRoot") ?: return null
            return File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe").takeIf { it.isFile }?.absolutePath
        }

        fun speakInvocation(kind: Kind, executable: String, text: String, language: String, voice: String): Invocation =
            when (kind) {
                Kind.WINDOWS_SAPI -> Invocation(
                    listOf(executable, "-NoProfile", "-NonInteractive", "-EncodedCommand", encodePowerShell(windowsScript(text, language, voice))),
                    null,
                )
                // -f - : the text from standard input, so nothing in it is read as an option.
                Kind.MACOS_SAY -> Invocation(
                    listOf(executable) + (if (voice.isNotBlank()) listOf("-v", voice) else emptyList()) + listOf("-f", "-"),
                    text,
                )
                // -w waits until it has been spoken, so the process ends with the speech.
                Kind.SPEECH_DISPATCHER -> Invocation(
                    listOf(executable, "-w") + languageArgs("-l", language) + listOf("--", text),
                    null,
                )
                Kind.ESPEAK_NG, Kind.ESPEAK -> Invocation(
                    listOf(executable, "-b", "1") + languageArgs("-v", language) + listOf("--stdin"),
                    text,
                )
            }

        fun voicesInvocation(kind: Kind, executable: String): Invocation? = when (kind) {
            Kind.WINDOWS_SAPI -> Invocation(
                listOf(executable, "-NoProfile", "-NonInteractive", "-EncodedCommand", encodePowerShell(WINDOWS_VOICES)),
                null,
            )
            Kind.MACOS_SAY -> Invocation(listOf(executable, "-v", "?"), null)
            else -> null
        }

        private fun languageArgs(flag: String, language: String): List<String> {
            val code = language.substringBefore('-').substringBefore('_').lowercase()
            return if (code.isBlank()) emptyList() else listOf(flag, code)
        }

        /**
         * The SAPI script: a chosen voice by name, else the first enabled one of the language, else
         * the system's. [text] goes in as a single-quoted literal, where only `'` needs doubling.
         */
        fun windowsScript(text: String, language: String, voice: String): String {
            val lang = language.substringBefore('-').substringBefore('_').lowercase()
            return buildString {
                append("Add-Type -AssemblyName System.Speech; ")
                append("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ")
                if (voice.isNotBlank()) {
                    append("try { \$s.SelectVoice(").append(psQuote(voice)).append(") } catch {} ; ")
                } else if (lang.isNotBlank()) {
                    append("\$v = \$s.GetInstalledVoices() | Where-Object { \$_.Enabled -and \$_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq ")
                    append(psQuote(lang)).append(" } | Select-Object -First 1; ")
                    append("if (\$v) { \$s.SelectVoice(\$v.VoiceInfo.Name) } ; ")
                }
                append("\$s.Speak(").append(psQuote(text)).append(")")
            }
        }

        private const val WINDOWS_VOICES =
            "Add-Type -AssemblyName System.Speech; " +
                "(New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() | " +
                "Where-Object { \$_.Enabled } | ForEach-Object { \$_.VoiceInfo.Name + '|' + \$_.VoiceInfo.Culture.Name }"

        private fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"

        /** PowerShell's -EncodedCommand: the script as UTF-16LE, in base64. */
        fun encodePowerShell(script: String): String =
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

        /** `Name|es-ES` per line, as [WINDOWS_VOICES] prints them. */
        fun parseWindowsVoices(output: String): List<SystemVoice> = output.lines()
            .mapNotNull { line ->
                val name = line.substringBefore('|', "").trim()
                val locale = line.substringAfter('|', "").trim()
                if (name.isEmpty() || locale.isEmpty()) null else SystemVoice(name, locale)
            }

        private val SAY_LINE = Regex("""^(.+?)\s+([a-z]{2,3}[_-][A-Za-z0-9]{2,})\s+#""")

        /** `say -v ?`: the name (spaces and brackets allowed), the locale, then `#` and a sample. */
        fun parseSayVoices(output: String): List<SystemVoice> = output.lines()
            .mapNotNull { line -> SAY_LINE.find(line)?.let { SystemVoice(it.groupValues[1].trim(), it.groupValues[2]) } }
    }
}
