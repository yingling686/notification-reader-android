param([string]$Avd = 'TingXun35', [int]$Port = 5560, [string]$Label = 'api35', [switch]$VerifyUpgrade, [string]$TestClass = '', [string]$Version = '0.1.12')
$ErrorActionPreference = 'Stop'
$notifyRoot = Split-Path -Parent $PSScriptRoot
$notifySdk = Join-Path $env:LOCALAPPDATA 'TingXunSdk'
$notifyAdb = Join-Path $notifySdk 'platform-tools/adb.exe'
$notifySerial = "emulator-$Port"
if ($VerifyUpgrade) { & "$PSScriptRoot/sign-upgrade-test.ps1" }
$notifyResults = Join-Path $notifyRoot "docs/results/$Version/$Label"
New-Item -ItemType Directory -Force -Path $notifyResults | Out-Null
$env:ANDROID_AVD_HOME = Join-Path $env:LOCALAPPDATA 'TingXunAvd'
$env:ANDROID_HOME = $notifySdk
$notifyProcess = Start-Process -FilePath "$notifySdk/emulator/emulator.exe" -ArgumentList @('-avd',$Avd,'-port',$Port,'-no-window','-no-audio','-no-snapshot','-gpu','swiftshader_indirect') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$notifyResults/emulator.log" -RedirectStandardError "$notifyResults/emulator-error.log"
try {
    $notifyBoot = $false
    for ($notifyAttempt = 0; $notifyAttempt -lt 90; $notifyAttempt++) {
        Start-Sleep -Seconds 2
        $notifyBootValue = & $notifyAdb -s $notifySerial shell getprop sys.boot_completed 2>$null
        if ($notifyBootValue -match '^1') { $notifyBoot = $true; break }
        if ($notifyProcess.HasExited) { throw "Emulator exited: $($notifyProcess.ExitCode)" }
    }
    if (-not $notifyBoot) { throw 'Emulator boot timed out' }
    Write-Output "$Label booted"
    & $notifyAdb -s $notifySerial shell input keyevent 224
    & $notifyAdb -s $notifySerial shell wm dismiss-keyguard
    # Dedicated test AVDs may retain a release-signed package from an earlier upgrade check.
    if ($notifySerial -notmatch '^emulator-\d+$') { throw 'This suite requires a dedicated emulator' }
    & $notifyAdb -s $notifySerial uninstall cn.tingxun.app.test 2>$null
    & $notifyAdb -s $notifySerial uninstall cn.tingxun.app 2>$null
    & $notifyAdb -s $notifySerial install -r "$env:LOCALAPPDATA/TingXunBuild/app/outputs/apk/debug/app-debug.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Debug APK install failed' }
    & $notifyAdb -s $notifySerial install -r "$env:LOCALAPPDATA/TingXunBuild/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed' }
    $notifyArguments = @('-s', $notifySerial, 'shell', 'am', 'instrument', '-w', '-e', 'clearPackageData', 'false')
    if ($TestClass) { $notifyArguments += @('-e', 'class', $TestClass) }
    $notifyArguments += 'cn.tingxun.app.test/androidx.test.runner.AndroidJUnitRunner'
    & $notifyAdb -s $notifySerial logcat -c
    $notifyRun = & $notifyAdb @notifyArguments
    $notifyRun | Tee-Object -FilePath "$notifyResults/test-run.txt"
    & $notifyAdb -s $notifySerial logcat -d -b crash | Set-Content -LiteralPath "$notifyResults/crash.txt"
    if (-not ($notifyRun -match '^OK \(\d+ tests?\)')) { throw 'Instrumentation failed; see test-run.txt' }
    & python "$PSScriptRoot/pull-screenshots.py" $notifyAdb $notifySerial "$notifyResults/screenshots"
    if ($VerifyUpgrade) {
        # This fixture workflow may delete data ONLY from this script's dedicated emulator.
        if ($notifySerial -notmatch '^emulator-\d+$') { throw 'Upgrade fixtures require an emulator' }
        & $notifyAdb -s $notifySerial uninstall cn.tingxun.app.test
        & $notifyAdb -s $notifySerial uninstall cn.tingxun.app
        # The seed runner must use the old Config ABI; the verification runner uses the new one.
        $notifySeedApk = "$env:LOCALAPPDATA/TingXunBuild/upgrade-seed-017.apk"
        if (-not (Test-Path -LiteralPath $notifySeedApk)) { throw 'Keep a signed 0.1.7 UpgradeFixtureTest APK as upgrade-seed-017.apk before upgrading the test sources' }
        & $notifyAdb -s $notifySerial install "$notifyRoot/delivery/听讯-0.1.7.apk"
        & $notifyAdb -s $notifySerial install $notifySeedApk
        $notifySeed = & $notifyAdb -s $notifySerial shell am instrument -w -e class 'cn.tingxun.app.UpgradeFixtureTest#seed' -e upgradePhase seed cn.tingxun.app.test/androidx.test.runner.AndroidJUnitRunner
        $notifySeed | Tee-Object -FilePath "$notifyResults/upgrade-seed.txt"
        if (-not ($notifySeed -match '^OK \(1 test\)')) { throw 'Upgrade seed failed' }
        & $notifyAdb -s $notifySerial install -r "$env:LOCALAPPDATA/TingXunBuild/app/outputs/apk/release/app-release.apk"
        if ($LASTEXITCODE -ne 0) { throw 'Signed release upgrade failed' }
        & $notifyAdb -s $notifySerial uninstall cn.tingxun.app.test
        & $notifyAdb -s $notifySerial install "$env:LOCALAPPDATA/TingXunBuild/upgrade-test-signed.apk"
        $notifyVerify = & $notifyAdb -s $notifySerial shell am instrument -w -e class 'cn.tingxun.app.UpgradeFixtureTest#verify' -e upgradePhase verify cn.tingxun.app.test/androidx.test.runner.AndroidJUnitRunner
        $notifyVerify | Tee-Object -FilePath "$notifyResults/upgrade-verify.txt"
        if (-not ($notifyVerify -match '^OK \(1 test\)')) { throw 'Upgrade fixture verification failed' }
        & $notifyAdb -s $notifySerial shell dumpsys package cn.tingxun.app | Select-String 'versionCode=|versionName=' | Set-Content -LiteralPath "$notifyResults/upgrade-version.txt"
    }
} finally {
    # Revoking exact alarms may kill the target app; do it only after the test runner exits.
    & $notifyAdb -s $notifySerial shell appops set cn.tingxun.app SCHEDULE_EXACT_ALARM default 2>$null
    & $notifyAdb -s $notifySerial emu kill
    if (-not $notifyProcess.HasExited) { Stop-Process -Id $notifyProcess.Id -ErrorAction SilentlyContinue }
}
