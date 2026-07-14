// core_engine/src/youtube/ytmusic.rs
//
// YouTube Music (E40) — browse/search bridge on top of RustyPipe 0.11.4.
//
// API surface used (verified against docs.rs/rustypipe/0.11.4):
//   - RustyPipeQuery::music_search_tracks(q)   -> MusicSearchResult<TrackItem>
//   - RustyPipeQuery::music_search_albums(q)    -> MusicSearchResult<AlbumItem>
//   - RustyPipeQuery::music_search_artists(q)   -> MusicSearchResult<ArtistItem>
//   - RustyPipeQuery::music_search_playlists(q, community: bool) -> MusicSearchResult<MusicPlaylistItem>
//   - RustyPipeQuery::music_album(id)           -> MusicAlbum   (tracks: Vec<TrackItem>)
//   - RustyPipeQuery::music_artist(id, all_albums: bool) -> MusicArtist (header_image, tracks, albums)
//   - RustyPipeQuery::music_playlist(id)        -> MusicPlaylist (tracks: Paginator<TrackItem>)
//   - RustyPipeQuery::music_radio_track(video_id) -> Paginator<TrackItem>
//
// Field references (verified):
//   TrackItem { id: String, name: String, duration: Option<u32>, cover: Vec<Thumbnail>,
//               artists: Vec<ArtistId>, album: Option<AlbumId> }
//   ArtistId  { id: Option<String>, name: String }
//   AlbumId   { id: String, name: String }
//   AlbumItem { id: String, name: String, cover: Vec<Thumbnail>, year: Option<u16> }
//   ArtistItem{ id: String, name: String, avatar: Vec<Thumbnail> }
//   MusicPlaylistItem { id, name, thumbnail: Vec<Thumbnail>, channel: Option<ChannelId> }
//   ChannelId { id: String, name: String }
//   Thumbnail { url, width, height }
//
// NOTE: RustyPipe's TrackItem exposes no explicit flag, so `is_explicit` is always
// `false` (the Kotlin model keeps the field for forward-compat but the UI hides the
// badge when false).

use super::models::*;
use super::scraper::get_client;
use rustypipe::model::{AlbumItem, ArtistItem, MusicPlaylistItem, TrackItem};

/// Map a RustyPipe `TrackItem` into our JNI `YtmTrack`.
fn map_track(t: &TrackItem) -> YtmTrack {
    YtmTrack {
        video_id: t.id.clone(),
        title: t.name.clone(),
        artists: t
            .artists
            .iter()
            .map(|a| YtmArtistRef {
                id: a.id.clone().unwrap_or_default(),
                name: a.name.clone(),
            })
            .collect(),
        album_id: t.album.as_ref().map(|a| a.id.clone()),
        duration_sec: t.duration.unwrap_or(0),
        thumbnail_url: t.cover.last().map(|c| c.url.clone()).unwrap_or_default(),
        is_explicit: false, // RustyPipe TrackItem exposes no explicit flag.
    }
}

/// Map a RustyPipe `AlbumItem` into our JNI `YtmAlbumSlim`.
fn map_album_slim(a: &AlbumItem) -> YtmAlbumSlim {
    YtmAlbumSlim {
        browse_id: a.id.clone(),
        title: a.name.clone(),
        year: a.year.map(|y| y as u32),
        thumbnail_url: a.cover.last().map(|c| c.url.clone()).unwrap_or_default(),
    }
}

/// Map a RustyPipe `ArtistItem` into our JNI `YtmArtistRef`.
fn map_artist_ref(a: &ArtistItem) -> YtmArtistRef {
    YtmArtistRef {
        id: a.id.clone(),
        name: a.name.clone(),
    }
}

/// Map a search `MusicPlaylistItem` into our JNI `YtmPlaylist` (tracks resolved lazily).
fn map_playlist_item(p: &MusicPlaylistItem) -> YtmPlaylist {
    YtmPlaylist {
        playlist_id: p.id.clone(),
        title: p.name.clone(),
        author: p.channel.as_ref().map(|ch| ch.name.clone()),
        thumbnail_url: p.thumbnail.last().map(|c| c.url.clone()).unwrap_or_default(),
        tracks: vec![],
    }
}

