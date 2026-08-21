// core_engine/src/server/lan.rs
//
// Opening the local server to the LAN, for casting — E16.
//
// This is the file that reopens a decision E11 closed, so it says out loud what it is doing.
//
// The old local server had a `0.0.0.0` fallback. That put `/resolve` on the wifi, it was found, and
// the server was deleted rather than fixed. The rule that replaced it — *bind to 127.0.0.1 and only
// 127.0.0.1* — is written in `server/mod.rs` and is the reason the current one is safe.
//
// Casting cannot live under that rule: a Chromecast is on the LAN and cannot reach a phone's
// loopback. So this module is the exception, and it is built so that **no single failure is enough**:
//
//   1. **Nothing is open by default.** This module does nothing until `open` is called, and `open`
//      is only called by a cast session the user started.
//   2. **Never `0.0.0.0`.** The listener binds to one interface address, handed in by the caller.
//      `0.0.0.0` would also cover the VPN interface, USB tethering, and any interface that appears
//      later; one address is a whitelist, `0.0.0.0` is an empty blacklist.
//   3. **Only the chosen device is answered.** Every accepted connection's peer address is checked
//      against the device the user picked, before the token, before the path, before anything is
//      read from the socket. This is the layer that makes the rest defensible: without it, being on
//      the same wifi plus the token is the whole barrier.
//   4. **It closes.** When the session ends, when the network changes, when the app stops.
//
// The token from the loopback server still applies on top of all of this — it is the same server,
// with the same routing.
//
// ## What this does not defend against
//
// Someone who is *on the path* — the router, or an attacker who can spoof a source address on an
// established TCP connection. Against that, the answer is that a single track is downloadable, not
// the library and not an account. Stated rather than glossed.

use std::net::{IpAddr, SocketAddr};
use std::sync::{Mutex, OnceLock};

use hyper::service::service_fn;
use hyper_util::rt::TokioIo;
use tokio::net::TcpListener;

use super::route;
use crate::env::{Env, EnvError, LogLevel};

/// The address allowed to talk to the LAN listener, and the port it is on.
///
/// One device at a time on purpose: "cast to two speakers" is a feature nobody asked for, and each
/// extra address is another thing that can be wrong.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Session {
    /// The interface address the listener is bound to — never `0.0.0.0`.
    pub bound_to: IpAddr,
    pub port: u16,
    /// The only peer that gets an answer.
    pub device: IpAddr,
}

static SESSION: OnceLock<Mutex<Option<Session>>> = OnceLock::new();

fn slot() -> &'static Mutex<Option<Session>> {
    SESSION.get_or_init(|| Mutex::new(None))
}

/// The active cast session, if any.
pub fn current() -> Option<Session> {
    slot().lock().ok().and_then(|guard| guard.clone())
}

/// Is `peer` the device this session is for?
///
/// `false` when there is no session at all, which is the answer that matters: a listener whose
/// session was cleared answers nobody, rather than everybody.
pub fn peer_is_allowed(peer: IpAddr) -> bool {
    current().is_some_and(|session| session.device == peer)
}

/// Opens the server to `bind_to`, answering only `device`.
///
/// Returns the port, which is the same one the loopback listener uses — the URLs already handed out
/// stay valid, which is what makes starting a cast mid-track possible.
///
/// Refuses to bind to an unspecified address. That is not a validation nicety: `0.0.0.0` is the
/// exact mistake E11 was about, and the check exists so that a caller passing it by accident gets an
/// error rather than a wifi-wide server.
pub async fn open<E: Env>(bind_to: IpAddr, device: IpAddr) -> Result<u16, EnvError> {
    if bind_to.is_unspecified() {
        return Err(EnvError::Other(
            "refusing to bind the cast listener to an unspecified address: \
             pass the interface address, never 0.0.0.0"
                .into(),
        ));
    }
    if bind_to.is_loopback() {
        return Err(EnvError::Other(
            "the loopback listener already exists; the cast listener is for a real interface".into(),
        ));
    }
    if device.is_unspecified() || device.is_loopback() {
        return Err(EnvError::Other(
            "a cast device has to be a real address on the network".into(),
        ));
    }

    // Reopening for the same device is a no-op; for a different one, the old session stops being
    // answered the moment the slot is replaced, even before its listener notices.
    if let Some(existing) = current() {
        if existing.bound_to == bind_to && existing.device == device {
            return Ok(existing.port);
        }
    }

    let server = super::current().ok_or_else(|| {
        EnvError::Other("the local server is not running, so there is nothing to open".into())
    })?;

    let listener = TcpListener::bind(SocketAddr::new(bind_to, server.port))
        .await
        .map_err(|e| EnvError::Other(format!("could not bind {bind_to}:{}: {e}", server.port)))?;

    let port = server.port;
    if let Ok(mut guard) = slot().lock() {
        *guard = Some(Session {
            bound_to: bind_to,
            port,
            device,
        });
    }

    let token = server.token.clone();
    E::exec_concurrent(async move {
        loop {
            let (stream, peer) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(e) => {
                    E::log(LogLevel::Warn, "CastServer", &format!("accept failed: {e}"));
                    // A listener whose session has been closed has nothing left to do; ending the
                    // loop is what drops it and frees the port.
                    if current().is_none() {
                        return;
                    }
                    continue;
                }
            };

            // Layer 3, and it happens here rather than in the router on purpose: an unauthorised
            // peer is dropped before a single byte of its request is read, so there is nothing to
            // parse, nothing to log, and no path for a malformed request to reach.
            if !peer_is_allowed(peer.ip()) {
                E::log(
                    LogLevel::Warn,
                    "CastServer",
                    &format!("refused {} — not the device being cast to", peer.ip()),
                );
                drop(stream);
                if current().is_none() {
                    return;
                }
                continue;
            }

            let token = token.clone();
            E::exec_concurrent(async move {
                let io = TokioIo::new(stream);
                let service = service_fn(move |req| {
                    let token = token.clone();
                    async move { route::<E>(req, token).await }
                });
                if let Err(e) = hyper::server::conn::http1::Builder::new()
                    .serve_connection(io, service)
                    .await
                {
                    E::log(
                        LogLevel::Debug,
                        "CastServer",
                        &format!("connection ended: {e}"),
                    );
                }
            });
        }
    });

    E::log(
        LogLevel::Info,
        "CastServer",
        &format!("open on {bind_to}:{port}, answering only {device}"),
    );
    Ok(port)
}

