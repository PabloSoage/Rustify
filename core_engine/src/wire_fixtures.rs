// core_engine/src/wire_fixtures.rs
//
// The wire contract, written down as bytes.
//
// ## What this is for
//
// `bridge/SpotifyModels.kt` is a mirror of `spotify/models.rs` and `youtube/models.rs`, and for
// three releases the only thing keeping the two in step was that somebody remembered. It did not
// hold. Eight fields declared `String?` on the Kotlin side were read with `optString(name, "")` --
// `releaseDate`, `releaseDatePrecision`, `albumType`, `recordLabel`, `description`, `country`,
// `product` and `LoginResult.error` -- so an absent one arrived as `""` and never as `null`; three
// more sat on a type nothing used; and `BrowseSectionItem` grew an `artist` variant over here that
// the core has never emitted, with two screens rendering it.
//
// Neither of those broke anything loudly. That is the point: a mirror drifts silently, because both
// halves keep compiling.
//
// So the shape travels as **data**. This module serialises one fully-populated value of every type
// that crosses JNI into `tests/fixtures/wire/`, and those files are checked in. Rust fails here if
// what it produces stops matching the file; Kotlin fails in `SpotifyModelsContractTest` if it can no
// longer parse the file into the same values. A rename on either side now breaks a build instead of
// arriving as an empty string a screen later.
//
// ## Changing a wire type on purpose
//
// Run `cargo test -p core_engine --lib wire_fixtures -- --ignored` — or set
// `RUSTIFY_UPDATE_FIXTURES=1` — to rewrite the files, then read the diff. The diff *is* the review:
// it is the whole of what the other side will see. Kotlin's half of the test then tells you what it
// costs over there.

use std::fs;
use std::path::PathBuf;

use crate::spotify::models::{
    BrowseSection, BrowseSectionItem, FullAlbum, FullArtist, FullPlaylist, FullTrack, LoginResult,
    NormalizedSearchResults, OperationResult, PaginatedResponse, PlaylistTracks, SimpleAlbum,
    SimpleArtist, SimplePlaylist, SpotifyImage, SpotifyUser,
};
use crate::youtube::models::{
    YtmAlbum, YtmAlbumSlim, YtmArtist, YtmArtistRef, YtmPlaylist, YtmSearchResults, YtmTrack,
};

fn fixture_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/wire")
}

fn updating() -> bool {
    std::env::var("RUSTIFY_UPDATE_FIXTURES").is_ok()
}

/// Compare one serialised value against its checked-in file, or rewrite it when updating.
///
/// Returns the mismatch rather than panicking so that one run reports *every* type that moved,
/// which is what you want when a shared field like `SpotifyImage` changes.
fn golden<T: serde::Serialize>(name: &str, value: &T) -> Option<String> {
    let dir = fixture_dir();
    let path = dir.join(format!("{name}.json"));
    let produced = serde_json::to_string_pretty(value).expect("every wire type serialises") + "\n";

    if updating() || !path.exists() {
        fs::create_dir_all(&dir).expect("fixture directory is writable");
        fs::write(&path, &produced).expect("fixture is writable");
        return None;
    }

    let stored = fs::read_to_string(&path).expect("fixture is readable");
    if stored.replace("\r\n", "\n") == produced {
        None
    } else {
        Some(format!(
            "{name}.json no longer matches what the core produces.\n  stored:   {}\n  produced: {}",
            stored.replace('\n', " ").trim().to_string(),
            produced.replace('\n', " ").trim().to_string(),
        ))
    }
}

// ─── Sample values ───────────────────────────────────────────────────────────────────────────────
//
// Every field is populated, and populated *distinctively*: a rename that swapped two fields of the
// same type would still pass if both held "test". `Option` fields are `Some` on purpose — a `None`
// with `skip_serializing_if` writes no key at all, which pins nothing.

fn image() -> SpotifyImage {
    SpotifyImage {
        url: "https://i.scdn.co/image/cover-640".to_string(),
        height: Some(640),
        width: Some(480),
    }
}

fn simple_artist() -> SimpleArtist {
    SimpleArtist {
        id: "artist-id".to_string(),
        name: "Björk".to_string(),
        external_uri: "spotify:artist:artist-id".to_string(),
        images: Some(vec![image()]),
    }
}

fn full_artist() -> FullArtist {
    FullArtist {
        id: "artist-id".to_string(),
        name: "Björk".to_string(),
        external_uri: "spotify:artist:artist-id".to_string(),
        images: vec![image()],
        genres: vec!["art pop".to_string(), "electronic".to_string()],
        followers: Some(4_200_000),
    }
}

