// core_engine/src/jam/mod.rs
//
// Jam: listening together (`23` §4, after Spotube 6). One phone hosts the queue; the others join with
// a code, follow what the host plays, and can ask for next/previous/pause and suggest songs.
//
// ## Transport
//
// Through an MQTT broker, as Spotube does, so nobody needs to open a port or be on the same Wi-Fi:
// every phone is only a client. The default is HiveMQ's public broker; Settings can point it at any
// other (a Mosquitto on the NAS, say).
//
// This is the second network path that does not go through `Env`, after `rustypipe`, and for a
// similar reason: MQTT is one long-lived TCP connection with its own keep-alive, not a request.
// It is bounded the same way: connecting has a timeout, and the event loop stops with the session.
//
// ## What the broker sees: nothing useful
//
// A public broker is public: anyone can subscribe to `#`. Spotube sends its queue there in clear
// text under the room code. Here the room code never leaves the phones:
//
// - **The topic** is a hash of the code (`sha256("rustify-jam-topic:" + code)`, 128 bits), so it
//   names the room without revealing the code.
// - **Every payload** is sealed with ChaCha20-Poly1305 under a key derived from the code (HKDF-SHA256),
//   with a fresh random nonce. Without the code it is noise, and a forged message fails to open.
// - **The code** is 12 characters from a 32-letter alphabet without look-alikes: 60 bits. Guessing
//   it from the topic hash means 2^59 SHA-256 on average, per room, for a session that lasts an evening.
//
// ## Topics under the room
//
// - `state`: the host's queue and position, **retained**, so a guest who joins late gets it at once.
// - `cmd`: what guests ask of the host.
// - `presence/<id>`: one retained message per participant; the broker's last will empties it if a
//   phone drops without leaving.
// - `kick/<id>`: the host sending a guest away.

use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use hkdf::Hkdf;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub mod session;

/// Crockford's base 32: digits and letters without `I`, `L`, `O` and `U`, which a person mistakes.
const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
pub const CODE_LEN: usize = 12;
const NONCE_LEN: usize = 12;
/// The most a payload may inflate to: a queue of several thousand songs.
const MAX_PLAIN: usize = 16 * 1024 * 1024;

/// A new room code, `XXXX-XXXX-XXXX`.
pub fn new_code() -> String {
    let mut bytes = [0u8; CODE_LEN];
    getrandom::getrandom(&mut bytes).expect("system random");
    let raw: String = bytes
        .iter()
        .map(|b| ALPHABET[(b & 31) as usize] as char)
        .collect();
    format!("{}-{}-{}", &raw[0..4], &raw[4..8], &raw[8..12])
}

/// The code as typed or pasted: case and separators do not matter, look-alikes are read as meant.
/// `None` when it cannot be a code.
pub fn normalize(input: &str) -> Option<String> {
    let raw: String = input
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            'U' => 'V',
            c => c,
        })
        .collect();
    (raw.len() == CODE_LEN && raw.bytes().all(|b| ALPHABET.contains(&b))).then_some(raw)
}

/// What both ends derive from the code.
#[derive(Clone)]
pub struct Room {
    pub topic: String,
    key: [u8; 32],
}

impl Room {
    /// `code` already normalised.
    pub fn new(code: &str) -> Self {
        let digest = Sha256::digest(format!("rustify-jam-topic:{code}").as_bytes());
        let hex: String = digest[..16].iter().map(|b| format!("{b:02x}")).collect();
        let mut key = [0u8; 32];
        Hkdf::<Sha256>::new(Some(b"rustify-jam-v1"), code.as_bytes())
            .expand(b"payload key", &mut key)
            .expect("32 bytes is a valid HKDF length");
        Room {
            topic: format!("rustify/jam/v1/{hex}"),
            key,
        }
    }

    /// Deflated, then sealed. A real fifty-song queue is ~99 KB of JSON (measured on the tablet);
    /// deflated it is a fraction of that, which keeps long playlists under the packet limit.
    /// Compressing before encrypting leaks only the length, and nothing secret is mixed with
    /// anything an outsider chooses, so there is nothing for a length to reveal.
    pub fn seal(&self, plain: &[u8]) -> Vec<u8> {
        use std::io::Write;
        let mut z = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
        z.write_all(plain).expect("writing to a Vec cannot fail");
        let packed = z.finish().expect("writing to a Vec cannot fail");
        let mut nonce = [0u8; NONCE_LEN];
        getrandom::getrandom(&mut nonce).expect("system random");
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&self.key));
        let sealed = cipher
            .encrypt(Nonce::from_slice(&nonce), packed.as_slice())
            .expect("encryption cannot fail");
        [nonce.as_slice(), &sealed].concat()
    }

    /// `None` for anything not sealed with this room's key: another room, noise, a forgery — or
    /// something that inflates past [MAX_PLAIN], which only a participant could have sent.
    pub fn open(&self, sealed: &[u8]) -> Option<Vec<u8>> {
        use std::io::Read;
        if sealed.len() < NONCE_LEN + 16 {
            return None;
        }
        let (nonce, body) = sealed.split_at(NONCE_LEN);
        let packed = ChaCha20Poly1305::new(Key::from_slice(&self.key))
            .decrypt(Nonce::from_slice(nonce), body)
            .ok()?;
        let mut plain = Vec::new();
        flate2::read::DeflateDecoder::new(packed.as_slice())
            .take(MAX_PLAIN as u64 + 1)
            .read_to_end(&mut plain)
            .ok()?;
        (plain.len() <= MAX_PLAIN).then_some(plain)
    }
}

