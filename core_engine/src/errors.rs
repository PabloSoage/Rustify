// core_engine/src/errors.rs
//
// What kind of failure this was — E-P, moving a contract that lived in Kotlin as string matching.
//
// ## Why this file exists
//
// Every bridge answers a failure the same way: `{"success":false,"error":"<Display>"}`. Kotlin then
// has to decide whether to retry, refresh the session, or give up, and until now it decided by
// **reading that sentence**:
//
// ```kotlin
// msg.contains("401") || msg.contains("expired") || msg.contains("not authenticated") -> AUTH
// Regex("""\b5\d\d\b""").containsMatchIn(msg)                                          -> TRANSIENT
// ```
//
// The information was never missing. `SpotifyError::ApiError(401, …)` holds the status as a number
// and `TokenExpired` is its own variant; both were flattened into prose by `Display` and then
// recovered by regex on the other side of the JNI boundary. A contract carried in English sentences
// is one rewording away from silently downgrading every auth failure to "permanent" — which is the
// failure where the heart button stops working and nothing says so.
//
// So the classification happens **here**, where the types are, and travels as a field. Kotlin keeps
// its heuristic as a fallback for anything that predates this, which is the only reason the change
// is safe to make in one go.
//
// ## Why a trait with a default
//
// `serialize_result` is generic over the error type and is used by 41 bridges over four different
// error enums. A trait with a defaulted method means each enum opts in with one line, and an enum
// that has nothing to say classifies as `Permanent` — the conservative answer, and the same one
// Kotlin's `else ->` already gave.

use std::fmt;

/// What the caller should do about a failure. Four answers, because there are four behaviours.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    /// The session is the problem, and refreshing it may fix this. Retry after re-authenticating.
    Auth,
    /// Too many requests. **Not** retried by the caller: whoever holds the `Retry-After` header owns
    /// the backoff, and that is this side. Retrying above as well multiplied one tap into nine.
    RateLimited,
    /// The network, or the far end, having a bad moment. Worth trying again with a backoff.
    Transient,
    /// Nothing about trying again will change the answer. The default, and deliberately so.
    Permanent,
}

impl ErrorKind {
    /// The wire form. Lowercase and stable — it crosses JNI and is matched by name on the far side,
    /// so it is part of the contract and not a debug string.
    pub fn as_str(self) -> &'static str {
        match self {
            ErrorKind::Auth => "auth",
            ErrorKind::RateLimited => "rateLimited",
            ErrorKind::Transient => "transient",
            ErrorKind::Permanent => "permanent",
        }
    }
}

impl fmt::Display for ErrorKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// An error that can say what kind of failure it was.
///
/// The default is [`ErrorKind::Permanent`]: an error type that has not thought about this should not
/// be able to talk the caller into retrying forever.
pub trait Classify {
    fn kind(&self) -> ErrorKind {
        ErrorKind::Permanent
    }
}

impl Classify for crate::spotify::client::SpotifyError {
    fn kind(&self) -> ErrorKind {
        use crate::spotify::client::SpotifyError as E;
        match self {
            // The two the session refresh exists for.
            E::NotAuthenticated | E::TokenExpired => ErrorKind::Auth,

            // 401 is the same thing arriving as a status rather than as a variant: the token was
            // rejected. 403 is deliberately **not** here — a free account being told
            // `PREMIUM_REQUIRED` is not fixed by getting a fresher token, and treating it as auth
            // would spend a session refresh on every attempt and still fail.
            E::ApiError(401, _) => ErrorKind::Auth,
            E::ApiError(429, _) => ErrorKind::RateLimited,
            E::ApiError(status, _) if (500..600).contains(status) => ErrorKind::Transient,
            E::ApiError(_, _) => ErrorKind::Permanent,

            // A failed fetch is transport, by construction: an HTTP error status is a *successful*
            // fetch and arrives as `ApiError` above.
            E::NetworkError(_) => ErrorKind::Transient,

            // The bytes arrived and did not mean what we expected, or we broke. Asking again gets
            // the same answer.
            E::ParseError(_) | E::InternalError(_) => ErrorKind::Permanent,
        }
    }
}

impl Classify for crate::env::EnvError {
    fn kind(&self) -> ErrorKind {
        use crate::env::EnvError as E;
        match self {
            E::Fetch(_) => ErrorKind::Transient,
            E::Storage(_) | E::Serde(_) | E::Other(_) => ErrorKind::Permanent,
        }
    }
}

