// iosApp/Sources/KeychainStore.swift
//
// The Keychain behind SecretStorage (shared/src/iosMain/.../platform/KeychainKeyValueStore.kt): where
// the Spotify cookie and token live on iOS, instead of NSUserDefaults. Written here rather than in
// Kotlin because every Security call takes and answers a CFDictionary, which Swift bridges for free.

import Foundation
import RustifyShared
import Security

/// Generic passwords under one service, one account per key — the scheme the desktop's credential
/// store uses (core_engine/src/secrets.rs), so the two read alike in a Keychain browser.
final class KeychainStore: NSObject, SecretStorage {
    private let service = "com.varuna.rustify"

    private func query(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }

    func secretFor(key: String) -> String? {
        var request = query(key)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            // Before the first unlock after a reboot the answer is errSecInteractionNotAllowed: the
            // value is there but cannot be read yet. The caller treats it as absent for now.
            if status != errSecItemNotFound { print("W/KeychainStore: reading \(key): \(status)") }
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func storeSecret(key: String, value: String) -> Bool {
        // After the first unlock: a background launch (the lock screen's play button after a
        // reboot and one unlock) can still restore the session. Not ...ThisDeviceOnly, so an
        // encrypted backup restored onto a new phone keeps the user signed in, as NSUserDefaults did.
        let attributes: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        var status = SecItemUpdate(query(key) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            let add = query(key).merging(attributes) { _, new in new }
            status = SecItemAdd(add as CFDictionary, nil)
        }
        if status != errSecSuccess { print("W/KeychainStore: storing \(key): \(status)") }
        return status == errSecSuccess
    }

    func removeSecret(key: String) -> Bool {
        let status = SecItemDelete(query(key) as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            print("W/KeychainStore: removing \(key): \(status)")
        }
        return status == errSecSuccess || status == errSecItemNotFound
    }
}
