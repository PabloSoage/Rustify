// core_engine/src/secrets.rs
//
// The operating system's credential store, for the few values the desktop app must not keep in
// plain text: the Spotify `sp_dc` cookie and its access token (docs 25, T2.3). The Windows
// Credential Manager, the macOS Keychain and the Secret Service on Linux (GNOME Keyring, KWallet),
// through `keyring`, behind the `secure-store` feature.
//
// Android does not use this: its preferences are private to the app's sandbox, which is the same
// guarantee the desktop lacks.

use keyring::Entry;

/// One service name for every Rustify secret, so they show up together in the system's manager.
const SERVICE: &str = "com.varuna.rustify";

fn entry(key: &str) -> Result<Entry, String> {
    Entry::new(SERVICE, key).map_err(|e| format!("credential store: {e}"))
}

/// The value under `key`, or `None` when there is none. An error means there is no usable store.
pub fn get(key: &str) -> Result<Option<String>, String> {
    match entry(key)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(format!("credential store: {e}")),
    }
}

pub fn set(key: &str, value: &str) -> Result<(), String> {
    entry(key)?
        .set_password(value)
        .map_err(|e| format!("credential store: {e}"))
}

/// Removing something absent succeeds.
pub fn remove(key: &str) -> Result<(), String> {
    match entry(key)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(format!("credential store: {e}")),
    }
}
