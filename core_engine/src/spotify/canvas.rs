// core_engine/src/spotify/canvas.rs
//
// Spotify Canvas (short looping mp4 shown behind cover art).
//
// The modern Spotify GraphQL/partner API does NOT expose canvases; Spotify keeps
// the canvas on the legacy protobuf endpoint served by spclient. This is exactly
// what the reference project Paxsenix0/Spotify-Canvas-API (MIT) does today:
//   POST https://spclient.wg.spotify.com/canvaz-cache/v0/canvases
//   body:   EntityCanvazRequest  (protobuf)
//   accept: application/protobuf
//   auth:   Bearer <user access token>   (from sp_dc login, which we already have)
//
// Canonical proto (librespot-org/spotify-connect-resources/Android/proto/canvaz.proto):
//   message EntityCanvazRequest {
//     message Entity { optional string entityUri = 1; optional string etag = 2; }
//     repeated Entity entities = 1;
//   }
//   message EntityCanvazResponse {
//     message Canvaz {
//       optional string id = 1;
//       optional string url = 2;      // <-- the mp4 canvas URL (canvaz.scdn.co)
//       optional string fileId = 3;
//       optional canvaz.Type type = 4;
//       optional string entityUri = 5;
//       ...
//     }
//     repeated Canvaz canvases = 1;
//   }
//
// The request/response are tiny (a nested message with a single string field, and a
// list of records from which we only need `url`), so we hand-encode/decode the
// protobuf wire format directly instead of pulling in `prost` + a build.rs/protoc
// codegen step. This keeps Cargo.toml and the NDK build untouched.

use crate::spotify::client::*;
use reqwest::header;

const CANVAZ_ENDPOINT: &str = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases";

// --- minimal protobuf wire-format helpers ------------------------------------

/// Encode a protobuf varint.
fn encode_varint(mut value: u64, out: &mut Vec<u8>) {
    loop {
        let mut byte = (value & 0x7f) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        out.push(byte);
        if value == 0 {
            break;
        }
    }
}

/// Encode a length-delimited field (wire type 2): tag, length, bytes.
fn encode_len_field(field_number: u32, bytes: &[u8], out: &mut Vec<u8>) {
    // tag = (field_number << 3) | wire_type(2)
    encode_varint(((field_number as u64) << 3) | 2, out);
    encode_varint(bytes.len() as u64, out);
    out.extend_from_slice(bytes);
}

/// Read a varint from `buf` starting at `*pos`; advances `*pos`. Returns None on truncation.
fn read_varint(buf: &[u8], pos: &mut usize) -> Option<u64> {
    let mut result: u64 = 0;
    let mut shift = 0;
    loop {
        if *pos >= buf.len() || shift >= 64 {
            return None;
        }
        let byte = buf[*pos];
        *pos += 1;
        result |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            return Some(result);
        }
        shift += 7;
    }
}

/// Skip a field whose wire type is not what we want. Returns false on error.
fn skip_field(buf: &[u8], pos: &mut usize, wire_type: u64) -> bool {
    match wire_type {
        0 => read_varint(buf, pos).is_some(),        // varint
        1 => {
            *pos += 8;
            *pos <= buf.len()
        } // 64-bit
        2 => {
            match read_varint(buf, pos) {
                Some(len) => {
                    *pos += len as usize;
                    *pos <= buf.len()
                }
                None => false,
            }
        }
        5 => {
            *pos += 4;
            *pos <= buf.len()
        } // 32-bit
        _ => false,
    }
}

/// Build the EntityCanvazRequest protobuf body for a single track URI.
fn build_request(track_uri: &str) -> Vec<u8> {
    // Entity { entityUri = 1 }
    let mut entity = Vec::new();
    encode_len_field(1, track_uri.as_bytes(), &mut entity);

    // EntityCanvazRequest { repeated Entity entities = 1 }
    let mut req = Vec::new();
    encode_len_field(1, &entity, &mut req);
    req
}

