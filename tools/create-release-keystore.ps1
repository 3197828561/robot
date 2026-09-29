param(
    [string]$Alias = "robot-app",
    [string]$LocalProperties = "local.properties"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$propertiesPath = Join-Path $repoRoot $LocalProperties
$keystoreDirectory = Join-Path $repoRoot ".local-tools\signing"
$keystorePath = Join-Path $keystoreDirectory "robot-release.jks"

if (Test-Path -LiteralPath $keystorePath) {
    throw "签名库已存在，已停止以避免覆盖：$keystorePath"
}

$javaHome = $env:JAVA_HOME
if (Test-Path -LiteralPath $propertiesPath) {
    foreach ($line in Get-Content -LiteralPath $propertiesPath -Encoding UTF8) {
        if ($line -match '^java\.home=(.+)$') { $javaHome = $matches[1].Trim().Trim('"') }
    }
}
if (!$javaHome) {
    throw "未找到 keytool，请先在 local.properties 配置 java.home。"
}
$keytool = Join-Path $javaHome "bin\keytool.exe"
if (!(Test-Path -LiteralPath $keytool)) {
    throw "未找到 keytool，请先在 local.properties 配置 java.home。"
}

$storeSecure = Read-Host "输入新的签名库密码（至少 12 位）" -AsSecureString
$keySecure = Read-Host "输入新的签名密钥密码（可与签名库不同）" -AsSecureString
$storePassword = [Net.NetworkCredential]::new('', $storeSecure).Password
$keyPassword = [Net.NetworkCredential]::new('', $keySecure).Password
if ($storePassword.Length -lt 12 -or $keyPassword.Length -lt 12) {
    throw "密码长度必须至少为 12 位。"
}

New-Item -ItemType Directory -Force -Path $keystoreDirectory | Out-Null
$env:ROBOT_STORE_PASSWORD = $storePassword
$env:ROBOT_KEY_PASSWORD = $keyPassword
try {
    & $keytool -genkeypair -v `
        -keystore $keystorePath `
        -alias $Alias `
        -keyalg RSA -keysize 4096 -validity 10000 `
        -dname "CN=Solar Robot App, OU=Android, O=Robot Project, C=CN" `
        -storepass:env ROBOT_STORE_PASSWORD `
        -keypass:env ROBOT_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "keytool 创建签名库失败。" }

    $existing = if (Test-Path -LiteralPath $propertiesPath) {
        Get-Content -LiteralPath $propertiesPath -Raw -Encoding UTF8
    } else { "" }
    if ($existing -match '(?m)^android\.(keystore|key)\.') {
        throw "local.properties 已有签名配置；签名库已创建，但请手工核对配置。"
    }
    $block = @"

# Android distribution signing (never commit)
android.keystore.file=.local-tools/signing/robot-release.jks
android.keystore.password=$storePassword
android.key.alias=$Alias
android.key.password=$keyPassword
"@
    Add-Content -LiteralPath $propertiesPath -Value $block -Encoding UTF8
    Write-Host "签名库已创建并写入忽略提交的 local.properties。请立即离线备份 .local-tools/signing/robot-release.jks。"
} finally {
    Remove-Item Env:ROBOT_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:ROBOT_KEY_PASSWORD -ErrorAction SilentlyContinue
    $storePassword = $null
    $keyPassword = $null
}
