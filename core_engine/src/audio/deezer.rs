// core_engine/src/audio/deezer.rs
//
// Deezer stream decryption — the public deemix scheme.
//
// A track comes off the CDN in 2048-byte stripes. Only one stripe in three is encrypted (index
// congruent to 0 mod 3); the rest is cleartext, and a final short stripe is never encrypted. Each
// encrypted stripe is an independent Blowfish/CBC/NoPadding operation with a fixed IV, so
// decryption is **stripe-local**: nothing depends on the stripe before it. That is what makes
// seeking possible at all — align a byte offset down to 2048 and you know exactly which stripes
// you need and what their indices are.
//
// The primitive is RustCrypto's; the stripe scheme is ours. Hand-rolling Blowfish means typing
// 4 KiB of S-box constants, which is not a thing to write from memory, and CBC on top of a block
// cipher is four lines.
//
// This used to live only in `DeezerCrypto.kt`. The Kotlin side is still there, for the first play
// of a track that is not cached yet (see `docs/stremio-core/PLAN-3.x.md` §4.6); the two are kept in
// step by a shared test vector, exactly as `TrackId` and `TrackRef` are.

// `Block<Blowfish>` is the crate's own alias for the 8-byte block type. Spelled this way rather
// than reaching into `cipher::generic_array` because the alias is what the trait signatures use.
use blowfish::cipher::Block;
use blowfish::cipher::{BlockDecrypt, KeyInit};
use blowfish::Blowfish;
use md5::{Digest, Md5};

/// The stripe size the whole scheme is built on.
pub const STRIPE: usize = 2048;

/// Fixed IV. Not a secret and not a nonce — every stripe uses it.
const IV: [u8; 8] = [0, 1, 2, 3, 4, 5, 6, 7];

/// The published deemix secret, mixed into the per-track key.
const SECRET: &[u8; 16] = b"g4el58wc0zvf9na1";

const HEX: &[u8; 16] = b"0123456789abcdef";

/// Is the stripe at `index` encrypted?
///
/// A named function rather than an inline `% 3` because it is the one rule that, when it is wrong,
/// produces audio that plays and is noise — the failure mode that does not raise anything.
#[inline]
pub fn stripe_is_encrypted(index: u64) -> bool {
    index % 3 == 0
}

/// The 16-byte Blowfish key for a Deezer track id.
///
/// `sng_id` is the id as the API gives it, an ASCII decimal string. The derivation is
/// `md5_hex[i] ^ md5_hex[i + 16] ^ SECRET[i]` over the **hex text** of the digest, not over its
/// bytes. Worth stating: reading it as bytes also produces sixteen plausible-looking bytes, and
/// decrypts every track to noise.
pub fn blowfish_key(sng_id: &str) -> [u8; 16] {
    let digest = Md5::digest(sng_id.as_bytes());
    let mut hex = [0u8; 32];
    for (i, byte) in digest.iter().enumerate() {
        hex[i * 2] = HEX[(byte >> 4) as usize];
        hex[i * 2 + 1] = HEX[(byte & 0x0f) as usize];
    }
    let mut key = [0u8; 16];
    for i in 0..16 {
        key[i] = hex[i] ^ hex[i + 16] ^ SECRET[i];
    }
    key
}

/// Decrypts one full stripe in place.
///
/// A stripe shorter than [`STRIPE`] is left alone: Deezer does not encrypt a trailing partial
/// stripe, and running the cipher over one corrupts the end of every track.
pub fn decrypt_stripe(key: &[u8; 16], stripe: &mut [u8]) {
    if stripe.len() != STRIPE {
        return;
    }
    let cipher = match <Blowfish as KeyInit>::new_from_slice(key) {
        Ok(cipher) => cipher,
        // Unreachable with a 16-byte key, and leaving the bytes alone beats corrupting them.
        Err(_) => return,
    };

    // CBC by hand over the block cipher: `plain = D(c) ^ prev`, then `prev = c`. Four lines, and it
    // saves a second dependency whose only job would be to hold that one variable.
    let mut prev = IV;
    for offset in (0..stripe.len()).step_by(8) {
        let mut ciphertext = [0u8; 8];
        ciphertext.copy_from_slice(&stripe[offset..offset + 8]);

        let mut block = Block::<Blowfish>::clone_from_slice(&ciphertext);
        cipher.decrypt_block(&mut block);

        for i in 0..8 {
            stripe[offset + i] = block[i] ^ prev[i];
        }
        prev = ciphertext;
    }
}

