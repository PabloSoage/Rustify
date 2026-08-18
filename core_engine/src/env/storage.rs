// core_engine/src/env/storage.rs
//
// Typed helpers over the raw string storage in [`Env`].
//
// The trait itself stores strings, not `T: Serialize`. `stremio-core` makes storage generic at the
// trait level; keeping serialization *on top* means the contract a platform has to implement stays
// four small methods, and it is the layer that changes least.

use super::{Env, EnvError};
use serde::de::DeserializeOwned;
use serde::Serialize;

/// Reads and deserializes. A missing key is `Ok(None)`; corrupt contents are `Err`.
///
/// Corruption is deliberately *not* swallowed into `None`. Silently treating unreadable state as
/// "not there yet" is how a bad write becomes a wiped library on the next save.
pub async fn get_json<E: Env, T: DeserializeOwned>(key: &str) -> Result<Option<T>, EnvError> {
    match E::get_storage(key).await? {
        Some(raw) => Ok(Some(serde_json::from_str(&raw)?)),
        None => Ok(None),
    }
}

/// Serializes and writes. `None` deletes the key.
pub async fn set_json<E: Env, T: Serialize>(key: &str, value: Option<&T>) -> Result<(), EnvError> {
    match value {
        Some(value) => {
            let raw = serde_json::to_string(value)?;
            E::set_storage(key, Some(&raw)).await
        }
        None => E::set_storage(key, None).await,
    }
}

/// Like [`get_json`], but a corrupt value yields the default instead of an error.
///
/// For caches only — things that can be rebuilt. Never for user data.
pub async fn get_json_or_default<E: Env, T: DeserializeOwned + Default>(key: &str) -> T {
    match get_json::<E, T>(key).await {
        Ok(Some(value)) => value,
        _ => T::default(),
    }
}

/// Maps a storage key onto a safe file name.
///
/// Keys come from code, not from users, but this is the function that stands between a key and the
/// filesystem — so it is written as if they did. Anything outside `[A-Za-z0-9._-]` becomes `_`,
/// which collapses `../` before it can mean anything.
pub fn sanitise_key(key: &str) -> String {
    let mut out = String::with_capacity(key.len().max(1));
    for c in key.chars() {
        if c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-' {
            out.push(c);
        } else {
            out.push('_');
        }
    }
    // A leading dot would make a hidden file, and "." / ".." would name a directory.
    if out.is_empty() || out.starts_with('.') {
        out.insert(0, 'k');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn traversal_cannot_survive_sanitising() {
        assert_eq!(sanitise_key("../../etc/passwd"), "k.._.._etc_passwd");
        assert_eq!(sanitise_key("a/b\\c"), "a_b_c");
        assert_eq!(sanitise_key(""), "k");
        assert_eq!(sanitise_key("."), "k.");
        assert_eq!(sanitise_key(".."), "k..");
    }

    #[test]
    fn ordinary_keys_pass_through_unchanged() {
        assert_eq!(sanitise_key("youtube_mappings"), "youtube_mappings");
        assert_eq!(sanitise_key("schema.version-2"), "schema.version-2");
    }
}
