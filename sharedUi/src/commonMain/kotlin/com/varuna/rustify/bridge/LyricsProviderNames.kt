// sharedUi/src/commonMain/kotlin/com/varuna/rustify/bridge/LyricsProviderNames.kt
package com.varuna.rustify.bridge

import androidx.compose.runtime.Composable
import com.varuna.rustify.ui.resources.Res
import com.varuna.rustify.ui.resources.lyrics_provider_file
import com.varuna.rustify.ui.resources.lyrics_provider_genius
import com.varuna.rustify.ui.resources.lyrics_provider_lrclib
import com.varuna.rustify.ui.resources.lyrics_provider_musixmatch
import com.varuna.rustify.ui.resources.lyrics_provider_petitlyrics
import com.varuna.rustify.ui.resources.lyrics_provider_spotify
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A lyrics provider's name as the app shows it, by its [LyricsChain] id; null for an unknown one. */
fun lyricsProviderNameRes(id: String): StringResource? = when (id) {
    SpotifyLyricsProvider.id -> Res.string.lyrics_provider_spotify
    LrcLibProvider.id -> Res.string.lyrics_provider_lrclib
    MusixmatchProvider.id -> Res.string.lyrics_provider_musixmatch
    GeniusProvider.id -> Res.string.lyrics_provider_genius
    PetitLyricsProvider.id -> Res.string.lyrics_provider_petitlyrics
    LyricsChain.LOCAL -> Res.string.lyrics_provider_file
    else -> null
}

/** Every provider's name, resolved in the app's language: id to name. */
@Composable
fun lyricsProviderNames(): Map<String, String> =
    listOf(
        SpotifyLyricsProvider.id, LrcLibProvider.id, MusixmatchProvider.id, GeniusProvider.id,
        PetitLyricsProvider.id, LyricsChain.LOCAL,
    ).associateWith { id -> stringResource(checkNotNull(lyricsProviderNameRes(id))) }
