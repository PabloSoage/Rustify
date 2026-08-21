# scripts/coverage.ps1
#
# Test coverage for `core_engine`.
#
# Run this on the machine that builds. Nothing in this working copy compiles, so the number can only
# come from you.
#
#     pwsh scripts/coverage.ps1            # summary in the terminal
#     pwsh scripts/coverage.ps1 -Html      # plus an HTML report you can click through
#     pwsh scripts/coverage.ps1 -Open      # ...and open it
#
# ## Why only Rust
#
# Because it is the only number here that means anything. `core_engine` is pure logic behind `Env`,
# so every branch is reachable from a test with no device and no network — a gap in this report is a
# real gap.
#
# The Kotlin side is 37 000 lines of which the large majority is Jetpack Compose, a foreground
# service and ExoPlayer wiring. Measuring that with unit tests reports a single-digit percentage that
# says nothing about risk: it is not "untested", it is "tested by running the app", and chasing the
# number would mean writing assertions about layout instead of about behaviour. What is worth testing
# in Kotlin already is: the codecs that must agree with Rust (`TrackRefTest`), and anything with
# arithmetic in it. See docs/17-testing-and-coverage.md.

param(
    [switch]$Html,
    [switch]$Open,
    # Fail the run when the line rate drops below this. Wire it into CI rather than trusting a habit.
    [int]$FailUnder = 0
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $repo 'core_engine')

# The host build needs no C toolchain, and that is a property worth keeping rather than a
# coincidence. `rquickjs-sys` is the only thing that ever wanted one, and its `bindgen` feature is
# now scoped to Android in Cargo.toml — see the comment there, and docs/17.
#
# Pointing LIBCLANG_PATH at the NDK looked like the fix and is not: that clang targets Android and
# cannot find the MSVC headers, so it fails on `stdio.h` in the middle of what reads like a test run.
# If `bindgen` ever shows up in this build again, something has pulled a native dependency back into
# the host graph, and scoping it is the fix rather than installing LLVM.

if (-not (Get-Command cargo-llvm-cov -ErrorAction SilentlyContinue)) {
    Write-Host 'cargo-llvm-cov is not installed. Once:' -ForegroundColor Yellow
    Write-Host '    rustup component add llvm-tools-preview'
    Write-Host '    cargo install cargo-llvm-cov'
    exit 1
}

# --ignore-filename-regex: `lib.rs` is the JNI layer -- it cannot run without a JVM, so counting it
#   only adds noise. `env/android.rs` is the same with reqwest and Android's filesystem, and it is
#   by design the thing `MockEnv` replaces: its being uncovered IS the architecture working.
#   Excluding both is what makes the percentage describe logic rather than plumbing.
$common = @(
    # `MockEnv` is gated on `any(test, feature = "mock-env")`. The `test` half covers the unit tests
    # inside the crate; an integration test is a SEPARATE crate, so for it the cfg is false and the
    # module simply is not there. That is exactly what the feature exists for.
    '--features', 'mock-env',
    '--ignore-filename-regex', '(src[\\/]lib\.rs|src[\\/]env[\\/]android\.rs)'
)

if ($Html) {
    cargo llvm-cov @common --html
    $report = Join-Path (Get-Location) 'target\llvm-cov\html\index.html'
    Write-Host "`nReport: $report"
    if ($Open) { Start-Process $report }
} else {
    if ($FailUnder -gt 0) {
        cargo llvm-cov @common --summary-only --fail-under-lines $FailUnder
    } else {
        cargo llvm-cov @common --summary-only
    }
}
