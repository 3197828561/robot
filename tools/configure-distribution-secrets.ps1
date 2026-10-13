param(
    [string]$Repository = "3197828561/robot",
    [string]$Server = "aliyun-robot"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$keystorePath = Join-Path $repoRoot ".local-tools\signing\robot-release.jks"
$signingEnvPath = Join-Path $repoRoot ".local-tools\signing\release-signing-secrets.env"
$runtimeEnvPath = Join-Path $repoRoot ".local-tools\config\app-runtime.env"
$distributionEnvPath = Join-Path $repoRoot ".local-tools\config\distribution-secrets.env"
$projectGh = Join-Path $repoRoot ".local-tools\gh\bin\gh.exe"
$installedGh = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"
$gh = if (Test-Path $projectGh) { $projectGh } elseif (Test-Path $installedGh) { $installedGh } else { (Get-Command gh -ErrorAction SilentlyContinue).Source }

function Read-Properties([string]$Path) {
    $result = @{}
    if (!(Test-Path -LiteralPath $Path)) { return $result }
    foreach ($raw in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $raw.Trim()
        if (!$line -or $line.StartsWith("#")) { continue }
        $index = $line.IndexOf("=")
        if ($index -gt 0) { $result[$line.Substring(0, $index).Trim()] = $line.Substring($index + 1).Trim().Trim('"') }
    }
    return $result
}

function Set-RepositorySecret([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "GitHub Secret $Name 为空。" }
    $Value | & $gh secret set $Name --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw "GitHub Secret $Name 写入失败。" }
}

if (!(Test-Path $keystorePath) -or !(Test-Path $signingEnvPath)) {
    throw "缺少固定 JKS 或明文签名配置；请先运行 .\tools\create-release-keystore.ps1。"
}
if (!$gh -or !(Test-Path $gh)) { throw "缺少 GitHub CLI；请执行 winget install --id GitHub.cli -e --source winget。" }
& $gh auth status --hostname github.com *> $null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI 尚未登录。" }

$runtime = Read-Properties $runtimeEnvPath
$mqttPassword = $runtime["MQTT_PASSWORD"]
if (!$mqttPassword) { throw "未找到 MQTT 密码；请在 app-runtime.env 中配置。" }

$remoteScript = @'
set -eu
. /opt/cloud-server/deploy/.env
printf '%s\n' "$APP_RELEASE_UPLOAD_TOKEN"
printf '%s\n' "$APP_DOWNLOAD_USERNAME"
printf '%s\n' "$APP_DOWNLOAD_PASSWORD"
'@
$remoteValues = @($remoteScript | ssh $Server sh)
if ($LASTEXITCODE -ne 0 -or $remoteValues.Count -ne 3) { throw "无法读取服务器发布配置。" }
$uploadToken, $downloadUsername, $downloadPassword = $remoteValues

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $distributionEnvPath) | Out-Null
@(
    "MQTT_PASSWORD=$mqttPassword"
    "APP_RELEASE_UPLOAD_TOKEN=$uploadToken"
    "APP_DOWNLOAD_USERNAME=$downloadUsername"
    "APP_DOWNLOAD_PASSWORD=$downloadPassword"
) | Set-Content -LiteralPath $distributionEnvPath -Encoding UTF8

$signing = Read-Properties $signingEnvPath
try {
    $keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
    Set-RepositorySecret "ANDROID_KEYSTORE_BASE64" $keystoreBase64
    Set-RepositorySecret "ANDROID_KEYSTORE_PASSWORD" $signing["ANDROID_KEYSTORE_PASSWORD"]
    Set-RepositorySecret "ANDROID_KEY_ALIAS" $signing["ANDROID_KEY_ALIAS"]
    Set-RepositorySecret "ANDROID_KEY_PASSWORD" $signing["ANDROID_KEY_PASSWORD"]
    Set-RepositorySecret "API_BASE_URL" "https://47.103.157.213/api"
    Set-RepositorySecret "APP_RELEASE_BASE_URL" "https://47.103.157.213"
    Set-RepositorySecret "APP_RELEASE_UPLOAD_TOKEN" $uploadToken
    Set-RepositorySecret "MQTT_HOST" ($runtime["MQTT_HOST"] ?? "47.103.157.213")
    Set-RepositorySecret "MQTT_PORT" ($runtime["MQTT_PORT"] ?? "1883")
    Set-RepositorySecret "MQTT_USERNAME" ($runtime["MQTT_USERNAME"] ?? "app_user_001")
    Set-RepositorySecret "MQTT_PASSWORD" $mqttPassword
} finally {
    $keystoreBase64 = $null
    $mqttPassword = $null
    $uploadToken = $null
    $downloadPassword = $null
}

$required = @(
    "ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
    "API_BASE_URL", "APP_RELEASE_BASE_URL", "APP_RELEASE_UPLOAD_TOKEN",
    "MQTT_HOST", "MQTT_PORT", "MQTT_USERNAME", "MQTT_PASSWORD"
)
$actual = @(& $gh secret list --repo $Repository --json name | ConvertFrom-Json | ForEach-Object name)
$missing = @($required | Where-Object { $_ -notin $actual })
if ($missing.Count -gt 0) { throw "GitHub Secrets 验证失败，缺少：$($missing -join ', ')" }

Write-Host "GitHub Secrets 名称验证通过；本地发布参数已明文保存到：$distributionEnvPath"
