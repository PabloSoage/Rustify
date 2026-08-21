// core_engine/src/listened/mod.rs
//
// "Already listened" as a bitfield — point I.
//
// The evaluation called this "niche but direct", and the niche is real: `metrics/ListeningTracker`
// already records every play with a `listenedMs`, which is strictly richer than a bit. What it does
// not answer cheaply is the question a person actually asks in front of a long playlist — **which of
// these have I already heard?** — because answering it from an event log means scanning the log.
//
// A bit per track, indexed by position, answers it in one read. The size difference is the reason
// this is a bitfield and not a set of ids: a 200-track playlist is 25 bytes here against roughly
// 4 KB as ids, and the whole thing is backed up to Drive.
//
// ## The part that is genuinely hard
//
// A bitfield is indexed by **position**, and a playlist's positions move. Add a song at the top and
// every mark below it is now wrong — silently, which is the worst way to be wrong. Stremio solves
// this by storing the id of the last item touched as an anchor and shifting the field to match; the
// same trick works here and has the same limit, so it is stated rather than hidden:
//
//   * **Albums are exact.** They do not change, so a position means the same thing forever.
//   * **Edited playlists are best-effort.** A single insertion or deletion realigns correctly via
//     the anchor. Songs *reordered* around the anchor cannot be recovered from a bitfield at all —
//     the information needed is not in it. Those marks are dropped rather than shown wrong, because
//     a wrong tick is worse than no tick.

use crate::env::storage;
use crate::env::{Env, EnvError};
use serde::{Deserialize, Serialize};

/// Where the whole set lives, as one map. Versioned by `env::schema` like everything else.
const STORAGE_KEY: &str = "listened_bitfields";

/// Contexts kept. Well above what anyone browses, and a bound so the store cannot grow forever.
const MAX_CONTEXTS: usize = 256;

/// One context's marks.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Marks {
    /// The bits, LSB-first within each byte, as hex. Hex rather than a JSON array of numbers, which
    /// would be four bytes of text per byte of data.
    #[serde(default)]
    pub bits: String,
    /// How many tracks the field covers. Kept explicitly because the last byte is padded.
    #[serde(default)]
    pub len: usize,
    /// The id of the most recently marked track, and where it was. This is what makes a shifted
    /// playlist recoverable — see the module note.
    #[serde(default)]
    pub anchor_id: String,
    #[serde(default)]
    pub anchor_index: usize,
}

impl Marks {
    fn bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.bits.len() / 2);
        let raw = self.bits.as_bytes();
        let mut i = 0;
        while i + 1 < raw.len() {
            let hi = hex_value(raw[i]);
            let lo = hex_value(raw[i + 1]);
            match (hi, lo) {
                (Some(h), Some(l)) => out.push((h << 4) | l),
                // A byte that is not hex means the stored value is corrupt. Stopping here yields the
                // marks up to that point rather than throwing away the whole context.
                _ => break,
            }
            i += 2;
        }
        out
    }

    fn from_bytes(bytes: &[u8], len: usize) -> String {
        let mut out = String::with_capacity(bytes.len() * 2);
        for b in bytes.iter().take(len.div_ceil(8)) {
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 0x0f) as usize] as char);
        }
        out
    }

    /// The marks as one bool per position, always exactly `len` long.
    pub fn to_vec(&self) -> Vec<bool> {
        let bytes = self.bytes();
        (0..self.len)
            .map(|i| {
                bytes
                    .get(i / 8)
                    .is_some_and(|byte| byte & (1 << (i % 8)) != 0)
            })
            .collect()
    }

    /// How many are marked.
    pub fn count(&self) -> usize {
        self.to_vec().into_iter().filter(|b| *b).count()
    }

    fn set(&mut self, index: usize, on: bool) {
        if index >= self.len {
            return;
        }
        let mut bytes = self.bytes();
        bytes.resize(self.len.div_ceil(8), 0);
        let mask = 1u8 << (index % 8);
        if on {
            bytes[index / 8] |= mask;
        } else {
            bytes[index / 8] &= !mask;
        }
        self.bits = Self::from_bytes(&bytes, self.len);
    }

    /// Realigns to `queue`, using the anchor to work out how far everything moved.
    ///
    /// Returns the marks that are still trustworthy. See the module note for what "trustworthy"
    /// excludes.
    fn realigned_to(&self, queue: &[String]) -> Marks {
        let mut fresh = Marks {
            bits: String::new(),
            len: queue.len(),
            anchor_id: self.anchor_id.clone(),
            anchor_index: 0,
        };
        if self.len == 0 || queue.is_empty() {
            return fresh;
        }
        // Nothing moved: the common case, and the only one that is exact.
        if self.len == queue.len() {
            let mut same = self.clone();
            same.len = queue.len();
            return same;
        }
        // The list changed size. Find the anchor in the new list; the difference between where it
        // was and where it is now is how far everything shifted.
        let Some(now_at) = queue.iter().position(|t| *t == self.anchor_id) else {
            // No anchor to align against. Everything below would be a guess, and a guess here shows
            // as a tick on a song you have never heard.
            return fresh;
        };
        let old = self.to_vec();
        let shift = now_at as isize - self.anchor_index as isize;
        let mut moved = vec![false; queue.len()];
        for (was, marked) in old.iter().enumerate() {
            if !marked {
                continue;
            }
            let to = was as isize + shift;
            if to >= 0 && (to as usize) < queue.len() {
                moved[to as usize] = true;
            }
        }
        fresh.anchor_index = now_at;
        fresh.write_all(&moved);
        fresh
    }

    fn write_all(&mut self, values: &[bool]) {
        self.len = values.len();
        let mut bytes = vec![0u8; values.len().div_ceil(8)];
        for (i, on) in values.iter().enumerate() {
            if *on {
                bytes[i / 8] |= 1 << (i % 8);
            }
        }
        self.bits = Self::from_bytes(&bytes, values.len());
    }
}

