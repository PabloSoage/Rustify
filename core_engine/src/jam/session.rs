// core_engine/src/jam/session.rs
//
// The one Jam a phone is in, over MQTT (see `mod.rs` for the protocol and why the broker sees
// nothing). One session per process; joining another room leaves the first.
//
// The event loop runs as a task on the core's runtime and hands what it hears to a queue the app
// drains with `poll`. No lock here is ever held across an `.await`: the queue and the participant
// list are behind `std::sync::Mutex`, touched only between awaits.

use super::{Command, CommandFrom, Event, Presence, Room, State};
use rumqttc::{
    AsyncClient, EventLoop, LastWill, MqttOptions, Packet, QoS, TlsConfiguration, Transport,
};
use serde::Deserialize;
use std::collections::{BTreeMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::Notify;
use tokio::task::JoinHandle;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const KEEP_ALIVE: Duration = Duration::from_secs(30);
/// Retried this often while the broker cannot be reached; rumqttc reconnects on the next poll.
const RETRY: Duration = Duration::from_secs(3);
const MAX_EVENTS: usize = 256;
/// rumqttc's default is 10 KiB each way, and a fifty-song queue as JSON is several times that: the
/// host's publish broke its own connection and the guest would have refused the state anyway.
const MAX_PACKET: usize = 1024 * 1024;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub broker_host: String,
    pub broker_port: u16,
    #[serde(default = "yes")]
    pub tls: bool,
    /// As typed; normalised here.
    pub code: String,
    /// This participant, stable for the session.
    pub id: String,
    pub name: String,
    pub host: bool,
}

fn yes() -> bool {
    true
}

struct Live {
    client: AsyncClient,
    room: Room,
    me: Presence,
    events: Mutex<VecDeque<Event>>,
    notify: Notify,
    participants: Mutex<BTreeMap<String, Presence>>,
    task: Mutex<Option<JoinHandle<()>>>,
    last_error: Mutex<String>,
}

impl Live {
    fn push(&self, event: Event) {
        let mut q = self.events.lock().unwrap();
        if q.len() >= MAX_EVENTS {
            q.pop_front();
        }
        q.push_back(event);
        drop(q);
        self.notify.notify_one();
    }

    fn topic(&self, leaf: &str) -> String {
        format!("{}/{leaf}", self.room.topic)
    }

    async fn publish(&self, leaf: &str, retain: bool, plain: &[u8]) -> Result<(), String> {
        let payload = if plain.is_empty() {
            Vec::new()
        } else {
            self.room.seal(plain)
        };
        self.client
            .publish(self.topic(leaf), QoS::AtLeastOnce, retain, payload)
            .await
            .map_err(|e| e.to_string())
    }

    fn on_publish(&self, topic: &str, payload: &[u8]) {
        let Some(leaf) = topic.strip_prefix(&format!("{}/", self.room.topic)) else {
            return;
        };
        if let Some(id) = leaf.strip_prefix("presence/") {
            let mut all = self.participants.lock().unwrap();
            match self
                .room
                .open(payload)
                .and_then(|p| serde_json::from_slice::<Presence>(&p).ok())
            {
                Some(p) if p.id == id => {
                    all.insert(id.to_string(), p);
                }
                // An empty retained message (a leave, or the broker's last will) clears it.
                _ if payload.is_empty() => {
                    all.remove(id);
                }
                _ => return,
            }
            let participants = all.values().cloned().collect();
            drop(all);
            self.push(Event::Presence { participants });
            return;
        }
        if payload.is_empty() {
            return;
        }
        let Some(plain) = self.room.open(payload) else {
            return;
        };
        match leaf {
            "state" if !self.me.host => {
                if let Ok(state) = serde_json::from_slice::<State>(&plain) {
                    self.push(Event::State { state });
                }
            }
            "cmd" if self.me.host => {
                if let Ok(command) = serde_json::from_slice::<CommandFrom>(&plain) {
                    self.push(Event::Command { command });
                }
            }
            l if l == format!("kick/{}", self.me.id) => self.push(Event::Kicked),
            _ => {}
        }
    }
}

static SESSION: Mutex<Option<Arc<Live>>> = Mutex::new(None);

fn current() -> Option<Arc<Live>> {
    SESSION.lock().unwrap().clone()
}

fn tls_config() -> rustls::ClientConfig {
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .expect("ring supports the default versions")
        .with_root_certificates(roots)
        .with_no_client_auth()
}