/// Decrypts `buffer` in place, given the index of the stripe it starts at.
///
/// `buffer` must begin on a stripe boundary. Returns how many stripes it covered, so a caller
/// reading a file in windows can carry the index forward without recomputing it from an offset.
pub fn decrypt_aligned(key: &[u8; 16], buffer: &mut [u8], first_stripe_index: u64) -> u64 {
    let mut index = first_stripe_index;
    let mut covered = 0;
    for stripe in buffer.chunks_mut(STRIPE) {
        if stripe_is_encrypted(index) {
            decrypt_stripe(key, stripe);
        }
        index += 1;
        covered += 1;
    }
    covered
}

/// Decrypts the whole of `src` into `dst`.
///
/// Reads and writes a stripe-aligned window at a time, so a lossless track never sits in memory:
/// this runs against files the size of a FLAC album track.
pub async fn decrypt_file(sng_id: &str, src: &str, dst: &str) -> std::io::Result<u64> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let key = blowfish_key(sng_id);
    let mut input = tokio::io::BufReader::new(tokio::fs::File::open(src).await?);
    if let Some(parent) = std::path::Path::new(dst).parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let mut output = tokio::io::BufWriter::new(tokio::fs::File::create(dst).await?);

    // 32 stripes: enough that the syscall count is irrelevant, and a whole number of stripes so the
    // window always starts on a boundary.
    const WINDOW: usize = STRIPE * 32;
    let mut buffer = vec![0u8; WINDOW];
    let mut stripe_index: u64 = 0;
    let mut total: u64 = 0;

    loop {
        // `read` may return short, and a short read that does not land on a stripe boundary would
        // shift every stripe after it. Fill the window before touching any of it.
        let mut filled = 0;
        while filled < WINDOW {
            let n = input.read(&mut buffer[filled..]).await?;
            if n == 0 {
                break;
            }
            filled += n;
        }
        if filled == 0 {
            break;
        }
        stripe_index += decrypt_aligned(&key, &mut buffer[..filled], stripe_index);
        output.write_all(&buffer[..filled]).await?;
        total += filled as u64;
        if filled < WINDOW {
            break;
        }
    }

    output.flush().await?;
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The shared vector. `DeezerCryptoTest.kt` derives a key for the same id and asserts the same
    /// sixteen bytes, which is what keeps the Kotlin path (still used for the first, uncached play)
    /// and this one from drifting apart.
    const VECTOR_SNG_ID: &str = "3135556";
    /// The sixteen bytes both implementations must produce for [`VECTOR_SNG_ID`].
    const VECTOR_KEY: [u8; 16] = [
        0x6c, 0x6c, 0x66, 0x6b, 0x39, 0x66, 0x2c, 0x37, 0x65, 0x25, 0x75, 0x60, 0x3c, 0x64, 0x34,
        0x39,
    ];

    #[test]
    fn the_key_matches_the_vector_shared_with_the_kotlin_side() {
        // A literal, not a recomputation: this is the value `DeezerCryptoTest.kt` asserts, and a
        // change to either derivation that is not a change to both fails here.
        assert_eq!(blowfish_key(VECTOR_SNG_ID), VECTOR_KEY);
    }

    #[test]
    fn the_key_derivation_follows_the_documented_rule() {
        // Derived here from the rule rather than pasted from another implementation, so this test
        // says what the rule is instead of only that it did not change.
        let digest = Md5::digest(VECTOR_SNG_ID.as_bytes());
        // Hex written out by hand rather than through `{:x}`: the point of this test is to state the
        // rule, and "the hex *text* of the digest" is the whole rule. A formatter would hide it.
        let mut hex = String::new();
        for byte in digest.iter() {
            hex.push_str(&format!("{byte:02x}"));
        }
        let bytes = hex.as_bytes();
        assert_eq!(bytes.len(), 32);
        let mut expected = [0u8; 16];
        for i in 0..16 {
            expected[i] = bytes[i] ^ bytes[i + 16] ^ SECRET[i];
        }
        assert_eq!(blowfish_key(VECTOR_SNG_ID), expected);
    }

    #[test]
    fn the_key_depends_on_the_id_and_only_on_the_id() {
        assert_ne!(blowfish_key("3135556"), blowfish_key("3135557"));
        assert_eq!(blowfish_key("3135556"), blowfish_key("3135556"));
    }

    #[test]
    fn a_stripe_round_trips_through_the_cipher() {
        use blowfish::cipher::BlockEncrypt;

        let key = blowfish_key(VECTOR_SNG_ID);
        let plain: Vec<u8> = (0..STRIPE).map(|i| (i % 251) as u8).collect();

        // Encrypt the way the CDN would, then assert our decryption undoes exactly that. This is
        // the only test here that would catch a wrong IV, a wrong block order or a wrong endianness.
        let cipher = <Blowfish as KeyInit>::new_from_slice(&key).unwrap();
        let mut encrypted = plain.clone();
        let mut prev = IV;
        for offset in (0..STRIPE).step_by(8) {
            let mut block = [0u8; 8];
            for i in 0..8 {
                block[i] = encrypted[offset + i] ^ prev[i];
            }
            let mut ga = Block::<Blowfish>::clone_from_slice(&block);
            cipher.encrypt_block(&mut ga);
            for i in 0..8 {
                encrypted[offset + i] = ga[i];
            }
            prev.copy_from_slice(&ga);
        }
        assert_ne!(encrypted, plain, "the vector has to actually be encrypted");

        decrypt_stripe(&key, &mut encrypted);
        assert_eq!(encrypted, plain);
    }

    #[test]
    fn only_one_stripe_in_three_is_encrypted() {
        assert!(stripe_is_encrypted(0));
        assert!(!stripe_is_encrypted(1));
        assert!(!stripe_is_encrypted(2));
        assert!(stripe_is_encrypted(3));
        assert!(stripe_is_encrypted(300));
    }

    #[test]
    fn a_partial_trailing_stripe_is_left_alone() {
        // What this guards against is inaudible until the last second of every track.
        let key = blowfish_key(VECTOR_SNG_ID);
        let mut tail = vec![7u8; 100];
        let before = tail.clone();
        decrypt_stripe(&key, &mut tail);
        assert_eq!(tail, before);
    }

    #[test]
    fn decrypt_aligned_touches_only_the_stripes_it_should() {
        let key = blowfish_key(VECTOR_SNG_ID);
        let mut buffer = vec![9u8; STRIPE * 3];
        let before = buffer.clone();
        decrypt_aligned(&key, &mut buffer, 0);
        assert_ne!(buffer[..STRIPE], before[..STRIPE]);
        assert_eq!(buffer[STRIPE..], before[STRIPE..]);
    }

    #[test]
    fn the_stripe_index_carries_across_windows() {
        // A window starting at stripe 2 must leave stripe 2 alone and decrypt stripe 3. Getting
        // this wrong only shows up on tracks longer than one window, which is all of them.
        let key = blowfish_key(VECTOR_SNG_ID);
        let mut buffer = vec![9u8; STRIPE * 2];
        let before = buffer.clone();
        let covered = decrypt_aligned(&key, &mut buffer, 2);
        assert_eq!(covered, 2);
        assert_eq!(buffer[..STRIPE], before[..STRIPE]);
        assert_ne!(buffer[STRIPE..], before[STRIPE..]);
    }

    #[tokio::test]
    async fn decrypt_file_handles_a_length_that_is_not_a_whole_number_of_stripes() {
        use blowfish::cipher::BlockEncrypt;

        let dir = std::env::temp_dir()
            .join(format!("rustify-deezer-{}", crate::server::random_hex(8)));
        tokio::fs::create_dir_all(&dir).await.unwrap();
        let src = dir.join("in.bin");
        let dst = dir.join("out.bin");

        // Four stripes and a bit: stripes 0 and 3 encrypted, 1, 2 and the tail cleartext.
        let key = blowfish_key(VECTOR_SNG_ID);
        let plain: Vec<u8> = (0..(STRIPE * 4 + 777)).map(|i| (i % 251) as u8).collect();
        let mut wire = plain.clone();
        let cipher = <Blowfish as KeyInit>::new_from_slice(&key).unwrap();
        for (index, stripe) in wire.chunks_mut(STRIPE).enumerate() {
            if !stripe_is_encrypted(index as u64) || stripe.len() != STRIPE {
                continue;
            }
            let mut prev = IV;
            for offset in (0..STRIPE).step_by(8) {
                let mut block = [0u8; 8];
                for i in 0..8 {
                    block[i] = stripe[offset + i] ^ prev[i];
                }
                let mut ga = Block::<Blowfish>::clone_from_slice(&block);
                cipher.encrypt_block(&mut ga);
                for i in 0..8 {
                    stripe[offset + i] = ga[i];
                }
                prev.copy_from_slice(&ga);
            }
        }

        tokio::fs::write(&src, &wire).await.unwrap();
        let written = decrypt_file(
            VECTOR_SNG_ID,
            src.to_str().unwrap(),
            dst.to_str().unwrap(),
        )
        .await
        .unwrap();

        assert_eq!(written as usize, plain.len());
        assert_eq!(tokio::fs::read(&dst).await.unwrap(), plain);
        let _ = tokio::fs::remove_dir_all(&dir).await;
    }
}
