// core_engine/src/audio/mod.rs
//
// Audio-format work that has to happen to the bytes themselves, as opposed to fetching or serving
// them. Today that is one thing: Deezer's stream encryption.
//
// It lives in the core rather than in the Android app because it is not an Android problem. A
// Kotlin implementation is a Kotlin implementation — the Windows and iOS ports would each have to
// write it again, and three implementations of a cipher scheme is three chances to get it wrong in
// a way that sounds like white noise rather than like an error.

pub mod deezer;
