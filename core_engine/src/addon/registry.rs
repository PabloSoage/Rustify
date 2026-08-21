// core_engine/src/addon/registry.rs
//
// Which addons are installed, in what order, and whether each is on.
//
// Stored through `Env`, so it is versioned by `env::schema` like everything else and shared by any
// platform that implements the trait.

use super::manifest::Manifest;
use super::{transport, AddonError};
use crate::env::{storage, Env, LogLevel};
use serde::{Deserialize, Serialize};

/// Storage key for the installed list.
pub const ADDONS_KEY: &str = "addons";

/// A cap, because an unbounded list is a fallback chain that takes forever to exhaust.
pub const MAX_ADDONS: usize = 16;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InstalledAddon {
    pub manifest: Manifest,
    /// Normalised, no trailing slash. Endpoints are this plus a fixed path.
    pub base_url: String,
    /// Off means "keep it, do not ask it". A kill switch that does not lose the installation.
    #[serde(default = "yes")]
    pub enabled: bool,
}

fn yes() -> bool {
    true
}

/// The installed list, in the order they will be tried.
pub async fn list<E: Env>() -> Vec<InstalledAddon> {
    storage::get_json_or_default::<E, Vec<InstalledAddon>>(ADDONS_KEY).await
}

/// Only the ones worth asking, in order.
pub async fn enabled<E: Env>() -> Vec<InstalledAddon> {
    list::<E>().await.into_iter().filter(|a| a.enabled).collect()
}

/// Installs (or re-installs) the addon served at `base_url`.
///
/// Fetches the manifest first, so an addon that cannot be reached or does not describe itself
/// properly never makes it into the list. Re-installing an existing id **replaces it in place**,
/// keeping its position and its enabled flag: updating an addon should not silently re-enable one
/// the user turned off, nor move it to the end of their fallback order.
pub async fn install<E: Env>(base_url: &str) -> Result<InstalledAddon, AddonError> {
    let (manifest, normalised) = transport::fetch_manifest::<E>(base_url).await?;

    let mut addons = list::<E>().await;
    let existing = addons.iter().position(|a| a.manifest.id == manifest.id);

    let entry = InstalledAddon {
        base_url: normalised,
        enabled: existing.map(|i| addons[i].enabled).unwrap_or(true),
        manifest,
    };

    match existing {
        Some(i) => addons[i] = entry.clone(),
        None => {
            if addons.len() >= MAX_ADDONS {
                return Err(AddonError::Refused(format!(
                    "at most {MAX_ADDONS} addons can be installed"
                )));
            }
            addons.push(entry.clone());
        }
    }

    save::<E>(&addons).await?;
    E::log(
        LogLevel::Info,
        "Addons",
        &format!("installed {} ({})", entry.manifest.id, entry.base_url),
    );
    Ok(entry)
}

pub async fn uninstall<E: Env>(id: &str) -> Result<(), AddonError> {
    let mut addons = list::<E>().await;
    let before = addons.len();
    addons.retain(|a| a.manifest.id != id);
    if addons.len() == before {
        return Ok(());
    }
    save::<E>(&addons).await
}

pub async fn set_enabled<E: Env>(id: &str, enabled: bool) -> Result<(), AddonError> {
    let mut addons = list::<E>().await;
    let mut touched = false;
    for addon in addons.iter_mut() {
        if addon.manifest.id == id {
            addon.enabled = enabled;
            touched = true;
        }
    }
    if !touched {
        return Ok(());
    }
    save::<E>(&addons).await
}

/// Reorders the list to match `ids`. Anything not named keeps its relative order at the end, so a
/// partial list cannot silently drop an addon.
pub async fn reorder<E: Env>(ids: &[String]) -> Result<(), AddonError> {
    let addons = list::<E>().await;
    let mut ordered: Vec<InstalledAddon> = Vec::with_capacity(addons.len());
    for id in ids {
        if let Some(found) = addons.iter().find(|a| &a.manifest.id == id) {
            ordered.push(found.clone());
        }
    }
    for addon in &addons {
        if !ordered.iter().any(|a| a.manifest.id == addon.manifest.id) {
            ordered.push(addon.clone());
        }
    }
    save::<E>(&ordered).await
}

