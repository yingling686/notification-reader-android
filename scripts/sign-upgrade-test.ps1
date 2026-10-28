$ErrorActionPreference = 'Stop'
$notifyRoot = Split-Path -Parent $PSScriptRoot
$notifyProperties = @{}
Get-Content -LiteralPath "$notifyRoot/signing.properties" | ForEach-Object {
    $notifyParts = $_ -split '=', 2
    if ($notifyParts.Length -eq 2) { $notifyProperties[$notifyParts[0]] = $notifyParts[1] }
}
$notifySigner = Join-Path $env:LOCALAPPDATA 'TingXunSdk/build-tools/36.0.0/apksigner.bat'
$env:TINGXUN_UPGRADE_STORE_PASS = $notifyProperties['storePassword']
$env:TINGXUN_UPGRADE_KEY_PASS = $notifyProperties['keyPassword']
try {
    & $notifySigner sign --ks (Join-Path $notifyRoot $notifyProperties['storeFile']) --ks-key-alias $notifyProperties['keyAlias'] --ks-pass env:TINGXUN_UPGRADE_STORE_PASS --key-pass env:TINGXUN_UPGRADE_KEY_PASS --out "$env:LOCALAPPDATA/TingXunBuild/upgrade-test-signed.apk" "$env:LOCALAPPDATA/TingXunBuild/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Upgrade test signing failed' }
} finally { Remove-Item Env:TINGXUN_UPGRADE_STORE_PASS,Env:TINGXUN_UPGRADE_KEY_PASS -ErrorAction SilentlyContinue }
