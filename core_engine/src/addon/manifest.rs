// core_engine/src/addon/manifest.rs
//
// What an addon says about itself, and what we insist on before believing it.

use super::AddonError;
use serde::{Deserialize, Serialize};

/// Largest manifest we will read. A manifest is a few hundred bytes; anything approaching this is
/// either a mistake or someone seeing how much memory the app will allocate on request.
pub const MAX_MANIFEST_BYTES: usize = 64 * 1024;

/// What an addon can do. Unknown values are dropped rather than rejected, so an addon written
/// against a later version of the protocol still installs for what it does today.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Resource {
    Stream,
    Download,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Manifest {
    /// Reverse-DNS-ish, stable across versions. Two addons with the same id are the same addon.
    pub id: String,
    pub version: String,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    /// Unknown entries are skipped -- see [`Resource`].
    #[serde(default, deserialize_with = "lenient_resources")]
    pub resources: Vec<Resource>,
    /// Which kinds of track id it can be asked about: `spotify`, `ytm`, `isrc`, `local`.
    /// Empty means "ask it about anything", which is what a simple addon will want.
    #[serde(default, rename = "trackKinds")]
    pub track_kinds: Vec<String>,
}

fn lenient_resources<'de, D>(deserializer: D) -> Result<Vec<Resource>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let raw: Vec<serde_json::Value> = Vec::deserialize(deserializer)?;
    Ok(raw
        .into_iter()
        .filter_map(|value| serde_json::from_value::<Resource>(value).ok())
        .collect())
}

impl Manifest {
    /// Parses and validates in one step, because a manifest that has not been validated should
    /// never exist as a value someone might use.
    pub fn parse(bytes: &[u8]) -> Result<Manifest, AddonError> {
        if bytes.len() > MAX_MANIFEST_BYTES {
            return Err(AddonError::InvalidManifest(format!(
                "manifest is {} bytes, over the {MAX_MANIFEST_BYTES} limit",
                bytes.len()
            )));
        }
        let manifest: Manifest =
            serde_json::from_slice(bytes).map_err(|e| AddonError::InvalidManifest(e.to_string()))?;
        manifest.validate()?;
        Ok(manifest)
    }

    fn validate(&self) -> Result<(), AddonError> {
        check_id(&self.id)?;
        check_short_text("version", &self.version, 32)?;
        check_short_text("name", &self.name, 64)?;
        if let Some(description) = &self.description {
            check_short_text("description", description, 512)?;
        }
        if self.resources.is_empty() {
            return Err(AddonError::InvalidManifest(
                "an addon that declares no resources cannot do anything".into(),
            ));
        }
        for kind in &self.track_kinds {
            check_short_text("trackKinds entry", kind, 32)?;
        }
        Ok(())
    }

    pub fn can(&self, resource: Resource) -> bool {
        self.resources.contains(&resource)
    }

    /// Whether this addon is worth asking about a track of `kind`. An empty `trackKinds` means yes.
    pub fn handles_kind(&self, kind: &str) -> bool {
        self.track_kinds.is_empty() || self.track_kinds.iter().any(|k| k == kind)
    }
}

fn check_id(id: &str) -> Result<(), AddonError> {
    if id.len() < 3 || id.len() > 64 {
        return Err(AddonError::InvalidManifest(
            "id must be between 3 and 64 characters".into(),
        ));
    }
    // Constrained because the id ends up as a storage key and in UI: anything else is an
    // opportunity for a name that renders as something it is not.
    if !id
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '.' || c == '-' || c == '_')
    {
        return Err(AddonError::InvalidManifest(
            "id may only contain lowercase letters, digits, '.', '-' and '_'".into(),
        ));
    }
    Ok(())
}

