// core_engine/src/addon/security.rs
//
// The limits on what an installed addon may point us at.
//
// This file exists before the transport that uses it, on purpose. Installing a backend by URL opens
// the app to code written by someone else; the checks are part of the feature, not a hardening pass
// to be done later, and every one of them is here because of a specific way this could go wrong.
//
// **DNS rebinding, and how it is closed (3.3).** Until 3.3 the note here said this was a real hole:
// a hostname that passes the checks below can still *resolve* to a private address, because the
// check happens on the name and the connection happens after a lookup nobody controlled. The answer
// is resolve-then-pin — look the name up ourselves, refuse the addresses we do not like, and then
// connect to **exactly** the address we approved rather than to whatever a second lookup returns.
// [`check_addresses`] is the refusing half; `HttpRequest::pin_to` is the pinning half.
//
// What that leaves: an attacker who controls the DNS answer can still choose *which public address*
// we reach. That is not the hole — it is what a domain name means.

use super::AddonError;
use std::net::{IpAddr, SocketAddr};
use url::{Host, Url};

/// Whether `http://` to a loopback address is tolerated. Debug builds only: it exists so an addon
/// can be developed against a laptop, and it is exactly the exception an attacker would want.
pub const ALLOW_INSECURE_LOOPBACK: bool = cfg!(debug_assertions);

/// The manifest and endpoint base of an addon the user is installing.
pub fn validate_addon_url(raw: &str) -> Result<Url, AddonError> {
    let url = Url::parse(raw).map_err(|e| AddonError::InvalidUrl(e.to_string()))?;
    check_scheme(&url)?;
    check_no_credentials(&url)?;
    // `allow_loopback: true` here and only here: this is the addon's own base url, typed by the
    // user in an install dialog, and pointing it at a laptop is how an addon gets developed.
    check_host(&url, true)?;
    Ok(url)
}

/// A URL an addon handed back for us to play.
///
/// Stricter than the addon's own URL in one way that matters: **never** loopback, not even in a
/// debug build. A stream URL pointing at `127.0.0.1` would aim the player at this device's own
/// services — including the local streaming server two modules over.
pub fn validate_stream_url(raw: &str) -> Result<Url, AddonError> {
    let url = Url::parse(raw).map_err(|e| AddonError::InvalidUrl(e.to_string()))?;
    if url.scheme() != "https" {
        return Err(AddonError::Refused(format!(
            "a stream url must be https, got {}",
            url.scheme()
        )));
    }
    check_no_credentials(&url)?;
    // `allow_loopback: false`, and this is the whole point of the parameter existing.
    //
    // The loopback exception is for *installing* an addon against a laptop while developing it. It
    // must not extend to the URLs that addon then hands back: a stream url is a place this app will
    // go and fetch from, so allowing loopback there lets third-party code aim us at whatever is
    // listening on the phone — starting with our own local server, which holds a token and serves
    // audio. That is the request-forgery this module exists to refuse, and a debug build is exactly
    // where someone would be running an addon they do not fully trust.
    check_host(&url, false)?;
    // `file:`, `content:` and `data:` are already excluded by the https check above; this catches
    // the case where a future edit relaxes it.
    if url.cannot_be_a_base() {
        return Err(AddonError::Refused("opaque url".into()));
    }
    Ok(url)
}

fn check_scheme(url: &Url) -> Result<(), AddonError> {
    match url.scheme() {
        "https" => Ok(()),
        "http" if ALLOW_INSECURE_LOOPBACK && is_loopback(url) => Ok(()),
        other => Err(AddonError::Refused(format!(
            "an addon must be served over https, got {other}"
        ))),
    }
}

/// `https://evil.example@internal.corp/` is a URL whose *host* is `internal.corp`, which is not what
/// it looks like to a person reading it in an install dialog.
fn check_no_credentials(url: &Url) -> Result<(), AddonError> {
    if !url.username().is_empty() || url.password().is_some() {
        return Err(AddonError::Refused(
            "urls with embedded credentials are refused".into(),
        ));
    }
    Ok(())
}

fn check_host(url: &Url, allow_loopback: bool) -> Result<(), AddonError> {
    let host = url.host().ok_or_else(|| AddonError::Refused("no host".into()))?;
    let private = match &host {
        Host::Ipv4(ip) => {
            ip.is_private()
                || ip.is_loopback()
                || ip.is_link_local()
                || ip.is_broadcast()
                || ip.is_unspecified()
                || ip.is_documentation()
                // 100.64.0.0/10, carrier-grade NAT — a real address range on mobile networks.
                || (ip.octets()[0] == 100 && (64..128).contains(&ip.octets()[1]))
        }
        Host::Ipv6(ip) => {
            ip.is_loopback()
                || ip.is_unspecified()
                // fc00::/7 unique-local and fe80::/10 link-local. Written out because the
                // std helpers for these are still unstable.
                || (ip.segments()[0] & 0xfe00) == 0xfc00
                || (ip.segments()[0] & 0xffc0) == 0xfe80
        }
        Host::Domain(name) => {
            let name = name.to_ascii_lowercase();
            name == "localhost"
                || name.ends_with(".localhost")
                || name.ends_with(".local")
                || name.ends_with(".internal")
                || name.ends_with(".home.arpa")
        }
    };
    if private && !(allow_loopback && ALLOW_INSECURE_LOOPBACK && is_loopback(url)) {
        return Err(AddonError::Refused(format!(
            "refusing a private or local address: {host}"
        )));
    }
    Ok(())
}

