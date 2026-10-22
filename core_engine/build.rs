// Compiles the audio lab's hand-written ARM kernels (native/fft_kernels.c) for 64-bit ARM only.
// Elsewhere — the host running the tests, armv7, x86_64 — there is nothing to build: the Rust side
// declares the kernels under the same `target_arch` and never names them.
fn main() {
    println!("cargo:rerun-if-changed=native/fft_kernels.c");
    if std::env::var("CARGO_CFG_TARGET_ARCH").as_deref() == Ok("aarch64") {
        cc::Build::new()
            .file("native/fft_kernels.c")
            .flag("-O3")
            // The file itself targets armv8.0; each kernel raises its own target with an attribute.
            .flag("-march=armv8-a")
            .compile("rustify_fft_kernels");
    }
}
