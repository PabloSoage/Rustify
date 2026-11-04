// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/RelativeTime.android.kt
package com.varuna.rustify.ui

import android.text.format.DateUtils

actual fun relativeTimeSpan(epochMs: Long): String = DateUtils.getRelativeTimeSpanString(epochMs).toString()