/// Unified YTM search: tracks + albums + artists + playlists. Failures per section
/// degrade gracefully (that section stays empty) instead of aborting the whole search.
pub async fn ytm_search(query: &str) -> YtmSearchResults {
    let mut results = YtmSearchResults {
        tracks: vec![],
        albums: vec![],
        artists: vec![],
        playlists: vec![],
    };

    let rp = match get_client() {
        Ok(rp) => rp,
        Err(_) => return results,
    };

    if let Ok(r) = rp.query().music_search_tracks(query).await {
        results.tracks = r.items.items.iter().map(map_track).collect();
    }
    if let Ok(r) = rp.query().music_search_albums(query).await {
        results.albums = r.items.items.iter().map(map_album_slim).collect();
    }
    if let Ok(r) = rp.query().music_search_artists(query).await {
        results.artists = r.items.items.iter().map(map_artist_ref).collect();
    }
    // `community = true` includes user-created playlists in the results.
    if let Ok(r) = rp.query().music_search_playlists(query, true).await {
        results.playlists = r.items.items.iter().map(map_playlist_item).collect();
    }

    results
}

/// Resolve a full album (header + track list) from its browse id (e.g. `MPREb_...`).
pub async fn ytm_get_album(browse_id: &str) -> Option<YtmAlbum> {
    let rp = get_client().ok()?;
    let album = rp.query().music_album(browse_id).await.ok()?;
    Some(YtmAlbum {
        browse_id: album.id.clone(),
        title: album.name.clone(),
        artists: album
            .artists
            .iter()
            .map(|a| YtmArtistRef {
                id: a.id.clone().unwrap_or_default(),
                name: a.name.clone(),
            })
            .collect(),
        year: album.year.map(|y| y as u32),
        thumbnail_url: album.cover.last().map(|c| c.url.clone()).unwrap_or_default(),
        // MusicAlbum::tracks is a plain Vec<TrackItem>.
        tracks: album
            .tracks
            .iter()
            .map(|t| {
                let mut track = map_track(t);
                // Album tracks often omit their own album ref; backfill for navigation.
                if track.album_id.is_none() {
                    track.album_id = Some(browse_id.to_string());
                }
                track
            })
            .collect(),
    })
}

/// Resolve an artist (header + top tracks + discography) from its channel id (`UC...`).
pub async fn ytm_get_artist(channel_id: &str) -> Option<YtmArtist> {
    let rp = get_client().ok()?;
    // all_albums = false → the concise artist page (top tracks + a subset of albums).
    let artist = rp.query().music_artist(channel_id, false).await.ok()?;
    Some(YtmArtist {
        channel_id: artist.id.clone(),
        name: artist.name.clone(),
        // MusicArtist exposes the artist image as `header_image: Vec<Thumbnail>`.
        thumbnail_url: artist
            .header_image
            .last()
            .map(|c| c.url.clone())
            .unwrap_or_default(),
        top_tracks: artist.tracks.iter().map(map_track).collect(),
        albums: artist.albums.iter().map(map_album_slim).collect(),
    })
}

/// Resolve a remote YTM playlist (header + tracks) from its playlist id.
pub async fn ytm_get_playlist(playlist_id: &str) -> Option<YtmPlaylist> {
    let rp = get_client().ok()?;
    let pl = rp.query().music_playlist(playlist_id).await.ok()?;
    Some(YtmPlaylist {
        playlist_id: pl.id.clone(),
        title: pl.name.clone(),
        author: pl.channel.as_ref().map(|ch| ch.name.clone()),
        thumbnail_url: pl.thumbnail.first().map(|c| c.url.clone()).unwrap_or_default(),
        // MusicPlaylist::tracks is a Paginator<TrackItem>; `.items` is the first page.
        tracks: pl.tracks.items.iter().map(map_track).collect(),
    })
}

/// Radio / autoplay continuation for a given video id (related tracks).
pub async fn ytm_radio(video_id: &str) -> Vec<YtmTrack> {
    let rp = match get_client() {
        Ok(rp) => rp,
        Err(_) => return vec![],
    };
    match rp.query().music_radio_track(video_id).await {
        // music_radio_track returns a Paginator<TrackItem>.
        Ok(radio) => radio.items.iter().map(map_track).collect(),
        Err(_) => vec![],
    }
}
