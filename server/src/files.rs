// server/src/files.rs
//
// `POST /api/file`: the app's own files, for the interface in the browser, which has no disk (`AppFile`
// on the web). One folder of the server's, with `/files` and `/cache` under it, as the other platforms
// have `filesDir` and `cacheDir`, and nothing outside it can be named: a path is taken apart before it is
// used. Settings, playlists and the like. The music the server was told to offer (`--music-dir`) is under
// `/music/<n>`, read only.

use std::path::{Component, Path, PathBuf};

use axum::body::Bytes;
use axum::extract::{Path as Path_, Request, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use base64::Engine;
use serde_json::{json, Value};
use tower::ServiceExt;
use tower_http::services::ServeFile;

use crate::App;

const ENGINE: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

/// The folders a browser can name: the interface's own, and the music the server was told to offer.
#[derive(Clone, Default)]
pub struct Roots {
    pub web: PathBuf,
    pub music: Vec<PathBuf>,
}

impl Roots {
    /// The file `/music/<n>/...` names, in the folder given to the server. A link inside it that leads
    /// outside it is refused: what is offered is the folder, not whatever it points at.
    pub fn music(&self, path: &str) -> Option<PathBuf> {
        let rest = path.strip_prefix("/music/")?;
        let (index, rest) = rest.split_once('/').unwrap_or((rest, ""));
        let root = self.music.get(index.parse::<usize>().ok()?)?;
        let mut out = root.clone();
        for part in Path::new(rest).components() {
            match part {
                Component::Normal(name) => out.push(name),
                _ => return None,
            }
        }
        let real = out.canonicalize().ok()?;
        real.starts_with(root.canonicalize().ok()?).then_some(real)
    }

    /// What [path] names, and whether it may be changed. `/files` and `/cache` may; the music may not.
    fn resolve(&self, path: &str) -> Option<(PathBuf, bool)> {
        if path.starts_with("/music/") {
            return self.music(path).map(|p| (p, false));
        }
        confine(&self.web, path).map(|p| (p, true))
    }

    /// For `/api/raw`: a file to read, of either kind.
    pub fn readable(&self, path: &str) -> Option<PathBuf> {
        self.resolve(path).map(|(p, _)| p)
    }
}

/// Where [path] is under [root], or `None` when it is not in `/files` or `/cache`, or climbs out.
pub fn confine(root: &Path, path: &str) -> Option<PathBuf> {
    let mut out = root.to_path_buf();
    let mut parts = Path::new(path).components();
    if parts.next()? != Component::RootDir {
        return None;
    }
    let area = parts.next()?;
    if area != Component::Normal("files".as_ref()) && area != Component::Normal("cache".as_ref()) {
        return None;
    }
    out.push(area);
    for part in parts {
        match part {
            Component::Normal(name) => out.push(name),
            _ => return None,
        }
    }
    Some(out)
}

fn millis(meta: &std::fs::Metadata) -> u64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_millis() as u64)
}

/// One request. What failed answers `{"ok":false}`, which `AppFile` turns into what `java.io` would do.
pub fn answer(roots: &Roots, request: &Value) -> Option<Value> {
    let op = request.get("op")?.as_str()?;
    let asked = request.get("path")?.as_str()?;
    // The music folders themselves, by number, as if `/music` were a folder.
    if asked == "/music" {
        let entries: Vec<Value> = (0..roots.music.len()).map(|i| json!({ "name": i.to_string(), "folder": true })).collect();
        return Some(match op {
            "stat" => json!({ "ok": true, "exists": true, "folder": true }),
            "list" => json!({ "ok": true, "entries": entries }),
            _ => return None,
        });
    }
    let (path, writable) = roots.resolve(asked)?;
    if !writable && !matches!(op, "stat" | "read" | "list") {
        return None;
    }
    let data = || request.get("data").and_then(Value::as_str).and_then(|d| ENGINE.decode(d).ok());
    let ok = |done: bool| json!({ "ok": done });
    Some(match op {
        "stat" => match std::fs::metadata(&path) {
            Ok(meta) => json!({ "ok": true, "exists": true, "folder": meta.is_dir(), "size": meta.len(), "modified": millis(&meta) }),
            Err(_) => ok(true),
        },
        "read" => match std::fs::read(&path) {
            Ok(mut bytes) => {
                if let Some(length) = request.get("length").and_then(Value::as_u64) {
                    bytes.truncate(length as usize);
                }
                json!({ "ok": true, "exists": true, "data": ENGINE.encode(bytes) })
            }
            Err(_) => ok(false),
        },
        "write" | "append" => {
            let bytes = data()?;
            if let Some(parent) = path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            let written = if op == "write" {
                std::fs::write(&path, bytes).is_ok()
            } else {
                use std::io::Write;
                std::fs::OpenOptions::new().create(true).append(true).open(&path).and_then(|mut f| f.write_all(&bytes)).is_ok()
            };
            ok(written)
        }
        "rename" => {
            let target = confine(&roots.web, request.get("target")?.as_str()?)?;
            ok(std::fs::rename(&path, &target).is_ok())
        }
        "delete" => {
            let done = if path.is_dir() { std::fs::remove_dir(&path) } else { std::fs::remove_file(&path) };
            ok(done.is_ok())
        }
        "mkdirs" => ok(std::fs::create_dir_all(&path).is_ok()),
        "list" => {
            // With what each one is, so reading a folder of thousands is one request and not thousands.
            let entries: Vec<Value> = std::fs::read_dir(&path)
                .map(|dir| {
                    dir.flatten()
                        .filter_map(|e| {
                            let name = e.file_name().into_string().ok()?;
                            let meta = std::fs::metadata(e.path()).ok()?;
                            Some(json!({ "name": name, "folder": meta.is_dir(), "size": meta.len(), "modified": millis(&meta) }))
                        })
                        .collect()
                })
                .unwrap_or_default();
            json!({ "ok": true, "entries": entries })
        }
        _ => return None,
    })
}

