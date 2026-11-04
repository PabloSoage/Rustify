/*
 * rustify.h — the C ABI of core_engine (src/ffi.rs), for callers that are not a JVM: iOS through
 * Kotlin/Native cinterop (shared/src/nativeInterop/cinterop/rustify.def) or Swift.
 *
 * Built with the `c-api` feature (plus `desktop-audio` for the engine* methods and `secure-store`
 * for the secret* ones), as a static library: see tools/ios/build-core.sh.
 *
 * Every function that returns a string allocates it; hand it back to rustify_free. Answers are the
 * JSON the JNI bridge of the same name answers (getSpotifyAlbum ↔ getSpotifyAlbumNative), so the
 * Kotlin decoders in bridge/SpotifyModels.kt read them unchanged. A failure is
 * {"success":false,"error":"…","kind":"auth|rateLimited|transient|permanent"}.
 *
 * Blocking: a method that reaches the network blocks the calling thread until it answers, exactly
 * like the JNI bridges. Call it from a background queue, never the main thread.
 */
#ifndef RUSTIFY_H
#define RUSTIFY_H

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Calls `method` with `args_json` (a JSON object; NULL reads as {}). The methods and their
 * arguments, in camelCase:
 *
 *   initCacheDir {dir}                 initSpotifyCacheDir {dir}          setLanguage {lang}
 *   parseLink {text}                   wrapLink {url, host}               unwrapLink {url, knownHosts}
 *   searchIndex {name, items}          searchQuery {name, query, limit}   searchForget {name}
 *   indexLocalTracks {items}           findLocalMatch {track}
 *   playerReduce {state, action}       arrangeReleases {entries, nowMs}
 *   recordListening {session}          listContinueListening {}           forgetListening {id}
 *   recordTrackPlace {trackId, positionMs, durationMs}  trackPlace {trackId}  forgetTrackPlace {trackId}
 *   markListened {contextId, queue, trackId}  listenedState {contextId, queue}  forgetListened {contextId}
 *   recordPlay {event}  importPlayHistory {history}  allPlays {}
 *   playStats {fromMs, toMs, zone}     topCsv {fromMs, toMs, kind}
 *   exportData {producedBy}            restoreData {json}
 *   listenBrainzSubmit {token, listen}  listenBrainzPlayingNow {token, listen}  listenBrainzValidate {token}
 *   lastFmLogin {apiKey, secret, user, password}
 *   lastFmSubmit {credentials, listen}  lastFmNowPlaying {credentials, listen}
 *   readReplayGain {path}  readEmbeddedLyrics {path}  tempoOf {samples, sampleRate}
 *   readTags {path}  readCover {path}  writeTags {path, tags}
 *   autoEqSearch {query}   autoEqProfile {path}  autoEqBands {peqText, n}
 *   subsonicPing {config}  subsonicFind {config, title, artists, durationMs}
 *   loginSpotify {spDc}                restoreSpotifySession {spDc, accessToken, expiration}
 *   refreshSpotifyToken {}             isSpotifyAuthenticated {}          logoutSpotify {}
 *   warmupSpotifyHashes {}             getSpotifyHashes {}                getSpotifyMe {}
 *   getSpotifySavedTracks {limit, offset}     getSpotifySavedAlbums {limit, offset}
 *   getSpotifySavedPlaylists {limit, offset}  getSpotifyFollowedArtists {limit, offset}
 *   searchSpotify {query, type, limit, offset}                getSpotifyBrowse {limit}
 *   getSpotifyAlbum {id}               getSpotifyAlbumTracks {id, limit, offset}
 *   getSpotifyNewReleases {limit, offset}     saveSpotifyAlbums {ids}  unsaveSpotifyAlbums {ids}
 *   getSpotifyArtist {id}              getSpotifyArtistTopTracks {id, limit, offset}
 *   getSpotifyArtistAlbums {id, limit, offset}  getSpotifyRelatedArtists {id, limit, offset}
 *   followSpotifyArtists {ids}         unfollowSpotifyArtists {ids}       getArtistAbout {id}
 *   getSpotifyPlaylist {id}            getSpotifyPlaylistTracks {id, limit, offset}
 *   createSpotifyPlaylist {userId, name, description, public}
 *   updateSpotifyPlaylist {id, name, description}
 *   addTracksToPlaylist {id, trackIds, position}   removeTracksFromPlaylist {id, trackIds}
 *   followPlaylist {id}                unfollowPlaylist {id}              extendPlaylist {id, skip, count}
 *   getSpotifyTrack {id}               saveSpotifyTracks {ids}            unsaveSpotifyTracks {ids}
 *   getSpotifyTrackRadio {id}          getSpotifyLyrics {id}              getTrackCredits {id}
 *   getSpotifyCanvas {uri}
 *
 * The Spotify writes (save*, unsave*, follow*, unfollow*, updateSpotifyPlaylist, addTracksToPlaylist,
 * removeTracksFromPlaylist) and refreshSpotifyToken answer {"success":…,"error":…} without a kind,
 * exactly as their JNI twins do.
 *
 *   searchYtMusic {query}              getYtmAlbum {id}  getYtmArtist {id}  getYtmPlaylist {id}
 *   searchYouTube {query}              resolveInnertubeAudio {videoId, prefs}
 *   resolveInnertubeVideo {videoId}
 *   setInnertubeClients {clients}
 *   registerTrackMetadata {id, name, artists, durationMs, isrc}   resolveYouTubeId {spotifyId, youtubeId}
 *   setAlternativeTrack {spotifyId, youtubeId}  getAlternativeTrack {spotifyId}  updateQueue {trackIds}
 *   installAddon {url}  listAddons {}  uninstallAddon {id}  setAddonEnabled {id, enabled}
 *   reorderAddons {ids}  resolveViaAddon {addonId, query}
 *   jamNewCode {}  jamJoin {config}  jamPoll {timeoutMs}  jamPublishState {state}
 *   jamSend {command}  jamKick {id}  jamLeave {}
 *   startLocalServer {}  registerLocalStream {request}  forgetLocalStream {handle}
 *   streamCacheSize {cacheRoot}  clearStreamCache {cacheRoot}
 *   openCastListener {bindTo, device}  closeCastListener {}  castUrlFor {handle}  castSession {}
 *   engineStart {}  engineSend {command}  enginePoll {timeoutMs}  engineStop {}  engineOutputDevice {}
 *   secretGet {key}  secretSet {key, value}  secretRemove {key}
 *
 * Where the JNI twin answers something that is not JSON, the answer here is its JSON form: a bare
 * string (wrapLink, unwrapLink, resolveYouTubeId, getAlternativeTrack) is a JSON string, a long
 * (trackPlace, streamCacheSize, clearStreamCache) a number, a boolean (isSpotifyAuthenticated)
 * true or false, and nothing at all {"success":true}. Arguments the JNI twin takes as a string of
 * JSON (an id list, a session, a config) are passed as JSON values. engine* need the
 * `desktop-audio` feature and secret* the `secure-store` one; without it they answer a failure.
 *
 * The list is kept in step with ffi::METHODS by the tests in src/ffi.rs.
 */
char *rustify_call(const char *method, const char *args_json);

/* Releases a string returned by this library. NULL is ignored. */
void rustify_free(char *s);

/* The core's version, e.g. "5.0.0". Free it with rustify_free. */
char *rustify_version(void);

#ifdef __cplusplus
}
#endif

#endif /* RUSTIFY_H */
