// server/src/files.rs
//
// `POST /api/file`: the app's own files, for the interface in the browser, which has no disk (`AppFile`
// on the web). One folder of the server's, with `/files` and `/cache` under it, as the other platforms
// have `filesDir` and `cacheDir`, and nothing outside it can be named: a path is taken apart before it is
// used. Settings, playlists and the like; the music is not here.

use std::path::{Component, Path, PathBuf};

use axum::body::Bytes;
use axum::extract::State;
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use base64::Engine;
use serde_json::{json, Value};

use crate::App;

const ENGINE: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

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
pub fn answer(root: &Path, request: &Value) -> Option<Value> {
    let op = request.get("op")?.as_str()?;
    let path = confine(root, request.get("path")?.as_str()?)?;
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
            let target = confine(root, request.get("target")?.as_str()?)?;
            ok(std::fs::rename(&path, &target).is_ok())
        }
        "delete" => {
            let done = if path.is_dir() { std::fs::remove_dir(&path) } else { std::fs::remove_file(&path) };
            ok(done.is_ok())
        }
        "mkdirs" => ok(std::fs::create_dir_all(&path).is_ok()),
        "list" => {
            let children: Vec<String> = std::fs::read_dir(&path)
                .map(|dir| dir.flatten().filter_map(|e| e.file_name().into_string().ok()).collect())
                .unwrap_or_default();
            json!({ "ok": true, "children": children })
        }
        _ => return None,
    })
}

pub async fn handle(State(app): State<App>, body: Bytes) -> Response {
    let Ok(request) = serde_json::from_slice::<Value>(&body) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let root = app.web_files.clone();
    let result = tokio::task::spawn_blocking(move || answer(&root, &request)).await;
    match result {
        Ok(Some(answer)) => ([(header::CONTENT_TYPE, "application/json")], answer.to_string()).into_response(),
        Ok(None) => StatusCode::FORBIDDEN.into_response(),
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
        let ask = |request: Value| answer(&root, &request).unwrap();
        let hello = ENGINE.encode("hello");

        assert_eq!(ask(json!({"op":"stat","path":"/files/a/b.txt"}))["exists"], Value::Null);
        assert_eq!(ask(json!({"op":"write","path":"/files/a/b.txt","data":hello}))["ok"], true);
        assert_eq!(ask(json!({"op":"append","path":"/files/a/b.txt","data":ENGINE.encode(" you")}))["ok"], true);
        assert_eq!(ask(json!({"op":"read","path":"/files/a/b.txt"}))["data"], ENGINE.encode("hello you"));
        assert_eq!(ask(json!({"op":"read","path":"/files/a/b.txt","length":2}))["data"], ENGINE.encode("he"));
        assert_eq!(ask(json!({"op":"stat","path":"/files/a/b.txt"}))["size"], 9);
        assert_eq!(ask(json!({"op":"stat","path":"/files/a"}))["folder"], true);
        assert_eq!(ask(json!({"op":"list","path":"/files/a"}))["children"], json!(["b.txt"]));
        assert_eq!(ask(json!({"op":"rename","path":"/files/a/b.txt","target":"/cache/c.txt"}))["ok"], false, "/cache does not exist yet");
        assert_eq!(ask(json!({"op":"mkdirs","path":"/cache"}))["ok"], true);
        assert_eq!(ask(json!({"op":"rename","path":"/files/a/b.txt","target":"/cache/c.txt"}))["ok"], true);
        assert_eq!(ask(json!({"op":"read","path":"/files/a/b.txt"}))["ok"], false);
        assert_eq!(ask(json!({"op":"delete","path":"/cache/c.txt"}))["ok"], true);
        assert_eq!(answer(&root, &json!({"op":"read","path":"/../secret"})), None);
        assert_eq!(answer(&root, &json!({"op":"rename","path":"/files/x","target":"/../secret"})), None);
        std::fs::remove_dir_all(&root).unwrap();
    }
}
