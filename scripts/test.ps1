# scripts/test.ps1
#
# Runs both test suites. On the machine that builds — nothing runs in the working copy.
#
#     pwsh scripts/test.ps1              # both suites
#     pwsh scripts/test.ps1 -Rust        # engine only (~2 s warm)
#     pwsh scripts/test.ps1 -Kotlin      # app only
#     pwsh scripts/test.ps1 -Coverage    # engine tests through coverage, then the app
#
# ## Why this exists, and it is not "so there is a script"
#
# Neither suite was being run, and neither failure was visible. Gradle builds the test source only
# when you ask for the test task — `assembleRelease` does not — and `cargo test` was never invoked
# at all.
#
# The first time each one actually executed:
#
#   * Rust: ten tests failed, including one that had been correctly describing a security hole since
#     the day it was written (a stream url was allowed to be loopback in a debug build).
#   * Kotlin: the test source did not **compile**. One invalid escape sequence, sitting there since
#     3.1, and nothing had ever asked for that source to be built.
#
# A test that has never been executed is not a test. It is a comment with syntax.

param(
    [switch]$Rust,
    [switch]$Kotlin,
    # Run the engine suite under cargo-llvm-cov instead of plain `cargo test`, so one command gives
    # both the pass/fail and the number. Slower, because it rebuilds with instrumentation.
    [switch]$Coverage
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
# Neither switch means both, which is what you want nine times out of ten.
$all = -not ($Rust -or $Kotlin)
$failed = @()

if ($Rust -or $all) {
    Write-Host "`n=== core_engine ===" -ForegroundColor Cyan
    if ($Coverage) {
        # coverage.ps1 owns the feature flags and the exclusions; duplicating them here is how the
        # two drift apart.
        & (Join-Path $PSScriptRoot 'coverage.ps1')
        if ($LASTEXITCODE -ne 0) { $failed += 'core_engine' }
    } else {
        Push-Location (Join-Path $repo 'core_engine')
        try {
            # `--features mock-env`: MockEnv is gated on `any(test, feature = "mock-env")`, and the
            # `test` half only covers tests INSIDE the crate. An integration test is a separate
            # crate, so without this the tests in `tests/` do not compile at all.
            cargo test --features mock-env
            if ($LASTEXITCODE -ne 0) { $failed += 'core_engine' }
        } finally { Pop-Location }
    }
}

if ($Kotlin -or $all) {
    Write-Host "`n=== app ===" -ForegroundColor Cyan
    Push-Location $repo
    try {
        # Debug rather than release: the unit tests do not care which, and debug skips R8, which is
        # minutes of work for no extra answer.
        $gradle = if ($IsWindows -ne $false) { '.\gradlew.bat' } else { './gradlew' }
        & $gradle testDebugUnitTest --console=plain
        if ($LASTEXITCODE -ne 0) { $failed += 'app' }
        $report = Join-Path $repo 'app\build\reports\tests\testDebugUnitTest\index.html'
        if (Test-Path $report) { Write-Host "Report: $report" -ForegroundColor DarkGray }
    } finally { Pop-Location }
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host "FAILED: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host 'All green.' -ForegroundColor Green