/// Ends the session.
///
/// Clearing the slot is what actually stops anyone being answered — [`peer_is_allowed`] returns
/// `false` immediately, so even a connection accepted a microsecond earlier gets nothing. The
/// listener itself goes when its loop next notices; the order matters and it is this way round.
pub fn close<E: Env>() {
    let had = current().is_some();
    if let Ok(mut guard) = slot().lock() {
        *guard = None;
    }
    if had {
        E::log(LogLevel::Info, "CastServer", "cast session closed");
    }
}

/// The URL to hand to the cast device for a registered handle.
///
/// Deliberately built here rather than by string-formatting on the Kotlin side: it is the same
/// `handle` and the same token as the loopback URL, and the only thing that changes is the host.
pub fn url_for(handle: &str) -> Option<String> {
    let session = current()?;
    let server = super::current()?;
    Some(format!(
        "http://{}:{}/stream/{}?t={}",
        session.bound_to, session.port, handle, server.token
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn ip(a: u8, b: u8, c: u8, d: u8) -> IpAddr {
        IpAddr::V4(Ipv4Addr::new(a, b, c, d))
    }

    fn with_session(bound: IpAddr, device: IpAddr) {
        *slot().lock().unwrap() = Some(Session {
            bound_to: bound,
            port: 1234,
            device,
        });
    }

    fn clear() {
        *slot().lock().unwrap() = None;
    }

    #[test]
    fn only_the_device_being_cast_to_is_allowed() {
        with_session(ip(192, 168, 1, 34), ip(192, 168, 1, 90));
        assert!(peer_is_allowed(ip(192, 168, 1, 90)));
        // The neighbour on the same wifi. This is the whole point of layer 3.
        assert!(!peer_is_allowed(ip(192, 168, 1, 91)));
        // And the router.
        assert!(!peer_is_allowed(ip(192, 168, 1, 1)));
        clear();
    }

    #[test]
    fn with_no_session_nobody_is_allowed() {
        // The failure mode that matters: a cleared session must answer *nobody*, not everybody.
        clear();
        assert!(!peer_is_allowed(ip(192, 168, 1, 90)));
        assert!(!peer_is_allowed(ip(127, 0, 0, 1)));
    }

    #[test]
    fn closing_stops_answering_before_the_listener_even_notices() {
        with_session(ip(192, 168, 1, 34), ip(192, 168, 1, 90));
        assert!(peer_is_allowed(ip(192, 168, 1, 90)));
        *slot().lock().unwrap() = None;
        assert!(!peer_is_allowed(ip(192, 168, 1, 90)));
    }

    #[tokio::test]
    async fn binding_to_an_unspecified_address_is_refused() {
        clear();
        // E11 in one assertion. If this ever passes, the server is on the whole wifi.
        let err = open::<crate::env::mock::MockEnv>(
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            ip(192, 168, 1, 90),
        )
        .await;
        assert!(err.is_err());
        assert!(current().is_none(), "a refused open must not leave a session");
    }

    #[tokio::test]
    async fn a_loopback_device_is_refused() {
        clear();
        assert!(
            open::<crate::env::mock::MockEnv>(ip(192, 168, 1, 34), ip(127, 0, 0, 1))
                .await
                .is_err()
        );
        assert!(current().is_none());
    }

    #[test]
    fn a_url_is_only_produced_while_a_session_is_open() {
        clear();
        assert!(url_for("abc").is_none());
    }
}
