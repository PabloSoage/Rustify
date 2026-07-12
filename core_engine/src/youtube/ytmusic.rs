use super::models::*;
use super::scraper::get_client;

pub async fn ytm_search(query: &str) -> YtmSearchResults {
    let rp = get_client();
    let mut results = YtmSearchResults {
        tracks: vec![],
        albums: vec![],
        artists: vec![],
        playlists: vec![],
    };

    if let Ok(r) = rp.query().music_search_tracks(query).await {
        results.tracks = r
            .items
            .items
            .into_iter()
            .map(|t| YtmTrack {
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
                duration_sec: t.duration.unwrap_or(0) as u32,
                thumbnail_url: t.cover.first().map(|c| c.url.clone()).unwrap_or_default(),
                is_explicit: false,
            })
            .collect();
    }

    if let Ok(r) = rp.query().music_search_albums(query).await {
        results.albums = r
            .items
            .items
            .into_iter()
            .map(|a| YtmAlbumSlim {
                browse_id: a.id.clone(),
                title: a.name.clone(),
                year: a.year.map(|y| y as u32),
                thumbnail_url: a.cover.first().map(|c| c.url.clone()).unwrap_or_default(),
            })
            .collect();
    }

    if let Ok(r) = rp.query().music_search_artists(query).await {
        results.artists = r
            .items
            .items
            .into_iter()
            .map(|a| YtmArtistRef {
                id: a.id.clone(),
                name: a.name.clone(),
            })
            .collect();
    }

    if let Ok(r) = rp.query().music_search_playlists(query, true).await {
        results.playlists = r
            .items
            .items
            .into_iter()
            .map(|p| YtmPlaylist {
                playlist_id: p.id.clone(),
                title: p.name.clone(),
                author: p.channel.map(|ch| ch.name),
                thumbnail_url: p
                    .thumbnail
                    .first()
                    .map(|c| c.url.clone())
                    .unwrap_or_default(),
                tracks: vec![],
            })
            .collect();
    }

    results
}

pub async fn ytm_get_album(browse_id: &str) -> Option<YtmAlbum> {
    let rp = get_client();
    let album = rp.query().music_album(browse_id).await.ok()?;
    Some(YtmAlbum {
        browse_id: album.id,
        title: album.name,
        artists: album
            .artists
            .iter()
            .map(|a| YtmArtistRef {
                id: a.id.clone().unwrap_or_default(),
                name: a.name.clone(),
            })
            .collect(),
        year: album.year.map(|y| y as u32),
        thumbnail_url: album
            .cover
            .first()
            .map(|c| c.url.clone())
            .unwrap_or_default(),
        tracks: album
            .tracks
            .into_iter()
            .map(|t| YtmTrack {
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
                album_id: Some(browse_id.to_string()),
                duration_sec: t.duration.unwrap_or(0) as u32,
                thumbnail_url: t.cover.first().map(|c| c.url.clone()).unwrap_or_default(),
                is_explicit: false,
            })
            .collect(),
    })
}

pub async fn ytm_get_artist(channel_id: &str) -> Option<YtmArtist> {
    let rp = get_client();
    let artist = rp.query().music_artist(channel_id, false).await.ok()?;
    Some(YtmArtist {
        channel_id: artist.id.clone(),
        name: artist.name.clone(),
        thumbnail_url: artist
            .header_image
            .first()
            .map(|c| c.url.clone())
            .unwrap_or_default(),
        top_tracks: artist
            .tracks
            .into_iter()
            .map(|t| YtmTrack {
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
                duration_sec: t.duration.unwrap_or(0) as u32,
                thumbnail_url: t.cover.first().map(|c| c.url.clone()).unwrap_or_default(),
                is_explicit: false,
            })
            .collect(),
        albums: artist
            .albums
            .into_iter()
            .map(|a| YtmAlbumSlim {
                browse_id: a.id.clone(),
                title: a.name.clone(),
                year: a.year.map(|y| y as u32),
                thumbnail_url: a.cover.first().map(|c| c.url.clone()).unwrap_or_default(),
            })
            .collect(),
    })
}

pub async fn ytm_get_playlist(playlist_id: &str) -> Option<YtmPlaylist> {
    let rp = get_client();
    let pl = rp.query().music_playlist(playlist_id).await.ok()?;
    Some(YtmPlaylist {
        playlist_id: pl.id,
        title: pl.name,
        author: pl.channel.map(|ch| ch.name),
        thumbnail_url: pl
            .thumbnail
            .first()
            .map(|c| c.url.clone())
            .unwrap_or_default(),
        tracks: pl
            .tracks
            .items
            .into_iter()
            .map(|t| YtmTrack {
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
                duration_sec: t.duration.unwrap_or(0) as u32,
                thumbnail_url: t.cover.first().map(|c| c.url.clone()).unwrap_or_default(),
                is_explicit: false,
            })
            .collect(),
    })
}

pub async fn ytm_radio(video_id: &str) -> Vec<YtmTrack> {
    let rp = get_client();
    if let Ok(radio) = rp.query().music_radio_track(video_id).await {
        radio
            .items
            .into_iter()
            .map(|t| YtmTrack {
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
                duration_sec: t.duration.unwrap_or(0) as u32,
                thumbnail_url: t.cover.first().map(|c| c.url.clone()).unwrap_or_default(),
                is_explicit: false,
            })
            .collect()
    } else {
        vec![]
    }
}