/// Joins (or, as host, opens) the room and waits until the broker has accepted the connection.
pub async fn join(cfg: Config) -> Result<(), String> {
    leave().await;
    let code = super::normalize(&cfg.code).ok_or("not a room code")?;
    let room = Room::new(&code);
    let me = Presence {
        id: cfg.id.clone(),
        name: cfg.name.clone(),
        host: cfg.host,
    };

    let mut opts = MqttOptions::new(
        format!("rustify-{}", cfg.id),
        cfg.broker_host.clone(),
        cfg.broker_port,
    );
    opts.set_keep_alive(KEEP_ALIVE);
    opts.set_clean_session(true);
    opts.set_max_packet_size(MAX_PACKET, MAX_PACKET);
    opts.set_last_will(LastWill::new(
        format!("{}/presence/{}", room.topic, cfg.id),
        Vec::<u8>::new(),
        QoS::AtLeastOnce,
        true,
    ));
    if cfg.tls {
        opts.set_transport(Transport::tls_with_config(TlsConfiguration::Rustls(
            Arc::new(tls_config()),
        )));
    }
    let (client, eventloop) = AsyncClient::new(opts, 64);

    let live = Arc::new(Live {
        client,
        room,
        me,
        events: Mutex::new(VecDeque::new()),
        notify: Notify::new(),
        participants: Mutex::new(BTreeMap::new()),
        task: Mutex::new(None),
        last_error: Mutex::new(String::new()),
    });
    let (connected_tx, connected_rx) = tokio::sync::oneshot::channel();
    let task = tokio::spawn(run(live.clone(), eventloop, connected_tx));
    *live.task.lock().unwrap() = Some(task);
    *SESSION.lock().unwrap() = Some(live.clone());

    match tokio::time::timeout(CONNECT_TIMEOUT, connected_rx).await {
        Ok(Ok(Ok(()))) => Ok(()),
        Ok(Ok(Err(e))) => {
            leave().await;
            Err(e)
        }
        _ => {
            let why = live.last_error.lock().unwrap().clone();
            leave().await;
            Err(format!(
                "no answer from {}:{} in {} s{}",
                cfg.broker_host,
                cfg.broker_port,
                CONNECT_TIMEOUT.as_secs(),
                if why.is_empty() {
                    String::new()
                } else {
                    format!(" ({why})")
                }
            ))
        }
    }
}

async fn run(
    live: Arc<Live>,
    mut eventloop: EventLoop,
    connected: tokio::sync::oneshot::Sender<Result<(), String>>,
) {
    let mut connected = Some(connected);
    loop {
        match eventloop.poll().await {
            Ok(rumqttc::Event::Incoming(Packet::ConnAck(_))) => {
                // Subscriptions and presence again on every (re)connection: the session is clean.
                let kick = format!("kick/{}", live.me.id);
                let topics = [
                    ("presence/+", true),
                    ("state", !live.me.host),
                    ("cmd", live.me.host),
                    (kick.as_str(), !live.me.host),
                ];
                for (leaf, wanted) in topics {
                    if wanted {
                        let _ = live
                            .client
                            .subscribe(live.topic(leaf), QoS::AtLeastOnce)
                            .await;
                    }
                }
                let me = serde_json::to_vec(&live.me).unwrap_or_default();
                let _ = live
                    .publish(&format!("presence/{}", live.me.id), true, &me)
                    .await;
                live.push(Event::Connected);
                if let Some(tx) = connected.take() {
                    let _ = tx.send(Ok(()));
                }
            }
            Ok(rumqttc::Event::Incoming(Packet::Publish(p))) => {
                live.on_publish(&p.topic, &p.payload)
            }
            Ok(_) => {}
            Err(e) => {
                let reason = e.to_string();
                if connected.is_some() {
                    // Not connected yet: keep trying until `join` gives up at CONNECT_TIMEOUT, and
                    // let it say why. A first TLS handshake to a public broker does time out now
                    // and then while the next attempt goes through.
                    *live.last_error.lock().unwrap() = reason;
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    continue;
                }
                live.push(Event::Disconnected { reason });
                tokio::time::sleep(RETRY).await;
            }
        }
    }
}

/// What the room said since the last call; waits up to `timeout_ms` when nothing is waiting.
pub async fn poll(timeout_ms: u64) -> Vec<Event> {
    let Some(live) = current() else {
        return Vec::new();
    };
    let ready = !live.events.lock().unwrap().is_empty();
    if !ready {
        let _ =
            tokio::time::timeout(Duration::from_millis(timeout_ms), live.notify.notified()).await;
    }
    let events: Vec<Event> = live.events.lock().unwrap().drain(..).collect();
    events
}

/// Host: the queue and position, retained for whoever joins later.
pub async fn publish_state(state: &State) -> Result<(), String> {
    let live = current().ok_or("not in a jam")?;
    if !live.me.host {
        return Err("only the host sets the state".into());
    }
    live.publish(
        "state",
        true,
        &serde_json::to_vec(state).map_err(|e| e.to_string())?,
    )
    .await
}

/// Guest: asks the host for something.
pub async fn send(command: Command) -> Result<(), String> {
    let live = current().ok_or("not in a jam")?;
    let from = CommandFrom {
        from: live.me.id.clone(),
        name: live.me.name.clone(),
        command,
    };
    live.publish(
        "cmd",
        false,
        &serde_json::to_vec(&from).map_err(|e| e.to_string())?,
    )
    .await
}

/// Host: sends a guest away and clears their presence.
pub async fn kick(id: &str) -> Result<(), String> {
    let live = current().ok_or("not in a jam")?;
    if !live.me.host {
        return Err("only the host can remove someone".into());
    }
    live.publish(&format!("kick/{id}"), false, b"{}").await?;
    live.publish(&format!("presence/{id}"), true, b"").await
}

/// Leaves cleanly: presence cleared, and the room's state too when the host leaves.
pub async fn leave() {
    let Some(live) = SESSION.lock().unwrap().take() else {
        return;
    };
    let _ = live
        .publish(&format!("presence/{}", live.me.id), true, b"")
        .await;
    if live.me.host {
        let _ = live.publish("state", true, b"").await;
    }
    let _ = live.client.disconnect().await;
    // Give the disconnect a moment to go out before the loop that sends it stops.
    tokio::time::sleep(Duration::from_millis(200)).await;
    let task = live.task.lock().unwrap().take();
    if let Some(task) = task {
        task.abort();
    }
}