fn simple_album() -> SimpleAlbum {
    SimpleAlbum {
        id: "album-id".to_string(),
        name: "Homogenic".to_string(),
        external_uri: "spotify:album:album-id".to_string(),
        release_date: Some("1997-09-22".to_string()),
        release_date_precision: Some("day".to_string()),
        images: vec![image()],
        artists: vec![simple_artist()],
        album_type: Some("album".to_string()),
    }
}

fn full_album() -> FullAlbum {
    FullAlbum {
        id: "album-id".to_string(),
        name: "Homogenic".to_string(),
        external_uri: "spotify:album:album-id".to_string(),
        release_date: Some("1997-09-22".to_string()),
        release_date_precision: Some("day".to_string()),
        images: vec![image()],
        artists: vec![simple_artist()],
        album_type: Some("album".to_string()),
        total_tracks: Some(10),
        record_label: Some("One Little Indian".to_string()),
        genres: vec!["art pop".to_string()],
    }
}

fn full_track() -> FullTrack {
    FullTrack {
        id: Some("track-id".to_string()),
        name: "Jóga".to_string(),
        external_uri: "spotify:track:track-id".to_string(),
        explicit: true,
        duration_ms: 303_000,
        isrc: "GBAAA9700123".to_string(),
        artists: vec![simple_artist()],
        album: Some(simple_album()),
        added_at: Some("2024-03-01T10:11:12Z".to_string()),
    }
}

fn user() -> SpotifyUser {
    SpotifyUser {
        id: "user-id".to_string(),
        name: Some("Pablo".to_string()),
        external_uri: "spotify:user:user-id".to_string(),
        images: vec![image()],
        followers: Some(17),
        country: Some("ES".to_string()),
        product: Some("premium".to_string()),
    }
}

fn simple_playlist() -> SimplePlaylist {
    SimplePlaylist {
        id: "playlist-id".to_string(),
        // The markup is deliberate: Kotlin strips it on the way in, and that transform is part of
        // the contract rather than something a screen does later.
        description: Some("<b>Best</b> of &amp; more".to_string()),
        name: "Road trip".to_string(),
        images: vec![image()],
        external_uri: "spotify:playlist:playlist-id".to_string(),
        owner: Some(user()),
        tracks: Some(PlaylistTracks { total: 142 }),
    }
}

fn full_playlist() -> FullPlaylist {
    FullPlaylist {
        id: "playlist-id".to_string(),
        name: "Road trip".to_string(),
        description: Some("<b>Best</b> of &amp; more".to_string()),
        images: vec![image()],
        external_uri: "spotify:playlist:playlist-id".to_string(),
        owner: Some(user()),
        tracks: Some(PlaylistTracks { total: 142 }),
        collaborative: true,
        public: Some(false),
    }
}

fn ytm_artist_ref() -> YtmArtistRef {
    YtmArtistRef {
        id: "UCartist".to_string(),
        name: "Björk".to_string(),
    }
}

fn ytm_track() -> YtmTrack {
    YtmTrack {
        video_id: "dQw4w9WgXcQ".to_string(),
        title: "Jóga".to_string(),
        artists: vec![ytm_artist_ref()],
        album_id: Some("MPREb_album".to_string()),
        duration_sec: 303,
        // Sized on purpose: Kotlin rewrites this to =w720-h720 on the way in, and that is pinned.
        thumbnail_url: "https://lh3.googleusercontent.com/thumb=w120-h120".to_string(),
        is_explicit: true,
    }
}

fn ytm_album_slim() -> YtmAlbumSlim {
    YtmAlbumSlim {
        browse_id: "MPREb_album".to_string(),
        title: "Homogenic".to_string(),
        year: Some(1997),
        thumbnail_url: "https://lh3.googleusercontent.com/thumb=w120-h120".to_string(),
    }
}

