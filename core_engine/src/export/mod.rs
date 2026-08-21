// core_engine/src/export/mod.rs
//
// Data export — point L.
//
// The evaluation called it "a minor but healthy idea", and the healthy part is worth naming: there
// is already a Drive backup, but a Drive backup is a *restore* mechanism, not a *portability* one.
// It answers "my phone died"; it does not answer "I want to read what this app knows about me", or
// "I am leaving". A file on disk answers both, and costs almost nothing because everything the
// engine persists already goes through one boundary.
//
// It goes both ways on purpose. An export you cannot restore is a museum piece — you find out it
// was incomplete on the day you need it, which is the day it is too late. The round trip is a test
// here rather than a promise.
//
// ## What is not in here
//
// Credentials. Not the Spotify session, not the Deezer ARL, not an add-on's URL parameters if it
// carries any. An export is a file that gets mailed to a laptop, dropped in a chat, or handed to
// someone for help; the one thing it must never be is a way to hand over an account. That is a
// decision, not an oversight, so [`redacted`] lists what is skipped and a test checks the list.

use crate::env::{Env, EnvError};
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Bumped when the shape of the document changes, independently of `SCHEMA_VERSION` — this is the
/// format of the *file*, which outlives the app that wrote it.
pub const EXPORT_VERSION: u32 = 1;

/// The storage keys an export carries, in the order they appear in the file.
///
/// Listed explicitly rather than "everything in storage" so that adding a key that holds a secret
/// does not silently start exporting it. A new key is opted **in**, here, on purpose.
const EXPORTED_KEYS: &[&str] = &[
    "addons",
    "youtube_mappings",
    "continue_listening",
    "listened_bitfields",
];

/// Keys deliberately left out, with the reason attached so the next person does not "fix" it.
pub fn redacted() -> &'static [(&'static str, &'static str)] {
    &[
        ("spotify_credentials", "an account, not data about you"),
        ("spotify_sp_dc", "a session cookie: whoever holds it is you"),
        ("deezer_arl", "the same, for Deezer"),
        ("schema_version", "describes this device's store, not your data"),
    ]
}

/// The document. Flat and boring on purpose: it is meant to be read by a person in a text editor as
/// much as by this code.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Export {
    /// Format version of the file itself.
    pub version: u32,
    /// When it was written, milliseconds since the epoch. Informational; nothing branches on it.
    pub exported_at_ms: i64,
    /// Which app wrote it, so a future reader knows what produced a shape it does not recognise.
    pub produced_by: String,
    /// Key → the stored value, already parsed. Missing keys are simply absent rather than null.
    pub data: serde_json::Map<String, Value>,
}

/// Builds the export document.
pub async fn build<E: Env>(produced_by: &str) -> Result<Export, EnvError> {
    let mut data = serde_json::Map::new();
    for key in EXPORTED_KEYS {
        // A key that was never written is not an error; it is a user who never used that feature.
        if let Some(raw) = E::get_storage(key).await? {
            match serde_json::from_str::<Value>(&raw) {
                Ok(value) => {
                    data.insert((*key).to_string(), value);
                }
                // Unparseable stored state is worth carrying as text rather than dropping: whoever
                // reads the file can still see what was there, which is the point of an export.
                Err(_) => {
                    data.insert((*key).to_string(), Value::String(raw));
                }
            }
        }
    }
    Ok(Export {
        version: EXPORT_VERSION,
        exported_at_ms: E::now_ms(),
        produced_by: produced_by.to_string(),
        data,
    })
}

/// The export as the text that gets written to a file. Pretty-printed: this is a document a person
/// may open, and one line of 40 KB is not a document.
pub async fn to_json<E: Env>(produced_by: &str) -> Result<String, EnvError> {
    let export = build::<E>(produced_by).await?;
    serde_json::to_string_pretty(&export)
        .map_err(|e| EnvError::Other(format!("could not serialise the export: {e}")))
}

/// What an import did, so the caller can tell the user rather than just saying "done".
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Restored {
    /// Keys that were written.
    pub keys: Vec<String>,
    /// Keys present in the file that this build does not know about. Named rather than silently
    /// ignored: it is how someone finds out they restored an export from a newer version.
    pub skipped: Vec<String>,
}