/// Is this address one we are willing to connect to?
///
/// The same judgement [`check_host`] makes about a literal address, applied to what a name actually
/// resolved to. Separate and public because it is the half that closes DNS rebinding: the name is
/// checked once, the addresses are checked once, and then the addresses are what we connect to.
pub fn is_private_addr(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => {
            ip.is_private()
                || ip.is_loopback()
                || ip.is_link_local()
                || ip.is_broadcast()
                || ip.is_unspecified()
                || ip.is_documentation()
                || (ip.octets()[0] == 100 && (64..128).contains(&ip.octets()[1]))
        }
        IpAddr::V6(ip) => {
            ip.is_loopback()
                || ip.is_unspecified()
                || (ip.segments()[0] & 0xfe00) == 0xfc00
                || (ip.segments()[0] & 0xffc0) == 0xfe80
        }
    }
}

/// Refuses a resolution that landed anywhere private.
///
/// **All of them, not the first.** A name that resolves to one public address and one private one
/// is the attack, not a coincidence: connecting would pick whichever the client felt like. An empty
/// answer is refused too — there is nothing to pin, so falling back to an unpinned connection would
/// hand the decision straight back to the resolver.
pub fn check_addresses(host: &str, addrs: &[SocketAddr]) -> Result<(), AddonError> {
    if addrs.is_empty() {
        return Err(AddonError::Unreachable(format!("{host} did not resolve")));
    }
    for addr in addrs {
        if is_private_addr(addr.ip()) {
            return Err(AddonError::Refused(format!(
                "{host} resolves to a private address ({}); refusing to connect",
                addr.ip()
            )));
        }
    }
    Ok(())
}

fn is_loopback(url: &Url) -> bool {
    match url.host() {
        Some(Host::Ipv4(ip)) => ip.is_loopback(),
        Some(Host::Ipv6(ip)) => ip.is_loopback(),
        Some(Host::Domain(name)) => name.eq_ignore_ascii_case("localhost"),
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_ordinary_https_addon_is_accepted() {
        assert!(validate_addon_url("https://addons.example.org/rustify/").is_ok());
    }

    #[test]
    fn plain_http_on_the_public_internet_is_refused() {
        // Not a purity argument: an addon over http can be rewritten in flight by anyone on the
        // path, and what it returns is a URL the player will fetch.
        assert!(validate_addon_url("http://addons.example.org/").is_err());
    }

    #[test]
    fn private_and_local_addresses_are_refused() {
        for raw in [
            "https://10.0.0.5/",
            "https://192.168.1.1/",
            "https://172.16.4.4/",
            "https://169.254.169.254/",          // cloud metadata, the classic SSRF target
            "https://100.100.0.1/",              // carrier-grade NAT
            "https://[fd00::1]/",                // unique-local
            "https://[fe80::1]/",                // link-local
            "https://printer.local/",
            "https://intranet.internal/",
        ] {
            assert!(
                validate_addon_url(raw).is_err(),
                "should have refused {raw}"
            );
        }
    }

    #[test]
    fn embedded_credentials_are_refused() {
        // Reads as "evil.example" to a person, resolves to "internal.corp" to a URL parser.
        assert!(validate_addon_url("https://evil.example@internal.corp/").is_err());
        assert!(validate_addon_url("https://user:pass@addons.example.org/").is_err());
    }

    #[test]
    fn a_stream_url_may_never_be_loopback_even_in_debug() {
        // Stricter than the addon url on purpose: this one is handed to the player, and loopback is
        // where this device's own services live — including our streaming server.
        assert!(validate_stream_url("http://127.0.0.1:8080/a.mp3").is_err());
        assert!(validate_stream_url("https://127.0.0.1/a.mp3").is_err());
        assert!(validate_stream_url("https://localhost/a.mp3").is_err());
    }

    #[test]
    fn non_http_schemes_are_refused_for_streams() {
        for raw in [
            "file:///data/data/com.varuna.rustify/databases/x.db",
            "content://media/external/audio/media/1",
            "data:audio/mp3;base64,AAAA",
            "javascript:alert(1)",
        ] {
            assert!(
                validate_stream_url(raw).is_err(),
                "should have refused {raw}"
            );
        }
    }

    #[test]
    fn an_ordinary_https_stream_is_accepted() {
        assert!(validate_stream_url("https://cdn.example.org/track/1.m4a?x=1").is_ok());
    }
}
