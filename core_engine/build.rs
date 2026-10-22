// Compiles the audio lab's hand-written ARM kernels (native/fft_kernels.c, native/matmul_kernels.c)
// for 64-bit ARM only. Elsewhere — the host running the tests, armv7, x86_64 — there is nothing to
// build: the Rust side declares the kernels under the same `target_arch` and never names them.
fn main() {
    println!("cargo:rerun-if-changed=native/fft_kernels.c");
    println!("cargo:rerun-if-changed=native/matmul_kernels.c");
    if std::env::var("CARGO_CFG_TARGET_ARCH").as_deref() == Ok("aarch64") {
        let mut build = cc::Build::new();
        build
            .file("native/fft_kernels.c")
            .file("native/matmul_kernels.c")
            .flag("-O3")
            // The file itself targets armv8.0; each kernel raises its own target with an attribute.
            .flag("-march=armv8-a")
            // Return addresses signed (PAuth, armv8.3) and branch targets marked (BTI, armv8.5): hint
            // instructions that older cores run as no-ops.
            .flag("-mbranch-protection=standard");
        let compiler = build.get_compiler();
        build.compile("rustify_fft_kernels");

        // The SME kernels that own ZA (`__arm_new("za")`) call the SME ABI's support routines
        // (`__arm_tpidr2_save`, `__arm_get_current_vg`…). They live in clang's own runtime, which
        // Rust does not link, so link it here; only the objects that define those symbols are
        // pulled in.
        let out = compiler
            .to_command()
            .args(["-rtlib=compiler-rt", "-print-libgcc-file-name"])
            .output()
            .expect("the C compiler runs");
        let lib = std::path::PathBuf::from(String::from_utf8_lossy(&out.stdout).trim());
        if let (Some(dir), Some(stem)) = (lib.parent(), lib.file_stem().and_then(|s| s.to_str())) {
            if lib.exists() {
                println!("cargo:rustc-link-search=native={}", dir.display());
                println!("cargo:rustc-link-lib=static={}", stem.trim_start_matches("lib"));
            }
        }
    }
}