impl Classify for crate::addon::AddonError {
    fn kind(&self) -> ErrorKind {
        use crate::addon::AddonError as E;
        match self {
            E::Unreachable(_) => ErrorKind::Transient,
            // `Refused` is a security decision and never becomes true by waiting. Neither does a URL
            // that does not parse or a manifest that does not describe an addon.
            E::InvalidUrl(_) | E::Refused(_) | E::InvalidManifest(_) | E::Protocol(_)
            | E::Storage(_) => ErrorKind::Permanent,
        }
    }
}

impl Classify for crate::types::id::IdError {}

/// The JSON a bridge was handed did not parse. Sending the same bytes again gets the same answer, so
/// the default is right — but it is spelled out rather than left implicit, because "the caller sent
/// us nonsense" is a case worth being able to find.
impl Classify for serde_json::Error {}

impl Classify for String {}

impl Classify for &str {}

impl Classify for Box<dyn std::error::Error> {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::env::EnvError;
    use crate::spotify::client::SpotifyError;

    // These pin the thing the Kotlin side used to guess at, and they are the reason the guess can be
    // retired. Each case below is one the substring heuristic got right by luck of wording.

    #[test]
    fn the_two_the_session_refresh_exists_for_are_auth() {
        assert_eq!(SpotifyError::NotAuthenticated.kind(), ErrorKind::Auth);
        assert_eq!(SpotifyError::TokenExpired.kind(), ErrorKind::Auth);
    }

    #[test]
    fn a_401_status_is_auth_and_a_403_is_not() {
        assert_eq!(
            SpotifyError::ApiError(401, "no".into()).kind(),
            ErrorKind::Auth
        );
        // A free account told PREMIUM_REQUIRED. A fresher token changes nothing, and treating it as
        // auth spends a session refresh per attempt to arrive at the same failure.
        assert_eq!(
            SpotifyError::ApiError(403, "PREMIUM_REQUIRED".into()).kind(),
            ErrorKind::Permanent
        );
    }

    #[test]
    fn rate_limiting_is_its_own_answer_and_not_transient() {
        // Not `Transient` on purpose: the caller must not retry it. This side already retries a 429
        // honouring `Retry-After`, and both layers retrying turned one tap into nine requests.
        assert_eq!(
            SpotifyError::ApiError(429, "slow down".into()).kind(),
            ErrorKind::RateLimited
        );
    }

    #[test]
    fn server_errors_are_transient_and_client_errors_are_not() {
        for status in [500u16, 502, 503, 599] {
            assert_eq!(
                SpotifyError::ApiError(status, String::new()).kind(),
                ErrorKind::Transient,
                "{status} should be transient"
            );
        }
        for status in [400u16, 404, 418, 499] {
            assert_eq!(
                SpotifyError::ApiError(status, String::new()).kind(),
                ErrorKind::Permanent,
                "{status} should be permanent"
            );
        }
    }

    #[test]
    fn a_failed_fetch_is_transport_and_a_bad_parse_is_not() {
        assert_eq!(
            SpotifyError::NetworkError("connection reset".into()).kind(),
            ErrorKind::Transient
        );
        assert_eq!(
            SpotifyError::ParseError("expected an object".into()).kind(),
            ErrorKind::Permanent
        );
        assert_eq!(EnvError::Fetch("timed out".into()).kind(), ErrorKind::Transient);
        assert_eq!(EnvError::Storage("no space".into()).kind(), ErrorKind::Permanent);
    }

    #[test]
    fn the_default_is_the_conservative_one() {
        // An error type that has not thought about this must not be able to ask for a retry.
        assert_eq!("something went wrong".kind(), ErrorKind::Permanent);
        assert_eq!(String::from("likewise").kind(), ErrorKind::Permanent);
    }

    #[test]
    fn the_wire_names_are_what_kotlin_matches_on() {
        // Changing any of these four strings is a breaking change to the bridge, not a rename.
        assert_eq!(ErrorKind::Auth.as_str(), "auth");
        assert_eq!(ErrorKind::RateLimited.as_str(), "rateLimited");
        assert_eq!(ErrorKind::Transient.as_str(), "transient");
        assert_eq!(ErrorKind::Permanent.as_str(), "permanent");
    }
}