pub async fn handle(State(app): State<App>, body: Bytes) -> Response {
    let Ok(request) = serde_json::from_slice::<Value>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let roots = app.roots.clone();
    let result = tokio::task::spawn_blocking(move || answer(&roots, &request)).await;
    match result {
        Ok(Some(answer)) => ([(header::CONTENT_TYPE, "application/json")], answer.to_string()).into_response(),
        Ok(None) => StatusCode::FORBIDDEN.into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

/// `/api/raw/{path}`: a file as it is, with `Range`, for an `<audio>` or a picture: the music, or a cover
/// the scan wrote. It reads what `/api/file` reads, so the same folders are all it can reach.
pub async fn raw(State(app): State<App>, Path_(path): Path_<String>, request: Request) -> Response {
    let roots = app.roots.clone();
    let asked = format!("/{path}");
    let real = tokio::task::spawn_blocking(move || roots.readable(&asked)).await.ok().flatten();
    let Some(real) = real.filter(|p| p.is_file()) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    match ServeFile::new(real).oneshot(request).await {
        Ok(answer) => answer.into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rustify-files-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn nothing_outside_the_two_folders_can_be_named() {
        let root = Path::new("/data/web");
        assert_eq!(confine(root, "/files/prefs/a.json"), Some(root.join("files").join("prefs").join("a.json")));
        assert_eq!(confine(root, "/cache"), Some(root.join("cache")));
        for bad in ["/files/../../etc/passwd", "/etc/passwd", "files/a", "/", "", "/cache/../files/a", "/other/a"] {
            assert_eq!(confine(root, bad), None, "{bad}");
        }
    }

    #[test]
    fn a_file_is_written_read_listed_renamed_and_deleted() {
        let root = temp("cycle");
        let roots = Roots { web: root.clone(), music: vec![] };
        let ask = |request: Value| answer(&roots, &request).unwrap();
        let hello = ENGINE.encode("hello");

        assert_eq!(ask(json!({"op":"stat","path":"/files/a/b.txt"}))["exists"], Value::Null);
        assert_eq!(ask(json!({"op":"write","path":"/files/a/b.txt","data":hello}))["ok"], true);
        assert_eq!(ask(json!({"op":"append","path":"/files/a/b.txt","data":ENGINE.encode(" you")}))["ok"], true);
        assert_eq!(ask(json!({"op":"read","path":"/files/a/b.txt"}))["data"], ENGINE.encode("hello you"));
        assert_eq!(ask(json!({"op":"read","path":"/files/a/b.txt","length":2}))["data"], ENGINE.encode("he"));
        assert_eq!(ask(json!({"op":"stat","path":"/files/a/b.txt"}))["size"], 9);
        assert_eq!(ask(json!({"op":"stat","path":"/files/a"}))["folder"], true);
        assert_eq!(ask(json!({"op":"list","path":"/files/a"}))["entries"][0]["name"], "b.txt");
        assert_eq!(ask(json!({"op":"rename","path":"/files/a/b.txt","target":"/cache/c.txt"}))["ok"], false, "/cache does not exist yet");
        assert_eq!(ask(json!({"op":"mkdirs","path":"/cache"}))["ok"], true);
        assert_eq!(ask(json!({"op":"rename","path":"/files/a/b.txt","target":"/cache/c.txt"}))["ok"], true);
        assert_eq!(ask(json!({"op":"read","path":"/files/a/b.txt"}))["ok"], false);
        assert_eq!(ask(json!({"op":"delete","path":"/cache/c.txt"}))["ok"], true);
        assert_eq!(answer(&roots, &json!({"op":"read","path":"/../secret"})), None);
        assert_eq!(answer(&roots, &json!({"op":"rename","path":"/files/x","target":"/../secret"})), None);
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn the_music_is_read_and_listed_and_nothing_else_of_it_is_possible() {
        let web = temp("music-web");
        let music = temp("music-own");
        std::fs::create_dir_all(music.join("Björk")).unwrap();
        std::fs::write(music.join("Björk").join("a b.flac"), "tone").unwrap();
        let outside = temp("music-outside");
        std::fs::write(outside.join("secret"), "no").unwrap();
        let roots = Roots { web, music: vec![music.clone()] };
        let ask = |request: Value| answer(&roots, &request);

        assert_eq!(ask(json!({"op":"list","path":"/music"})).unwrap()["entries"][0]["name"], "0");
        let inside = ask(json!({"op":"list","path":"/music/0"})).unwrap();
        assert_eq!(inside["entries"][0]["name"], "Björk");
        assert_eq!(inside["entries"][0]["folder"], true);
        assert_eq!(ask(json!({"op":"read","path":"/music/0/Björk/a b.flac"})).unwrap()["data"], ENGINE.encode("tone"));

        for op in ["write", "append", "delete", "mkdirs", "rename"] {
            assert_eq!(ask(json!({"op":op,"path":"/music/0/Björk/a b.flac","data":"","target":"/files/x"})), None, "{op}");
        }
        for bad in ["/music/1/x", "/music/0/../../secret", "/music/x/y", "/music/0/Björk/../../../secret"] {
            assert_eq!(ask(json!({"op":"read","path":bad})), None, "{bad}");
        }
        assert!(roots.readable("/music/0/Björk/a b.flac").is_some());
        for dir in [&music, &outside, &roots.web] {
            std::fs::remove_dir_all(dir).unwrap();
        }
    }
}
