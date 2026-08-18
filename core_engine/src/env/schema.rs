// core_engine/src/env/schema.rs
//
// Versioned migration of persisted state. Point E of docs/stremio-core/PLAN-3.x.md, done alongside
// A because it costs almost nothing here and cannot be retrofitted later: the whole value of a
// schema version is that it was written down *before* the first shape change, not after.
//
// The idea is `stremio-core`'s (theirs chains 25 versions). The mechanism is deliberately dull: a
// single integer under one key, and a chain of one-way steps.

use super::{storage, Env, EnvError, LogLevel};

/// Bump this when a stored shape changes, and add the matching step to [`migrate`].
pub const SCHEMA_VERSION: u32 = 1;

pub const SCHEMA_VERSION_KEY: &str = "schema_version";

/// Where the YouTube id mappings live from v1 onwards.
pub const MAPPINGS_KEY: &str = "youtube_mappings";

/// The file the engine wrote before this abstraction existed. Read once, during the v1 step, and
/// **never deleted** — see [`migrate_to_v1`].
pub const LEGACY_MAPPINGS_FILE: &str = "youtube_mappings.json";

/// Brings persisted state up to [`SCHEMA_VERSION`].
///
/// `legacy_dir` is the pre-3.0 cache directory, or `None` where there is none (tests, and any
/// platform that never had one).
///
/// A newer schema than this build understands is an **error, not something to repair**: a downgrade
/// that rewrites state to an older shape destroys whatever the newer build added.
pub async fn migrate<E: Env>(legacy_dir: Option<&str>) -> Result<(), EnvError> {
    let stored = storage::get_json::<E, u32>(SCHEMA_VERSION_KEY)
        .await
        .unwrap_or(None);
    let mut version = stored.unwrap_or(0);

    if version > SCHEMA_VERSION {
        return Err(EnvError::Storage(format!(
            "stored schema is v{version} but this build understands v{SCHEMA_VERSION}; \
             refusing to downgrade"
        )));
    }
    if version == SCHEMA_VERSION {
        return Ok(());
    }

    if version == 0 {
        migrate_to_v1::<E>(legacy_dir).await?;
        version = 1;
    }

    storage::set_json::<E, u32>(SCHEMA_VERSION_KEY, Some(&version)).await?;
    E::log(
        LogLevel::Info,
        "Schema",
        &format!("storage schema is now v{version}"),
    );
    Ok(())
}

/// v0 → v1: adopt the manual YouTube id mappings that used to live in a loose JSON file.
///
/// Two deliberate choices, both about not losing user data:
///
///  * the legacy file is **copied, never moved**. Manual mappings are the one thing in that
///    directory the user actually curated by hand, and leaving the original in place means a
///    rollback to 2.12.14 still finds them;
///  * an existing `MAPPINGS_KEY` **wins**. If storage already has mappings, this build has already
///    run and the legacy file is stale; overwriting with it would resurrect deleted entries.
async fn migrate_to_v1<E: Env>(legacy_dir: Option<&str>) -> Result<(), EnvError> {
    if E::get_storage(MAPPINGS_KEY).await?.is_some() {
        return Ok(());
    }
    let dir = match legacy_dir {
        Some(dir) => dir,
        None => return Ok(()),
    };
    let path = format!("{dir}/{LEGACY_MAPPINGS_FILE}");
    let raw = match tokio::fs::read_to_string(&path).await {
        Ok(raw) => raw,
        // Nothing to carry over: a fresh install, which is the common case.
        Err(_) => return Ok(()),
    };
    // Parsed rather than copied blind, so a corrupt legacy file becomes "no mappings" instead of a
    // corrupt value under the new key.
    if serde_json::from_str::<std::collections::HashMap<String, String>>(&raw).is_err() {
        E::log(
            LogLevel::Warn,
            "Schema",
            "legacy youtube_mappings.json is unreadable; starting empty",
        );
        return Ok(());
    }
    E::set_storage(MAPPINGS_KEY, Some(&raw)).await?;
    E::log(
        LogLevel::Info,
        "Schema",
        "carried legacy youtube_mappings.json into versioned storage",
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};

    #[tokio::test]
    async fn a_fresh_install_lands_on_the_current_version() {
        let _guard = mock::lock_and_reset();
        migrate::<MockEnv>(None).await.unwrap();
        assert_eq!(
            storage::get_json::<MockEnv, u32>(SCHEMA_VERSION_KEY)
                .await
                .unwrap(),
            Some(SCHEMA_VERSION)
        );
    }

    #[tokio::test]
    async fn migrating_twice_is_a_no_op() {
        let _guard = mock::lock_and_reset();
        MockEnv::set_storage(MAPPINGS_KEY, Some(r#"{"a":"b"}"#))
            .await
            .unwrap();
        migrate::<MockEnv>(None).await.unwrap();
        migrate::<MockEnv>(None).await.unwrap();
        assert_eq!(
            MockEnv::get_storage(MAPPINGS_KEY).await.unwrap(),
            Some(r#"{"a":"b"}"#.to_owned())
        );
    }

    #[tokio::test]
    async fn a_newer_schema_is_refused_rather_than_downgraded() {
        let _guard = mock::lock_and_reset();
        storage::set_json::<MockEnv, u32>(SCHEMA_VERSION_KEY, Some(&(SCHEMA_VERSION + 7)))
            .await
            .unwrap();
        assert!(migrate::<MockEnv>(None).await.is_err());
    }

    #[tokio::test]
    async fn existing_mappings_are_not_replaced_by_the_legacy_file() {
        let _guard = mock::lock_and_reset();
        MockEnv::set_storage(MAPPINGS_KEY, Some(r#"{"kept":"yes"}"#))
            .await
            .unwrap();
        // A legacy directory that does exist would still lose to what is already stored.
        migrate_to_v1::<MockEnv>(Some("/nonexistent")).await.unwrap();
        assert_eq!(
            MockEnv::get_storage(MAPPINGS_KEY).await.unwrap(),
            Some(r#"{"kept":"yes"}"#.to_owned())
        );
    }
}
