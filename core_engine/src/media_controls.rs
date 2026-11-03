// core_engine/src/media_controls.rs
//
// The operating system's media controls for the desktop app (docs 25, T2.2 and T3.1): the
// Windows overlay and media keys (SMTC), MPRIS on Linux (GNOME's and KDE's players, `playerctl`,
// headset buttons) and Now Playing on macOS. Android has its MediaSession and does not use this.
//
// One crate for the three, `souvlaki`, behind the `media-controls` feature. It lives on a thread of
// its own that creates the controls, applies updates and forwards what the system asks for: the
// controls are not `Send` on every platform, and keeping them on one thread makes that not matter.
// The app reads the requests with `poll`, the same shape as the audio engine's events.

use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Mutex;
use std::thread::JoinHandle;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use souvlaki::{
    MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig,
    SeekDirection,
};

/// What the app tells the system. Deserialised from `{"type":"metadata",…}`.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Update {
    Metadata {
        title: String,
        #[serde(default)]
        artist: String,
        #[serde(default)]
        album: String,
        #[serde(default)]
        cover_url: Option<String>,
        #[serde(default)]
        duration_ms: Option<u64>,
    },
    Playback {
        playing: bool,
        #[serde(default)]
        position_ms: Option<u64>,
    },
    Stopped,
}

/// What the system asks the app for. Serialised as `{"type":"next"}` and so on.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Request {
    Play,
    Pause,
    Toggle,
    Next,
    Previous,
    Stop,
    /// Relative, in milliseconds; negative is backwards.
    SeekBy { offset_ms: i64 },
    SetPosition { position_ms: u64 },
    /// Bring the window to the front (clicking the overlay, `playerctl` "raise").
    Raise,
    Quit,
}

/// How far a bare "seek forward" goes when the system does not say.
const DEFAULT_SEEK_MS: i64 = 10_000;

/// Turns the crate's event into ours; `None` for what the app has no use for (open a URI, volume).
pub fn request_of(event: MediaControlEvent) -> Option<Request> {
    let signed = |direction: SeekDirection, ms: i64| match direction {
        SeekDirection::Forward => ms,
        SeekDirection::Backward => -ms,
    };
    Some(match event {
        MediaControlEvent::Play => Request::Play,
        MediaControlEvent::Pause => Request::Pause,
        MediaControlEvent::Toggle => Request::Toggle,
        MediaControlEvent::Next => Request::Next,
        MediaControlEvent::Previous => Request::Previous,
        MediaControlEvent::Stop => Request::Stop,
        MediaControlEvent::Seek(direction) => Request::SeekBy {
            offset_ms: signed(direction, DEFAULT_SEEK_MS),
        },
        MediaControlEvent::SeekBy(direction, by) => Request::SeekBy {
            offset_ms: signed(direction, by.as_millis() as i64),
        },
        MediaControlEvent::SetPosition(MediaPosition(at)) => Request::SetPosition {
            position_ms: at.as_millis() as u64,
        },
        MediaControlEvent::Raise => Request::Raise,
        MediaControlEvent::Quit => Request::Quit,
        _ => return None,
    })
}

enum Msg {
    Update(Update),
    Shutdown,
}

/// The running controls: one thread, one system registration.
pub struct Controls {
    tx: Sender<Msg>,
    requests: Mutex<Receiver<Request>>,
    thread: Option<JoinHandle<()>>,
}

impl Controls {
    /// Registers with the system as `display_name`. `hwnd` is the window handle Windows needs to
    /// attach the controls to (`ComposeWindow.windowHandle`); other systems ignore it.
    pub fn start(display_name: &str, hwnd: Option<usize>) -> Result<Self, String> {
        let (tx, rx) = mpsc::channel::<Msg>();
        let (requests_tx, requests_rx) = mpsc::channel::<Request>();
        let (ready_tx, ready_rx) = mpsc::channel::<Result<(), String>>();
        let name = display_name.to_string();
        let thread = std::thread::Builder::new()
            .name("rustify-media-controls".into())
            .spawn(move || run(name, hwnd, rx, requests_tx, ready_tx))
            .map_err(|e| e.to_string())?;
        ready_rx
            .recv_timeout(Duration::from_secs(5))
            .map_err(|_| "the media controls did not start in 5 s".to_string())??;
        Ok(Controls {
            tx,
            requests: Mutex::new(requests_rx),
            thread: Some(thread),
        })
    }

    /// Queues an update; never waits.
    pub fn update(&self, update: Update) -> bool {
        self.tx.send(Msg::Update(update)).is_ok()
    }

