param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$SkipChecks
)
$ErrorActionPreference = 'Stop'
$notifyProject = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath "$JavaHome/bin/java.exe")) { throw '请设置 JAVA_HOME 或传入 -JavaHome（JDK 17/21）' }
if (-not (Test-Path -LiteralPath "$AndroidSdk/platforms/android-36")) { throw '请设置 ANDROID_HOME 或传入 -AndroidSdk（SDK 36）' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$notifySigning = Join-Path $notifyProject 'signing.properties'
if (-not (Test-Path -LiteralPath $notifySigning)) {
    $notifyKeyDir = Join-Path $notifyProject '.signing'
    New-Item -ItemType Directory -Force -Path $notifyKeyDir | Out-Null
    $notifyKey = Join-Path $notifyKeyDir 'tingxun-release.p12'
    if (Test-Path -LiteralPath $notifyKey) { throw '已有签名密钥但缺少 signing.properties，请恢复原配置以保持升级签名' }
    $notifyRandom = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($notifyRandom)
    $env:TINGXUN_STORE_PASS = [Convert]::ToBase64String($notifyRandom)
    try {
        & "$JavaHome/bin/keytool.exe" -genkeypair -keystore $notifyKey -storetype PKCS12 -storepass:env TINGXUN_STORE_PASS -keypass:env TINGXUN_STORE_PASS -alias tingxun -keyalg RSA -keysize 3072 -validity 10950 -dname 'CN=TingXun Personal, OU=Personal, O=TingXun, C=CN'
        if ($LASTEXITCODE -ne 0) { throw '签名密钥生成失败' }
        @('storeFile=.signing/tingxun-release.p12', "storePassword=$env:TINGXUN_STORE_PASS", 'keyAlias=tingxun', "keyPassword=$env:TINGXUN_STORE_PASS") | Set-Content -LiteralPath $notifySigning -Encoding ascii
    } finally { Remove-Item Env:TINGXUN_STORE_PASS -ErrorAction SilentlyContinue }
}
Push-Location $notifyProject
try {
    $notifyTasks = @(':app:assembleRelease')
    if (-not $SkipChecks) { $notifyTasks += @(':app:testDebugUnitTest', ':app:lintDebug', ':app:lintRelease') }
    & ./gradlew.bat @notifyTasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw '构建或检查失败，未更新交付包' }
    $notifyDelivery = Join-Path $notifyProject 'delivery'
    New-Item -ItemType Directory -Force -Path $notifyDelivery | Out-Null
    $notifyApk = Join-Path $env:LOCALAPPDATA 'TingXunBuild/app/outputs/apk/release/app-release.apk'
    $notifyVersion = [regex]::Match((Get-Content -LiteralPath 'app/build.gradle' -Raw), "versionName '([^']+)'").Groups[1].Value
    $notifyTarget = Join-Path $notifyDelivery "听讯-$notifyVersion.apk"
    Copy-Item -LiteralPath $notifyApk -Destination $notifyTarget
    $notifyHash = (Get-FileHash -LiteralPath $notifyTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    "$notifyHash  听讯-$notifyVersion.apk" | Set-Content -LiteralPath (Join-Path $notifyDelivery "SHA256-$notifyVersion.txt") -Encoding utf8
    Get-Item -LiteralPath $notifyTarget | Select-Object FullName,Length
} finally { Pop-Location }
