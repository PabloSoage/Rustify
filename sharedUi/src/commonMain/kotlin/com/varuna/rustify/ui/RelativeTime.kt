// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/RelativeTime.kt
package com.varuna.rustify.ui

/** When [epochMs] was, said the short way ("5 minutes ago", "Yesterday") in the system's language. */
expect fun relativeTimeSpan(epochMs: Long): String
