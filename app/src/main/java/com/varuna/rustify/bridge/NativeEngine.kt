package com.varuna.rustify.bridge

object NativeEngine {
    // Esto le dice a la máquina virtual de Android que cargue la librería .so
    init {
        System.loadLibrary("core_engine")
    }

    /**
     * Busca canciones en YouTube Music usando Rust.
     * @param query Ejemplo: "USUM71700966" o "Bohemian Rhapsody Queen"
     * @return Un String en formato JSON con la lista de YouTubeTrack.
     */
    external fun searchYouTubeNative(query: String): String

    /**
     * Extrae las URLs directas de los archivos de audio (.m4a/.webm) de un Video de YouTube.
     * @param videoId El ID alfanumérico del vídeo.
     * @return Un String en formato JSON con la lista de AudioStream ordenados por bitrate.
     */
    external fun getAudioStreamsNative(videoId: String): String
}