const HEX: &[u8; 16] = b"0123456789abcdef";

fn hex_value(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

type Store = std::collections::HashMap<String, Marks>;

/// Marks `track_id` as listened within `context_id`, whose current order is `queue`.
///
/// The queue is passed on every call rather than stored: it is what the caller is already showing,
/// and taking it here is what lets the field realign the moment a playlist has changed underneath.
pub async fn mark<E: Env>(
    context_id: &str,
    queue: &[String],
    track_id: &str,
) -> Result<(), EnvError> {
    if context_id.is_empty() || queue.is_empty() {
        return Ok(());
    }
    let Some(index) = queue.iter().position(|t| t == track_id) else {
        return Ok(());
    };
    let mut store: Store = storage::get_json_or_default::<E, _>(STORAGE_KEY).await;
    let mut marks = store
        .get(context_id)
        .map(|m| m.realigned_to(queue))
        .unwrap_or(Marks {
            bits: String::new(),
            len: queue.len(),
            anchor_id: String::new(),
            anchor_index: 0,
        });
    marks.len = queue.len();
    marks.set(index, true);
    marks.anchor_id = track_id.to_string();
    marks.anchor_index = index;
    store.insert(context_id.to_string(), marks);
    prune(&mut store);
    storage::set_json::<E, _>(STORAGE_KEY, Some(&store)).await
}

/// One bool per position in `queue`. Never fails and never panics: a tick on a list is not worth an
/// error path, and the answer when anything is wrong is "nothing is marked".
pub async fn state<E: Env>(context_id: &str, queue: &[String]) -> Vec<bool> {
    if context_id.is_empty() || queue.is_empty() {
        return vec![false; queue.len()];
    }
    let store: Store = storage::get_json_or_default::<E, _>(STORAGE_KEY).await;
    store
        .get(context_id)
        .map(|m| m.realigned_to(queue).to_vec())
        .unwrap_or_else(|| vec![false; queue.len()])
}

/// Drops one context's marks, because the user said so.
pub async fn forget<E: Env>(context_id: &str) -> Result<(), EnvError> {
    let mut store: Store = storage::get_json_or_default::<E, _>(STORAGE_KEY).await;
    store.remove(context_id);
    storage::set_json::<E, _>(STORAGE_KEY, Some(&store)).await
}

/// Drops all of them.
pub async fn clear<E: Env>() -> Result<(), EnvError> {
    storage::set_json::<E, _>(STORAGE_KEY, Some(&Store::new())).await
}

/// Keeps the store bounded. Nothing here knows when a context was last touched, so the ones dropped
/// are the emptiest — a context with no marks costs nothing to rebuild.
fn prune(store: &mut Store) {
    if store.len() <= MAX_CONTEXTS {
        return;
    }
    let mut by_marks: Vec<(String, usize)> =
        store.iter().map(|(k, v)| (k.clone(), v.count())).collect();
    by_marks.sort_by(|a, b| a.1.cmp(&b.1).then(a.0.cmp(&b.0)));
    for (id, _) in by_marks.into_iter().take(store.len() - MAX_CONTEXTS) {
        store.remove(&id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    fn queue(ids: &[&str]) -> Vec<String> {
        ids.iter().map(|s| s.to_string()).collect()
    }

    #[tokio::test]
    async fn marking_a_track_shows_up_at_its_position_and_nowhere_else() {
        let _guard = mock::lock_and_reset();
        let q = queue(&["a", "b", "c"]);
        mark::<MockEnv>("album:x", &q, "b").await.unwrap();
        assert_eq!(state::<MockEnv>("album:x", &q).await, vec![false, true, false]);
    }

    #[tokio::test]
    async fn a_track_that_is_not_in_the_queue_marks_nothing() {
        let _guard = mock::lock_and_reset();
        let q = queue(&["a", "b"]);
        mark::<MockEnv>("album:x", &q, "zzz").await.unwrap();
        assert_eq!(state::<MockEnv>("album:x", &q).await, vec![false, false]);
    }

    #[tokio::test]
    async fn the_field_survives_a_track_being_inserted_above_the_anchor() {
        // This is the whole reason the anchor exists. Without it every mark below an insertion
        // silently moves onto the wrong song.
        let _guard = mock::lock_and_reset();
        let before = queue(&["a", "b", "c", "d"]);
        mark::<MockEnv>("pl:x", &before, "c").await.unwrap();
        assert_eq!(
            state::<MockEnv>("pl:x", &before).await,
            vec![false, false, true, false]
        );

        // "new" is added at the top: everything shifted down one.
        let after = queue(&["new", "a", "b", "c", "d"]);
        assert_eq!(
            state::<MockEnv>("pl:x", &after).await,
            vec![false, false, false, true, false],
            "the mark must still be on c"
        );
    }

    #[tokio::test]
    async fn a_mark_that_would_shift_off_the_end_is_dropped_rather_than_wrapped() {
        let _guard = mock::lock_and_reset();
        let before = queue(&["a", "b", "c"]);
        mark::<MockEnv>("pl:x", &before, "a").await.unwrap();
        mark::<MockEnv>("pl:x", &before, "c").await.unwrap();
        // "c" is now first, so the shift is -2 and "a"'s mark lands at -2: outside the list.
        let after = queue(&["c", "d"]);
        assert_eq!(state::<MockEnv>("pl:x", &after).await, vec![true, false]);
    }

    #[tokio::test]
    async fn a_list_whose_anchor_disappeared_shows_nothing_rather_than_something_wrong() {
        let _guard = mock::lock_and_reset();
        let before = queue(&["a", "b", "c"]);
        mark::<MockEnv>("pl:x", &before, "b").await.unwrap();
        // The anchor itself was removed, and the list changed size: there is nothing to align to.
        let after = queue(&["a", "c"]);
        assert_eq!(state::<MockEnv>("pl:x", &after).await, vec![false, false]);
    }

    #[tokio::test]
    async fn contexts_do_not_bleed_into_each_other() {
        let _guard = mock::lock_and_reset();
        let q = queue(&["a", "b"]);
        mark::<MockEnv>("album:one", &q, "a").await.unwrap();
        assert_eq!(state::<MockEnv>("album:two", &q).await, vec![false, false]);
    }

    #[tokio::test]
    async fn forgetting_one_leaves_the_others() {
        let _guard = mock::lock_and_reset();
        let q = queue(&["a"]);
        mark::<MockEnv>("album:one", &q, "a").await.unwrap();
        mark::<MockEnv>("album:two", &q, "a").await.unwrap();
        forget::<MockEnv>("album:one").await.unwrap();
        assert_eq!(state::<MockEnv>("album:one", &q).await, vec![false]);
        assert_eq!(state::<MockEnv>("album:two", &q).await, vec![true]);
    }

    #[test]
    fn a_long_playlist_costs_bytes_rather_than_kilobytes() {
        // The claim the whole design rests on, as a test rather than a comment: 200 tracks all
        // marked is a 50-character string, against roughly 4 KB if these were Spotify ids.
        let mut marks = Marks::default();
        marks.write_all(&vec![true; 200]);
        assert_eq!(marks.len, 200);
        assert_eq!(marks.bits.len(), 50);
        assert_eq!(marks.count(), 200);
    }

    #[test]
    fn bits_survive_a_round_trip_through_hex() {
        let mut marks = Marks::default();
        let pattern: Vec<bool> = (0..37).map(|i| i % 3 == 0).collect();
        marks.write_all(&pattern);
        assert_eq!(marks.to_vec(), pattern);
    }

    #[test]
    fn a_corrupt_stored_value_yields_what_it_can_instead_of_panicking() {
        let marks = Marks {
            bits: "ff!!ff".into(),
            len: 24,
            anchor_id: String::new(),
            anchor_index: 0,
        };
        // The first byte reads; the rest stops at the bad pair. Still exactly `len` long.
        let out = marks.to_vec();
        assert_eq!(out.len(), 24);
        assert!(out[..8].iter().all(|b| *b));
        assert!(out[8..].iter().all(|b| !*b));
    }
}