/// Parse an EntityCanvazResponse and return the first canvas `url` (field 2 of Canvaz).
fn parse_response_url(buf: &[u8]) -> Option<String> {
    let mut pos = 0usize;
    // EntityCanvazResponse: repeated Canvaz canvases = 1  (wire type 2)
    while pos < buf.len() {
        let tag = read_varint(buf, &mut pos)?;
        let field = (tag >> 3) as u32;
        let wire = tag & 0x7;
        if field == 1 && wire == 2 {
            let len = read_varint(buf, &mut pos)? as usize;
            let end = pos.checked_add(len)?;
            if end > buf.len() {
                return None;
            }
            let canvaz = &buf[pos..end];
            pos = end;
            if let Some(url) = parse_canvaz_url(canvaz) {
                if !url.is_empty() {
                    return Some(url);
                }
            }
        } else if !skip_field(buf, &mut pos, wire) {
            return None;
        }
    }
    None
}

/// Parse a single Canvaz message and extract `url` (field 2, string).
fn parse_canvaz_url(buf: &[u8]) -> Option<String> {
    let mut pos = 0usize;
    while pos < buf.len() {
        let tag = read_varint(buf, &mut pos)?;
        let field = (tag >> 3) as u32;
        let wire = tag & 0x7;
        if field == 2 && wire == 2 {
            let len = read_varint(buf, &mut pos)? as usize;
            let end = pos.checked_add(len)?;
            if end > buf.len() {
                return None;
            }
            return String::from_utf8(buf[pos..end].to_vec()).ok();
        } else if !skip_field(buf, &mut pos, wire) {
            return None;
        }
    }
    None
}

impl SpotifyClient {
    /// Fetch the Spotify Canvas (looping mp4) for a track.
    ///
    /// `track_uri` may be either a full URI (`spotify:track:<id>`) or a bare id.
    /// Returns `Ok(Some(url))` when a canvas exists, `Ok(None)` when the track has
    /// no canvas, and `Err` on auth/network failures. Never panics.
    pub async fn get_track_canvas(&self, track_uri: &str) -> SpotifyResult<Option<String>> {
        let uri = if track_uri.starts_with("spotify:track:") {
            track_uri.to_string()
        } else {
            format!("spotify:track:{}", track_uri)
        };

        // Canvas requires the user Bearer token obtained via sp_dc login.
        let token = self.access_token()?.to_string();

        let body = build_request(&uri);

        let http = self.clone_http();
        let res = http
            .post(CANVAZ_ENDPOINT)
            .header(header::AUTHORIZATION, format!("Bearer {}", token))
            .header(header::ACCEPT, "application/protobuf")
            .header(header::CONTENT_TYPE, "application/x-protobuf")
            // Spotify serves canvases only to app clients; spoof a mobile client.
            .header(header::USER_AGENT, "Spotify/9.0.34.593 iOS/18.4")
            .body(body)
            .send()
            .await?;

        let status = res.status();
        if !status.is_success() {
            let code = status.as_u16();
            // 404 / no-canvas → treat as "no canvas" rather than a hard error.
            if code == 404 {
                return Ok(None);
            }
            let msg = res.text().await.unwrap_or_default();
            return Err(SpotifyError::ApiError(code, msg));
        }

        let bytes = res.bytes().await?;
        Ok(parse_response_url(&bytes))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_varint_roundtrip() {
        for v in [0u64, 1, 127, 128, 300, 16384, 1_000_000] {
            let mut out = Vec::new();
            encode_varint(v, &mut out);
            let mut pos = 0;
            assert_eq!(read_varint(&out, &mut pos), Some(v));
            assert_eq!(pos, out.len());
        }
    }

    #[test]
    fn test_request_and_response_roundtrip() {
        // Encode a request, then hand-build a matching response and parse the url back.
        let req = build_request("spotify:track:abc123");
        assert!(!req.is_empty());

        // Build EntityCanvazResponse { canvases: [ Canvaz { id=1:"x", url=2:"http://c.mp4" } ] }
        let mut canvaz = Vec::new();
        encode_len_field(1, b"someid", &mut canvaz); // id
        encode_len_field(2, b"https://canvaz.scdn.co/x.mp4", &mut canvaz); // url
        let mut resp = Vec::new();
        encode_len_field(1, &canvaz, &mut resp); // canvases

        assert_eq!(
            parse_response_url(&resp).as_deref(),
            Some("https://canvaz.scdn.co/x.mp4")
        );
    }

    #[test]
    fn test_empty_response_is_none() {
        assert_eq!(parse_response_url(&[]), None);
    }
}
