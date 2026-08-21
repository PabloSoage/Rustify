// core_engine/src/server/cache.rs
//
// The on-disk stream cache: where a track lives once it has been played, so the second play is a
// file read instead of a resolve plus a download.
//
// Two rules, and both exist because a cache that only grows is a bug with a friendly name:
//
//   1. **A budget, enforced after every fill.** Oldest first, by modification time.
//   2. **Flat, sanitised names.** The key comes from the app — a track id, a Deezer song id — and
//      an id is attacker-adjacent input the moment an add-on can influence it. `sanitise_key`
//      already refuses anything that could climb out of the directory, and everything lands
//      directly in the root, so there is no tree to walk and nothing to recurse into.

use crate::env::storage::sanitise_key;
use std::path::{Path, PathBuf};

/// How much disk the stream cache may use before the oldest entries are dropped.
///
/// 512 MiB is roughly a hundred lossy tracks or a dozen lossless ones. It is a constant rather than
/// a setting because a setting nobody changes is worse than a number written down.
pub const DEFAULT_BUDGET_BYTES: u64 = 512 * 1024 * 1024;

/// The path a cache key maps to, under `root`.
pub fn path_for(root: &str, key: &str) -> PathBuf {
    Path::new(root).join(sanitise_key(key))
}

/// Total size of the cache in bytes.
pub async fn size(root: &str) -> u64 {
    let mut total = 0;
    let mut dir = match tokio::fs::read_dir(root).await {
        Ok(dir) => dir,
        Err(_) => return 0,
    };
    while let Ok(Some(item)) = dir.next_entry().await {
        if let Ok(meta) = item.metadata().await {
            if meta.is_file() {
                total += meta.len();
            }
        }
    }
    total
}

/// Deletes the oldest entries until the cache fits in `budget`. Returns how many bytes it freed.
///
/// Oldest by modification time, which for this cache means least recently *written* rather than
/// least recently read — Android gives no reliable atime, and a true LRU would mean writing a
/// sidecar on every play, which is a write per play to save a download per week.
pub async fn sweep(root: &str, budget: u64) -> u64 {
    let mut entries: Vec<(std::time::SystemTime, u64, PathBuf)> = Vec::new();
    let mut total: u64 = 0;

    let mut dir = match tokio::fs::read_dir(root).await {
        Ok(dir) => dir,
        Err(_) => return 0,
    };
    while let Ok(Some(item)) = dir.next_entry().await {
        let meta = match item.metadata().await {
            Ok(meta) if meta.is_file() => meta,
            _ => continue,
        };
        let modified = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
        total += meta.len();
        entries.push((modified, meta.len(), item.path()));
    }

    if total <= budget {
        return 0;
    }

    entries.sort_by_key(|(modified, _, _)| *modified);
    let mut freed = 0;
    for (_, len, path) in entries {
        if total <= budget {
            break;
        }
        if tokio::fs::remove_file(&path).await.is_ok() {
            total = total.saturating_sub(len);
            freed += len;
        }
    }
    freed
}

/// Empties the cache. For the Settings screen, and for a user who wants their disk back.
pub async fn clear(root: &str) -> u64 {
    sweep(root, 0).await
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn scratch() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "rustify-cache-{}",
            crate::server::random_hex(8)
        ));
        tokio::fs::create_dir_all(&dir).await.unwrap();
        dir
    }

    #[test]
    fn a_key_cannot_climb_out_of_the_cache_directory() {
        // The property is **one path component, directly under the root** — not "the string has no
        // dots in it". `../../etc/passwd` sanitises to `k.._.._etc_passwd`, which still reads as
        // dots but cannot traverse anywhere, because every separator became an underscore. An
        // earlier version of this test asserted the absence of `..` in the text and failed on a
        // path that was perfectly safe; asserting the shape says what actually matters.
        for hostile in [
            "../../etc/passwd",
            "..",
            ".",
            "/etc/shadow",
            "..\\..\\windows\\system32",
            "",
        ] {
            let path = path_for("/data/cache", hostile);
            assert_eq!(
                path.parent().unwrap(),
                Path::new("/data/cache"),
                "{hostile} escaped the cache directory"
            );
            let name = path.file_name().unwrap().to_string_lossy().to_string();
            assert!(!name.is_empty(), "{hostile} produced no file name");
            assert_ne!(name, ".", "{hostile} named the directory itself");
            assert_ne!(name, "..", "{hostile} named the parent directory");
            assert!(
                !name.contains('/') && !name.contains('\\'),
                "{hostile} kept a separator: {name}"
            );
            // One component and no more: `components()` over the whole path must be exactly the
            // root plus this name.
            assert_eq!(
                Path::new("/data/cache").join(&name),
                path,
                "{hostile} did not stay a single component"
            );
        }
    }

    #[tokio::test]
    async fn sweeping_under_budget_deletes_nothing() {
        let dir = scratch().await;
        tokio::fs::write(dir.join("a"), vec![0u8; 1000]).await.unwrap();
        let freed = sweep(dir.to_str().unwrap(), 10_000).await;
        assert_eq!(freed, 0);
        assert!(dir.join("a").exists());
        let _ = tokio::fs::remove_dir_all(&dir).await;
    }

    #[tokio::test]
    async fn sweeping_drops_the_oldest_first_and_stops_at_the_budget() {
        let dir = scratch().await;
        let root = dir.to_str().unwrap().to_owned();

        // The modification times are set explicitly rather than inferred from the order of the
        // writes: several filesystems have one-second timestamp resolution, so three files written
        // in a row can easily share a timestamp and the test would pass or fail by luck.
        for (name, age_secs) in [("old", 300u64), ("middle", 200), ("new", 100)] {
            let path = dir.join(name);
            tokio::fs::write(&path, vec![0u8; 1000]).await.unwrap();
            let when = std::time::SystemTime::now() - std::time::Duration::from_secs(age_secs);
            filetime::set_file_mtime(&path, filetime::FileTime::from_system_time(when)).unwrap();
        }

        // Budget fits two of the three files.
        let freed = sweep(&root, 2_000).await;
        assert_eq!(freed, 1000);
        assert!(!dir.join("old").exists(), "the oldest entry should have gone");
        assert!(dir.join("middle").exists());
        assert!(dir.join("new").exists());
        assert_eq!(size(&root).await, 2000);

        let _ = tokio::fs::remove_dir_all(&dir).await;
    }

    #[tokio::test]
    async fn clearing_empties_the_cache() {
        let dir = scratch().await;
        let root = dir.to_str().unwrap().to_owned();
        tokio::fs::write(dir.join("a"), vec![0u8; 10]).await.unwrap();
        tokio::fs::write(dir.join("b"), vec![0u8; 10]).await.unwrap();
        assert_eq!(clear(&root).await, 20);
        assert_eq!(size(&root).await, 0);
        let _ = tokio::fs::remove_dir_all(&dir).await;
    }
}
