// Compiles the audio lab's hand-written ARM kernels (native/fft_kernels.c, native/matmul_kernels.c)
// for 64-bit ARM Android only, and says so with the `rustify_arm_kernels` cfg, which is what the Rust
// side declares the kernels under. Elsewhere — the host running the tests, armv7, x86_64 — there is
// nothing to build and nothing is named.
//
// Not plain `target_arch = "aarch64"` since the desktop and iOS builds (docs `24`): Apple Silicon,
// iOS, Windows on ARM and aarch64 Linux are aarch64 too, and the kernels assume three things only the
// NDK guarantees — `getauxval` to read the SME bit, clang's SME attributes (`__arm_new("za")`) and
// clang's own runtime for the SME ABI routines. Those targets get the portable FFT and matrix product,
// which is what x86_64 already runs. On the phone nothing changes: every APK ABI that set the cfg
// before (arm64-v8a) sets it now. Turning it on for aarch64 Linux is a measured decision for later.
fn main() {
    println!("cargo:rerun-if-changed=native/fft_kernels.c");
    println!("cargo:rerun-if-changed=native/matmul_kernels.c");
    println!("cargo::rustc-check-cfg=cfg(rustify_arm_kernels)");
    let arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if arch == "aarch64" && os == "android" {
        println!("cargo:rustc-cfg=rustify_arm_kernels");
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
