package com.varuna.rustify.util

/**
 * App Links verified hosts — single source of truth.
 *
 * Each entry must have a corresponding `intent-filter` in [AndroidManifest.xml] with
 * `android:autoVerify="true"` and a matching `assetlinks.json` hosted at the domain root.
 *
 * To add a host:
 * 1. Add it here.
 * 2. Add an intent-filter in the manifest (same host + pathPrefix="/r").
 * 3. Host `.well-known/assetlinks.json` at the domain root with this app's package name
 *    and signing key SHA-256 fingerprints.
 *
 * This object is used by [MainActivity.extractDeepLink] to unwrap incoming wrapper links
 * for all known hosts. The manifest entries are build-time (cannot be read at runtime).
 */
object AppLinksHosts {

    /** Verified App Links hosts baked into the manifest. First entry is the default. */
    val verifiedHosts: List<String> = listOf(
        "rustify-music.github.io",
        "pablosoage.github.io",
    )

    /** Default wrapper host preselected in the UI when no preference is set yet. */
    val DEFAULT_HOST: String = verifiedHosts.first()

    /** Placeholder host kept for reference; does NOT verify. */
    const val PLACEHOLDER_HOST = "rustify.example.com"
}