/// Restores a document produced by [`to_json`].
///
/// **Replaces rather than merges.** Merging two histories of "what you have listened to" needs a
/// rule for every conflict, and the rule people actually expect from a file they chose to restore is
/// "make it look like that file". Merge belongs to the Drive backup, which has the union and
/// last-write-wins logic for exactly this reason.
pub async fn restore<E: Env>(json: &str) -> Result<Restored, EnvError> {
    let export: Export = serde_json::from_str(json)
        .map_err(|e| EnvError::Other(format!("this does not look like a Rustify export: {e}")))?;

    if export.version > EXPORT_VERSION {
        return Err(EnvError::Other(format!(
            "this export is version {} and this build understands {EXPORT_VERSION}; \
             restoring it could lose what it does not recognise",
            export.version
        )));
    }

    let mut restored = Restored::default();
    for (key, value) in export.data {
        if !EXPORTED_KEYS.contains(&key.as_str()) {
            restored.skipped.push(key);
            continue;
        }
        let text = serde_json::to_string(&value)
            .map_err(|e| EnvError::Other(format!("could not re-encode {key}: {e}")))?;
        E::set_storage(&key, Some(&text)).await?;
        restored.keys.push(key);
    }
    restored.keys.sort();
    restored.skipped.sort();
    Ok(restored)
}

#[cfg(test)]
mod tests {
    use super::*;
    // Only the tests seed and read storage directly; the module itself goes through `Env`, which is
    // the point — an export reads what is there rather than what a helper decides is there.
    use crate::env::storage;
    use crate::env::mock::{self, MockEnv};

    async fn seed() {
        storage::set_json::<MockEnv, _>("continue_listening", Some(&vec!["one", "two"]))
            .await
            .unwrap();
        storage::set_json::<MockEnv, _>("youtube_mappings", Some(&vec!["a"]))
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn what_goes_out_comes_back_identical() {
        let _guard = mock::lock_and_reset();
        seed().await;
        let json = to_json::<MockEnv>("test").await.unwrap();

        // Wipe everything, then restore.
        storage::set_json::<MockEnv, _>("continue_listening", Some(&Vec::<String>::new()))
            .await
            .unwrap();
        storage::set_json::<MockEnv, _>("youtube_mappings", Some(&Vec::<String>::new()))
            .await
            .unwrap();

        let restored = restore::<MockEnv>(&json).await.unwrap();
        assert_eq!(restored.keys, vec!["continue_listening", "youtube_mappings"]);
        assert!(restored.skipped.is_empty());

        let sessions: Vec<String> =
            storage::get_json_or_default::<MockEnv, _>("continue_listening").await;
        assert_eq!(sessions, vec!["one", "two"]);
    }

    #[tokio::test]
    async fn no_credential_ever_reaches_the_file() {
        // The property the whole module is judged on. If a future key holds a secret and someone
        // adds it to EXPORTED_KEYS, this test is what says no.
        let _guard = mock::lock_and_reset();
        for (key, _) in redacted() {
            MockEnv::set_storage(key, Some("SECRET-VALUE")).await.unwrap();
        }
        seed().await;

        let json = to_json::<MockEnv>("test").await.unwrap();
        assert!(
            !json.contains("SECRET-VALUE"),
            "an export must never carry a credential"
        );
        for (key, _) in redacted() {
            assert!(!json.contains(key), "{key} must not appear in an export");
        }
    }

    #[tokio::test]
    async fn a_key_this_build_does_not_know_is_named_rather_than_written() {
        let _guard = mock::lock_and_reset();
        let json = r#"{
            "version": 1,
            "exported_at_ms": 0,
            "produced_by": "a future Rustify",
            "data": { "continue_listening": ["kept"], "something_new": {"a": 1} }
        }"#;
        let restored = restore::<MockEnv>(json).await.unwrap();
        assert_eq!(restored.keys, vec!["continue_listening"]);
        assert_eq!(restored.skipped, vec!["something_new"]);
    }

    #[tokio::test]
    async fn an_export_from_a_newer_format_is_refused_rather_than_half_restored() {
        let _guard = mock::lock_and_reset();
        let json = r#"{"version": 99, "exported_at_ms": 0, "produced_by": "x", "data": {}}"#;
        assert!(restore::<MockEnv>(json).await.is_err());
    }

    #[tokio::test]
    async fn something_that_is_not_an_export_fails_before_touching_storage() {
        let _guard = mock::lock_and_reset();
        seed().await;
        assert!(restore::<MockEnv>("{\"hello\": true}").await.is_err());
        // And the store is untouched: a failed import must not leave half a restore behind.
        let sessions: Vec<String> =
            storage::get_json_or_default::<MockEnv, _>("continue_listening").await;
        assert_eq!(sessions, vec!["one", "two"]);
    }

    #[tokio::test]
    async fn a_feature_never_used_is_absent_rather_than_null() {
        let _guard = mock::lock_and_reset();
        let export = build::<MockEnv>("test").await.unwrap();
        assert!(!export.data.contains_key("addons"));
    }
}