async fn save<E: Env>(addons: &[InstalledAddon]) -> Result<(), AddonError> {
    storage::set_json::<E, _>(ADDONS_KEY, Some(&addons.to_vec()))
        .await
        .map_err(AddonError::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::mock::{self, MockEnv};
    use crate::env::HttpResponse;

    fn manifest_json(id: &str) -> String {
        format!(r#"{{"id":"{id}","version":"1","name":"n","resources":["stream"]}}"#)
    }

    async fn install_at(host_path: &str, id: &str) -> Result<InstalledAddon, AddonError> {
        mock::on_url(
            format!("{host_path}/manifest.json"),
            HttpResponse::ok(manifest_json(id)),
        );
        // Since 3.3 an install resolves the host, judges every address it got, and pins the
        // connection to what it approved — so a test that only cans the HTTP answer now stops one
        // step earlier, at "no canned DNS answer". Seeded here rather than in each test because it
        // is a property of the transport, not of what any of these four is checking.
        if let Some(host) = url::Url::parse(host_path).ok().and_then(|u| {
            u.host_str().map(str::to_owned)
        }) {
            mock::on_dns(host, vec![std::net::IpAddr::V4(std::net::Ipv4Addr::new(93, 184, 216, 34))]);
        }
        install::<MockEnv>(host_path).await
    }

    #[tokio::test]
    async fn installing_stores_the_addon() {
        let _guard = mock::lock_and_reset();
        install_at("https://a.example.org/x", "org.a").await.unwrap();
        let stored = list::<MockEnv>().await;
        assert_eq!(stored.len(), 1);
        assert_eq!(stored[0].manifest.id, "org.a");
        assert!(stored[0].enabled);
    }

    #[tokio::test]
    async fn reinstalling_keeps_position_and_the_off_switch() {
        let _guard = mock::lock_and_reset();
        install_at("https://a.example.org/x", "org.a").await.unwrap();
        install_at("https://b.example.org/y", "org.b").await.unwrap();
        set_enabled::<MockEnv>("org.a", false).await.unwrap();

        // Updating an addon must not re-enable one the user turned off, nor move it to the end of
        // their fallback order.
        install_at("https://a.example.org/x", "org.a").await.unwrap();

        let stored = list::<MockEnv>().await;
        assert_eq!(stored.len(), 2);
        assert_eq!(stored[0].manifest.id, "org.a");
        assert!(!stored[0].enabled);
        assert_eq!(enabled::<MockEnv>().await.len(), 1);
    }

    #[tokio::test]
    async fn uninstalling_removes_only_that_one() {
        let _guard = mock::lock_and_reset();
        install_at("https://a.example.org/x", "org.a").await.unwrap();
        install_at("https://b.example.org/y", "org.b").await.unwrap();
        uninstall::<MockEnv>("org.a").await.unwrap();
        let stored = list::<MockEnv>().await;
        assert_eq!(stored.len(), 1);
        assert_eq!(stored[0].manifest.id, "org.b");
    }

    #[tokio::test]
    async fn reordering_by_a_partial_list_drops_nothing() {
        let _guard = mock::lock_and_reset();
        install_at("https://a.example.org/x", "org.a").await.unwrap();
        install_at("https://b.example.org/y", "org.b").await.unwrap();
        install_at("https://c.example.org/z", "org.c").await.unwrap();

        reorder::<MockEnv>(&["org.c".to_string()]).await.unwrap();

        let ids: Vec<String> = list::<MockEnv>()
            .await
            .into_iter()
            .map(|a| a.manifest.id)
            .collect();
        assert_eq!(ids, vec!["org.c", "org.a", "org.b"]);
    }

    #[tokio::test]
    async fn an_addon_that_cannot_be_reached_is_not_installed() {
        let _guard = mock::lock_and_reset();
        // No canned response at all, so the fetch fails.
        assert!(install::<MockEnv>("https://nope.example.org/x").await.is_err());
        assert!(list::<MockEnv>().await.is_empty());
    }
}
