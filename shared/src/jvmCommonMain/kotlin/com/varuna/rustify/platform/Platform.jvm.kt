// shared/src/jvmCommonMain/kotlin/com/varuna/rustify/platform/Platform.jvm.kt
package com.varuna.rustify.platform

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun defaultLanguage(): String = java.util.Locale.getDefault().language

actual fun normalizeNfc(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

actual fun isIoException(t: Throwable): Boolean = t is java.io.IOException
