// shared/src/wasmJsMain/kotlin/com/varuna/rustify/platform/Platform.wasm.kt
package com.varuna.rustify.platform

private fun nowMs(): Double = js("Date.now()")

private fun navigatorLanguage(): String = js("navigator.language || 'en'")

private fun nfc(s: String): String = js("s.normalize('NFC')")

private fun languageName(code: String): String =
    js("(function() { try { return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) || ''; } catch (e) { return ''; } })()")

actual fun currentTimeMillis(): Long = nowMs().toLong()

actual fun defaultLanguage(): String = navigatorLanguage().substringBefore('-').ifBlank { "en" }

actual fun normalizeNfc(s: String): String = nfc(s)

/** No `java.io` here: transport failures from the core arrive as answers, not exceptions. */
actual fun isIoException(t: Throwable): Boolean = false

actual fun languageNameInEnglish(code: String): String = languageName(code)

actual val kotlinx.coroutines.Dispatchers.IO: kotlinx.coroutines.CoroutineDispatcher get() = Default