/// Every type that crosses JNI, in one place.
///
/// Adding a wire type means adding it here; the Kotlin test enumerates the same list and fails on a
/// fixture it does not know about, so the two lists cannot drift apart either.
fn all_fixtures() -> Vec<String> {
    let mut problems = Vec::new();
    let mut check = |result: Option<String>| {
        if let Some(problem) = result {
            problems.push(problem);
        }
    };

    check(golden("SpotifyImage", &image()));
    check(golden("SimpleArtist", &simple_artist()));
    check(golden("FullArtist", &full_artist()));
    check(golden("SimpleAlbum", &simple_album()));
    check(golden("FullAlbum", &full_album()));
    check(golden("FullTrack", &full_track()));
    check(golden("SpotifyUser", &user()));
    check(golden("SimplePlaylist", &simple_playlist()));
    check(golden("FullPlaylist", &full_playlist()));

    check(golden(
        "BrowseSection",
        &BrowseSection {
            id: "featured".to_string(),
            title: "Featured".to_string(),
            // Both variants, because the tag is the part that is easy to get wrong and the
            // Kotlin side reads it by hand.
            items: vec![
                BrowseSectionItem::Playlist(simple_playlist()),
                BrowseSectionItem::Album(simple_album()),
            ],
        },
    ));

    check(golden(
        "PaginatedResponse",
        &PaginatedResponse {
            items: vec![full_track()],
            total: 142,
            limit: 50,
            next_offset: Some(50),
            has_more: true,
        },
    ));

    check(golden(
        "NormalizedSearchResults",
        &NormalizedSearchResults {
            tracks: vec![full_track()],
            albums: vec![simple_album()],
            artists: vec![full_artist()],
            playlists: vec![simple_playlist()],
        },
    ));

    check(golden(
        "LoginResult",
        &LoginResult {
            success: true,
            user: Some(user()),
            error: None,
            access_token: Some("BQC-token".to_string()),
            expiration: Some(1_759_000_000_000),
        },
    ));

    check(golden(
        "OperationResult",
        &OperationResult::err("no such playlist"),
    ));

    // The other half of the failure contract, and the one that carries `kind`. `serialize_result`
    // produces this for every bridge that returns a `Result`; `OperationResult` is the hand-written
    // refusal with no error type behind it.
    check(golden(
        "FailedOperation",
        &crate::FailedOperation {
            success: false,
            error: "Spotify API error 401: token expired".to_string(),
            kind: crate::errors::ErrorKind::Auth.as_str(),
        },
    ));

    check(golden("YtmArtistRef", &ytm_artist_ref()));
    check(golden("YtmTrack", &ytm_track()));
    check(golden("YtmAlbumSlim", &ytm_album_slim()));

    check(golden(
        "YtmAlbum",
        &YtmAlbum {
            browse_id: "MPREb_album".to_string(),
            title: "Homogenic".to_string(),
            artists: vec![ytm_artist_ref()],
            year: Some(1997),
            thumbnail_url: "https://lh3.googleusercontent.com/thumb=w120-h120".to_string(),
            tracks: vec![ytm_track()],
        },
    ));

    check(golden(
        "YtmArtist",
        &YtmArtist {
            channel_id: "UCartist".to_string(),
            name: "Björk".to_string(),
            thumbnail_url: "https://lh3.googleusercontent.com/thumb=w120-h120".to_string(),
            top_tracks: vec![ytm_track()],
            albums: vec![ytm_album_slim()],
        },
    ));

    check(golden(
        "YtmPlaylist",
        &YtmPlaylist {
            playlist_id: "PLplaylist".to_string(),
            title: "Road trip".to_string(),
            author: Some("Pablo".to_string()),
            thumbnail_url: "https://lh3.googleusercontent.com/thumb=w120-h120".to_string(),
            tracks: vec![ytm_track()],
        },
    ));

    check(golden(
        "YtmSearchResults",
        &YtmSearchResults {
            tracks: vec![ytm_track()],
            albums: vec![ytm_album_slim()],
            artists: vec![ytm_artist_ref()],
            playlists: vec![YtmPlaylist {
                playlist_id: "PLplaylist".to_string(),
                title: "Road trip".to_string(),
                author: Some("Pablo".to_string()),
                thumbnail_url: "https://lh3.googleusercontent.com/thumb=w120-h120".to_string(),
                tracks: vec![],
            }],
        },
    ));

    problems
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The wire shape has not moved without the fixtures moving with it.
    ///
    /// This failing is not a broken test: it means the JSON the app receives changed, and the file
    /// it prints is exactly what Kotlin will be handed. Re-run with `RUSTIFY_UPDATE_FIXTURES=1` once
    /// the change is the one you meant.
    #[test]
    fn the_wire_shape_matches_the_checked_in_fixtures() {
        let problems = all_fixtures();
        assert!(
            problems.is_empty(),
            "the wire contract moved:\n\n{}\n\nRe-run with RUSTIFY_UPDATE_FIXTURES=1 to accept it, \
             then check app/src/test/java/com/varuna/rustify/bridge/SpotifyModelsContractTest.kt.",
            problems.join("\n\n")
        );
    }
}