/// The host's queue as guests see it. Tracks travel as the app's own JSON: the core passes them on.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct State {
    pub tracks: Vec<serde_json::Value>,
    pub index: i32,
    pub playing: bool,
    pub position_ms: i64,
    /// The host's clock when `position_ms` was read, so a guest can add the time in flight.
    pub at_ms: i64,
    /// Bumped by the host on every change; a guest ignores anything older than what it has.
    pub revision: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Command {
    Play,
    Pause,
    Next,
    Previous,
    JumpTo {
        index: i32,
    },
    /// Songs a guest wants added after the current one.
    Suggest {
        tracks: Vec<serde_json::Value>,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandFrom {
    pub from: String,
    pub name: String,
    pub command: Command,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Presence {
    pub id: String,
    pub name: String,
    pub host: bool,
}

/// What a participant hears from the room, in the order it arrived.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Event {
    Connected,
    State { state: State },
    Command { command: CommandFrom },
    Presence { participants: Vec<Presence> },
    Kicked,
    Disconnected { reason: String },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_code_is_three_groups_of_four_and_survives_being_retyped() {
        let code = new_code();
        assert_eq!(code.len(), 14);
        assert_eq!(code.matches('-').count(), 2);
        let n = normalize(&code).unwrap();
        assert_eq!(
            normalize(&code.to_lowercase().replace('-', " ")).as_deref(),
            Some(n.as_str())
        );
        assert_ne!(new_code(), code);
    }

    #[test]
    fn look_alikes_are_read_as_meant_and_junk_is_refused() {
        assert_eq!(normalize("abcd-efgh-jkmn").as_deref(), Some("ABCDEFGHJKMN"));
        assert_eq!(
            normalize("ABCD-EFGH-JKMO").as_deref(),
            Some("ABCDEFGHJKM0"),
            "O read as zero"
        );
        assert_eq!(
            normalize("ABCD-EFGH-JKMl").as_deref(),
            Some("ABCDEFGHJKM1"),
            "l read as one"
        );
        assert_eq!(normalize("ABCD-EFGH-JKMu").as_deref(), Some("ABCDEFGHJKMV"));
        assert!(normalize("ABCD-EFGH").is_none());
        assert!(normalize("").is_none());
    }

    #[test]
    fn the_topic_does_not_reveal_the_code_and_both_ends_agree() {
        let a = Room::new("ABCDEFGHJKMN");
        assert!(!a.topic.contains("ABCD"));
        assert_eq!(a.topic, Room::new("ABCDEFGHJKMN").topic);
        assert_ne!(a.topic, Room::new("ABCDEFGHJKMP").topic);
        assert_eq!(a.topic.len(), "rustify/jam/v1/".len() + 32);
    }

    #[test]
    fn only_the_same_room_can_open_a_payload() {
        let room = Room::new("ABCDEFGHJKMN");
        let sealed = room.seal(b"{\"index\":3}");
        assert!(!sealed.windows(5).any(|w| w == b"index"), "not in clear");
        assert_eq!(room.open(&sealed).unwrap(), b"{\"index\":3}");
        assert!(
            Room::new("ABCDEFGHJKMP").open(&sealed).is_none(),
            "another room"
        );
        let mut forged = sealed.clone();
        *forged.last_mut().unwrap() ^= 1;
        assert!(room.open(&forged).is_none(), "tampered");
        assert_ne!(room.seal(b"x"), room.seal(b"x"), "fresh nonce each time");
    }

    #[test]
    fn a_long_queue_travels_deflated_and_comes_back_whole() {
        let room = Room::new("ABCDEFGHJKMN");
        // 600 songs shaped like the app's JSON: ~1.8 KB each, over 1 MB in all, the old packet limit.
        let state = State {
            tracks: (0..600)
                .map(|i| {
                    serde_json::json!({
                        "id": format!("spotify:track:{i:022}"),
                        "name": format!("Song {i}"),
                        "album": {"name": "Album", "images": [
                            {"url": format!("https://i.scdn.co/image/ab67616d0000b273{i:024}"), "width": 640},
                            {"url": format!("https://i.scdn.co/image/ab67616d00001e02{i:024}"), "width": 300},
                        ]},
                        "artists": [{"name": "Artist", "externalUri": "https://open.spotify.com/artist/x"}],
                        "padding": "a".repeat(1500),
                    })
                })
                .collect(),
            ..State::default()
        };
        let plain = serde_json::to_vec(&state).unwrap();
        let sealed = room.seal(&plain);
        assert!(plain.len() > 1_000_000);
        assert!(
            sealed.len() < plain.len() / 5,
            "{} of {}",
            sealed.len(),
            plain.len()
        );
        assert_eq!(room.open(&sealed).unwrap(), plain);
    }

    #[test]
    fn a_payload_that_inflates_past_the_limit_is_refused() {
        let room = Room::new("ABCDEFGHJKMN");
        let bomb = room.seal(&vec![0u8; MAX_PLAIN + 1]);
        assert!(bomb.len() < 64 * 1024);
        assert_eq!(room.open(&bomb), None);
        assert!(room.open(&room.seal(&vec![0u8; 1024])).is_some());
    }

    #[test]
    fn commands_and_events_have_a_stable_shape() {
        let c = CommandFrom {
            from: "g1".into(),
            name: "Ana".into(),
            command: Command::JumpTo { index: 2 },
        };
        let j = serde_json::to_value(&c).unwrap();
        assert_eq!(j["command"]["type"], "jumpTo");
        assert_eq!(j["command"]["index"], 2);
        assert_eq!(serde_json::from_value::<CommandFrom>(j).unwrap(), c);
        let e = serde_json::to_value(Event::Presence {
            participants: vec![],
        })
        .unwrap();
        assert_eq!(e["type"], "presence");
    }
}
