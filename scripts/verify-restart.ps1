<#
.SYNOPSIS
Verifies persistence across a process restart using synthetic data only.
.DESCRIPTION
Build assembleVerification assembleVerificationAndroidTest first. Requires one
authorized, unlocked Android device. Installs only com.turnocontrol.verification
and its test APK; replaces that isolated variant's calendar with synthetic fixtures.
Never targets com.turnocontrol. Run from any directory.
ADB discovery: explicit -AdbPath, ANDROID_HOME, ANDROID_SDK_ROOT, PATH, local SDK.
.EXAMPLE
pwsh -File ./scripts/verify-restart.ps1 -AdbPath /path/to/Android/Sdk/platform-tools/adb
#>
param([string]$AdbPath = "")
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AdbPath) {
    $adbName = if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) { 'adb.exe' } else { 'adb' }
    $candidates = @()
    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($sdkRoot) { $candidates += Join-Path $sdkRoot "platform-tools/$adbName" }
    }
    $fromPath = Get-Command $adbName -ErrorAction SilentlyContinue
    if ($fromPath) { $candidates += $fromPath.Source }
    $candidates += Join-Path $projectRoot ".tools/android-sdk/platform-tools/$adbName"
    $AdbPath = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'ADB not found. Set ANDROID_HOME or pass -AdbPath pointing to platform-tools/adb.'
}
function Invoke-Adb([string[]]$Arguments) {
    $result = & $AdbPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $result" }
    return ($result -join "`n")
}
$package = 'com.turnocontrol.verification'
$devices = @((Invoke-Adb @('devices')) -split "`r?`n" | Where-Object { $_ -match '\s+device\s*$' })
if ($devices.Count -ne 1) { throw 'Exactly one authorized Android device is required. Check adb devices and USB authorization.' }
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$test = 'com.turnocontrol.data.RestartPersistenceTest#repositoryConfigurationSurvivesRestart'
$targetApk = Join-Path $projectRoot 'app/build/outputs/apk/verification/app-verification.apk'
$testApk = Join-Path $projectRoot 'app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk'
foreach ($apk in @($targetApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $apk)) { throw "Build assembleVerification assembleVerificationAndroidTest first: $apk" }
    Write-Output (Invoke-Adb @('install', '-r', $apk))
}
function Invoke-RestartPhase([string]$Phase) {
    $result = Invoke-Adb @('shell', 'am', 'instrument', '-w', '-e', 'class', $test, '-e', 'restartPhase', $Phase, $runner)
    Write-Output $result
    if ($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw "Restart phase failed: $Phase"
    }
}
Invoke-RestartPhase 'seed'
# Stop only the isolated test application; the private user's APK is untouched.
Invoke-Adb @('shell', 'am', 'force-stop', $package) | Out-Null
$launch = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$package/com.turnocontrol.MainActivity")
Write-Output $launch
if ($launch -notmatch 'Status: ok') { throw 'Isolated app failed to restart' }
Invoke-RestartPhase 'verify'
Write-Output 'PASS: profile, contract, five rates, split and overnight scheduled shifts with explicit extra classification preserved across a real process restart.'
