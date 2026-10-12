param([string]$AdbPath = "")
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AdbPath) {
    $AdbPath = Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe'
}
function Invoke-Adb([string[]]$Arguments) {
    $result = & $AdbPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $result" }
    return ($result -join "`n")
}
$package = 'com.turnocontrol.verification'
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
Write-Output 'PASS: profile, contract and five rates preserved across a real process restart.'
