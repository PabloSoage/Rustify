// sharedUi/src/androidMain/kotlin/com/varuna/rustify/ui/ShareImage.android.kt
package com.varuna.rustify.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

// The activity is found through the composition's context: inside a dialog that is the dialog's,
// which wraps the activity. Where there is none, the application's, with a new task.
@Composable
actual fun rememberShareImage(): (file: File, text: String, mimeType: String) -> Boolean {
    val platform = LocalAppPlatform.current
    val activity = LocalActivity.current
    return remember(platform, activity) {
        { file: File, text: String, mimeType: String ->
            if (activity != null) shareImageFrom(activity, file, text, mimeType)
            else platform.shareImage(file, text, mimeType)
        }
    }
}
