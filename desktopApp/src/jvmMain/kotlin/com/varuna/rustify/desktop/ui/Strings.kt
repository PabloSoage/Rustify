// desktopApp/src/jvmMain/kotlin/com/varuna/rustify/desktop/ui/Strings.kt
package com.varuna.rustify.desktop.ui

import java.util.Locale

/**
 * The desktop's words, in the languages the phone speaks (English, Spanish, Japanese), picked from the
 * system's language as Android picks its resources. The texts follow the phone's `strings.xml` where
 * the same thing is said.
 */
internal object S {
    private val lang = Locale.getDefault().language

    private fun t(en: String, es: String, ja: String) = when (lang) {
        "es" -> es
        "ja" -> ja
        else -> en
    }

    val home = t("Home", "Inicio", "ホーム")
    val search = t("Search", "Buscar", "検索")
    val library = t("Library", "Biblioteca", "ライブラリ")
    val metrics = t("Metrics", "Métricas", "統計")
    val jam = t("Jam", "Jam", "Jam")
    val account = t("Account", "Cuenta", "アカウント")

    val goodMorning = t("Good morning", "Buenos días", "おはようございます")
    val goodAfternoon = t("Good afternoon", "Buenas tardes", "こんにちは")
    val goodEvening = t("Good evening", "Buenas noches", "こんばんは")
    val charts = t("Charts", "Listas de éxitos", "チャート")
    val moods = t("For every mood", "Para cada momento", "気分で選ぶ")

    val searchHint = t("What do you want to listen to?", "¿Qué quieres escuchar?", "何を聴きますか？")
    val searchEmpty = t("Search for a song, an artist or an album.", "Busca una canción, un artista o un álbum.", "曲・アーティスト・アルバムを検索")
    val noResults = t("Nothing found.", "No se ha encontrado nada.", "見つかりませんでした。")

    val liked = t("Liked songs", "Canciones que te gustan", "お気に入りの曲")
    val playlists = t("Playlists", "Playlists", "プレイリスト")
    val signInForLibrary = t(
        "Connect Spotify in Account to see your library. Local search, YouTube Music and your metrics work without it.",
        "Conecta Spotify en Cuenta para ver tu biblioteca. La búsqueda, YouTube Music y tus estadísticas funcionan sin ella.",
        "ライブラリを表示するにはアカウントで Spotify を接続してください。",
    )
    val playlist = t("PLAYLIST", "PLAYLIST", "プレイリスト")
    val album = t("ALBUM", "ÁLBUM", "アルバム")
    fun songs(n: Int) = t("$n songs", "$n canciones", "${n}曲")
    val play = t("Play", "Reproducir", "再生")
    val shuffle = t("Shuffle", "Aleatorio", "シャッフル")
    val notPlaying = t("Not playing", "Sin reproducir", "再生していません")
    val queue = t("Queue", "Cola", "キュー")
    val back = t("Back", "Atrás", "戻る")
    val couldNotLoad = t("Could not load", "No se pudo cargar", "読み込めませんでした")

    val lyrics = t("Lyrics", "Letra", "歌詞")
    val lyricsNotFound = t("Lyrics not found", "No se encontraron letras", "歌詞が見つかりません")
    fun lyricsAsking(name: String) = t("Asking $name…", "Preguntando a $name…", "${name}に問い合わせ中…")
    fun lyricsNothing(name: String) = t("$name has nothing for this song", "$name no tiene esta canción", "${name}にはこの曲がありません")

    /** A lyrics provider's name, by its id in the shared chain. */
    fun lyricsProvider(id: String) = when (id) {
        "spotify" -> t("Spotify (official, needs an account)", "Spotify (oficiales, requiere cuenta)", "Spotify（公式・アカウント必要）")
        "lrclib" -> "LRCLIB"
        "musixmatch" -> "Musixmatch"
        "genius" -> "Genius"
        "petitlyrics" -> "PetitLyrics"
        "local" -> t("The file's own", "El propio archivo", "ファイル内蔵")
        else -> id
    }

    val signedInAs =t("Signed in as", "Sesión iniciada como", "ログイン中:")
    val signOut = t("Sign out", "Cerrar sesión", "ログアウト")
    val signIn = t("Sign in", "Iniciar sesión", "ログイン")
    val signingIn = t("Signing in…", "Iniciando sesión…", "ログイン中…")
    val signInHelp = t(
        "Rustify signs in with your browser session, as on the phone: open open.spotify.com signed in, copy the value of the sp_dc cookie and paste it here. It stays on this computer.",
        "Rustify entra con la sesión del navegador, como en el móvil: abre open.spotify.com con tu sesión, copia el valor de la cookie sp_dc y pégalo aquí. Se queda en este ordenador.",
        "スマホと同じくブラウザのセッションでログインします。open.spotify.com にログインし、sp_dc クッキーの値を貼り付けてください。",
    )
}