    /// The next request from the system, waiting up to `timeout`.
    pub fn poll(&self, timeout: Duration) -> Option<Request> {
        self.requests.lock().ok()?.recv_timeout(timeout).ok()
    }
}

impl Drop for Controls {
    fn drop(&mut self) {
        let _ = self.tx.send(Msg::Shutdown);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn run(
    name: String,
    hwnd: Option<usize>,
    rx: Receiver<Msg>,
    requests: Sender<Request>,
    ready: Sender<Result<(), String>>,
) {
    let config = PlatformConfig {
        display_name: &name,
        // MPRIS bus name: org.mpris.MediaPlayer2.rustify.
        dbus_name: "rustify",
        hwnd: hwnd.map(|h| h as *mut std::ffi::c_void),
    };
    let mut controls = match MediaControls::new(config) {
        Ok(controls) => controls,
        Err(e) => {
            let _ = ready.send(Err(format!("media controls unavailable: {e:?}")));
            return;
        }
    };
    if let Err(e) = controls.attach(move |event| {
        if let Some(request) = request_of(event) {
            let _ = requests.send(request);
        }
    }) {
        let _ = ready.send(Err(format!("media controls refused the handler: {e:?}")));
        return;
    }
    let _ = ready.send(Ok(()));

    while let Ok(Msg::Update(update)) = rx.recv() {
        let applied = match update {
            Update::Metadata {
                title,
                artist,
                album,
                cover_url,
                duration_ms,
            } => controls.set_metadata(MediaMetadata {
                title: Some(title.as_str()),
                artist: Some(artist.as_str()),
                album: Some(album.as_str()),
                cover_url: cover_url.as_deref(),
                duration: duration_ms.map(Duration::from_millis),
            }),
            Update::Playback {
                playing,
                position_ms,
            } => {
                let progress = position_ms.map(|ms| MediaPosition(Duration::from_millis(ms)));
                controls.set_playback(if playing {
                    MediaPlayback::Playing { progress }
                } else {
                    MediaPlayback::Paused { progress }
                })
            }
            Update::Stopped => controls.set_playback(MediaPlayback::Stopped),
        };
        if let Err(e) = applied {
            <crate::env::android::AndroidEnv as crate::env::Env>::log(
                crate::env::LogLevel::Warn,
                "MediaControls",
                &format!("update refused: {e:?}"),
            );
        }
    }
    let _ = controls.detach();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn updates_deserialise_from_the_shape_the_app_sends() {
        let meta: Update = serde_json::from_str(
            r#"{"type":"metadata","title":"Bohemian Rhapsody","artist":"Queen","album":"A Night at the Opera","coverUrl":"https://i/x.jpg","durationMs":354000}"#,
        )
        .unwrap();
        assert_eq!(
            meta,
            Update::Metadata {
                title: "Bohemian Rhapsody".into(),
                artist: "Queen".into(),
                album: "A Night at the Opera".into(),
                cover_url: Some("https://i/x.jpg".into()),
                duration_ms: Some(354_000),
            }
        );
        let playback: Update =
            serde_json::from_str(r#"{"type":"playback","playing":false,"positionMs":1200}"#).unwrap();
        assert_eq!(
            playback,
            Update::Playback {
                playing: false,
                position_ms: Some(1200)
            }
        );
        assert_eq!(
            serde_json::from_str::<Update>(r#"{"type":"stopped"}"#).unwrap(),
            Update::Stopped
        );
    }

    #[test]
    fn system_events_become_requests() {
        assert_eq!(request_of(MediaControlEvent::Toggle), Some(Request::Toggle));
        assert_eq!(
            request_of(MediaControlEvent::SeekBy(
                SeekDirection::Backward,
                Duration::from_secs(5)
            )),
            Some(Request::SeekBy { offset_ms: -5000 })
        );
        assert_eq!(
            request_of(MediaControlEvent::Seek(SeekDirection::Forward)),
            Some(Request::SeekBy { offset_ms: 10_000 })
        );
        assert_eq!(
            request_of(MediaControlEvent::SetPosition(MediaPosition(Duration::from_millis(
                42_000
            )))),
            Some(Request::SetPosition { position_ms: 42_000 })
        );
    }

    #[test]
    fn requests_serialise_in_the_shape_the_app_reads() {
        assert_eq!(serde_json::to_string(&Request::Next).unwrap(), r#"{"type":"next"}"#);
        assert_eq!(
            serde_json::to_string(&Request::SeekBy { offset_ms: -5000 }).unwrap(),
            r#"{"type":"seekBy","offsetMs":-5000}"#
        );
    }
}