/// Text that will be shown to a person, so it must not be able to lie about its own shape: no
/// control characters, no line breaks, no bidirectional overrides.
fn check_short_text(field: &str, value: &str, max: usize) -> Result<(), AddonError> {
    if value.trim().is_empty() {
        return Err(AddonError::InvalidManifest(format!("{field} is empty")));
    }
    if value.chars().count() > max {
        return Err(AddonError::InvalidManifest(format!(
            "{field} is longer than {max} characters"
        )));
    }
    if value.chars().any(char::is_control) {
        return Err(AddonError::InvalidManifest(format!(
            "{field} contains control characters"
        )));
    }
    // U+202A..U+202E and U+2066..U+2069 can make displayed text read in the opposite order to the
    // bytes -- the trick behind "Trojan Source". An addon name is exactly where that would be aimed.
    if value
        .chars()
        .any(|c| matches!(c, '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}'))
    {
        return Err(AddonError::InvalidManifest(format!(
            "{field} contains bidirectional overrides"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const GOOD: &str = r#"{
        "id": "org.example.backend",
        "version": "1.0.0",
        "name": "Example backend",
        "description": "Resolves audio for tracks",
        "resources": ["stream", "download"],
        "trackKinds": ["spotify", "isrc"]
    }"#;

    #[test]
    fn a_well_formed_manifest_parses() {
        let manifest = Manifest::parse(GOOD.as_bytes()).unwrap();
        assert_eq!(manifest.id, "org.example.backend");
        assert!(manifest.can(Resource::Stream));
        assert!(manifest.can(Resource::Download));
        assert!(manifest.handles_kind("spotify"));
        assert!(!manifest.handles_kind("ytm"));
    }

    #[test]
    fn an_empty_track_kinds_list_means_anything() {
        let raw = r#"{"id":"a.b","version":"1","name":"n","resources":["stream"]}"#;
        let manifest = Manifest::parse(raw.as_bytes()).unwrap();
        assert!(manifest.handles_kind("ytm"));
        assert!(manifest.handles_kind("anything-at-all"));
    }

    #[test]
    fn an_unknown_resource_is_skipped_not_fatal() {
        // Forward compatibility: an addon written against a later protocol still installs for the
        // part of it we understand today.
        let raw = r#"{"id":"a.b","version":"1","name":"n","resources":["stream","lyrics"]}"#;
        let manifest = Manifest::parse(raw.as_bytes()).unwrap();
        assert_eq!(manifest.resources, vec![Resource::Stream]);
    }

    #[test]
    fn an_addon_that_can_do_nothing_is_refused() {
        let raw = r#"{"id":"a.b","version":"1","name":"n","resources":["lyrics"]}"#;
        assert!(Manifest::parse(raw.as_bytes()).is_err());
        let raw = r#"{"id":"a.b","version":"1","name":"n","resources":[]}"#;
        assert!(Manifest::parse(raw.as_bytes()).is_err());
    }

    #[test]
    fn ids_are_constrained_because_they_become_keys_and_labels() {
        let long = "x".repeat(65);
        for id in ["ab", "UPPER.case", "has space", "emoji.x/y", long.as_str()] {
            let raw = format!(r#"{{"id":"{id}","version":"1","name":"n","resources":["stream"]}}"#);
            assert!(Manifest::parse(raw.as_bytes()).is_err(), "accepted id {id}");
        }
    }

    #[test]
    fn a_name_cannot_lie_about_its_own_shape() {
        // The hostile characters are written as JSON escapes so they survive review as text.

        // U+0007, a control character.
        let with_control =
            "{\"id\":\"a.b\",\"version\":\"1\",\"name\":\"good\\u0007evil\",\"resources\":[\"stream\"]}";
        assert!(Manifest::parse(with_control.as_bytes()).is_err());

        // U+202E RIGHT-TO-LEFT OVERRIDE -- the "Trojan Source" trick.
        let with_bidi =
            "{\"id\":\"a.b\",\"version\":\"1\",\"name\":\"safe\\u202Elive\",\"resources\":[\"stream\"]}";
        assert!(Manifest::parse(with_bidi.as_bytes()).is_err());

        // A newline is a control character too, and a two-line name breaks every list it appears in.
        let with_newline =
            "{\"id\":\"a.b\",\"version\":\"1\",\"name\":\"one\\ntwo\",\"resources\":[\"stream\"]}";
        assert!(Manifest::parse(with_newline.as_bytes()).is_err());
    }

    #[test]
    fn an_oversized_manifest_is_refused_before_it_is_parsed() {
        let huge = vec![b'x'; MAX_MANIFEST_BYTES + 1];
        assert!(Manifest::parse(&huge).is_err());
    }
}
