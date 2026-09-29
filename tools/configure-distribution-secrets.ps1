param(
    [string]$Repository = "3197828561/robot",
    [string]$Server = "aliyun-robot"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$localPropertiesPath = Join-Path $repoRoot "local.properties"
$signingDirectory = Join-Path $repoRoot ".local-tools\signing"
$keystorePath = Join-Path $signingDirectory "robot-release.jks"
$signingSecretsPath = Join-Path $signingDirectory "release-secrets.clixml"
$encryptedDirectory = Join-Path $repoRoot ".local-tools\secrets"
$encryptedPath = Join-Path $encryptedDirectory "distribution-secrets.clixml"
$projectGh = Join-Path $repoRoot ".local-tools\gh\bin\gh.exe"
$installedGh = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"
$gh = if (Test-Path $projectGh) { $projectGh } elseif (Test-Path $installedGh) { $installedGh } else { (Get-Command gh -ErrorAction SilentlyContinue).Source }

function Read-Properties([string]$Path) {
    $result = @{}
    foreach ($raw in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $raw.Trim()
        if (!$line -or $line.StartsWith("#")) { continue }
        $index = $line.IndexOf("=")
        if ($index -gt 0) { $result[$line.Substring(0, $index).Trim()] = $line.Substring($index + 1).Trim().Trim('"') }
    }
    return $result
}

function Convert-SecureToPlainText([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Set-RepositorySecret([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "GitHub Secret $Name 为空。" }
    $Value | & $gh secret set $Name --repo $Repository --body -
    if ($LASTEXITCODE -ne 0) { throw "GitHub Secret $Name 写入失败。" }
}

if (!(Test-Path $localPropertiesPath)) { throw "缺少 local.properties。" }
if (!(Test-Path $keystorePath) -or !(Test-Path $signingSecretsPath)) {
    throw "请先运行 .\tools\create-release-keystore.ps1 完成一次交互式签名配置。"
}
if (!$gh -or !(Test-Path $gh)) { throw "缺少 GitHub CLI；请执行 winget install --id GitHub.cli -e --source winget。" }
& $gh auth status --hostname github.com *> $null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI 尚未登录；请先运行 .\.local-tools\gh\bin\gh.exe auth login --hostname github.com --git-protocol ssh --web" }

$properties = Read-Properties $localPropertiesPath
$mqttPassword = $properties["mqtt.password"]
if (!$mqttPassword -and (Test-Path $encryptedPath)) {
    $existingEncrypted = Import-Clixml -LiteralPath $encryptedPath
    $mqttPassword = Convert-SecureToPlainText $existingEncrypted.MqttPassword
}
if (!$mqttPassword) { throw "未找到 MQTT 密码；请先在忽略提交的 local.properties 中配置一次 mqtt.password。" }

$remoteScript = @'
set -eu
. /opt/cloud-server/deploy/.env
printf '%s\n' "$APP_RELEASE_UPLOAD_TOKEN"
printf '%s\n' "$APP_DOWNLOAD_USERNAME"
printf '%s\n' "$APP_DOWNLOAD_PASSWORD"
'@
$remoteValues = @($remoteScript | ssh $Server sh)
if ($LASTEXITCODE -ne 0 -or $remoteValues.Count -ne 3) { throw "无法安全读取服务器发布配置。" }
$uploadToken, $downloadUsername, $downloadPassword = $remoteValues

New-Item -ItemType Directory -Force -Path $encryptedDirectory | Out-Null
$encrypted = [pscustomobject]@{
    SchemaVersion = 1
    MqttPassword = ConvertTo-SecureString $mqttPassword -AsPlainText -Force
    AppReleaseUploadToken = ConvertTo-SecureString $uploadToken -AsPlainText -Force
    AppDownloadUsername = $downloadUsername
    AppDownloadPassword = ConvertTo-SecureString $downloadPassword -AsPlainText -Force
}
$temporary = "$encryptedPath.tmp"
$encrypted | Export-Clixml -LiteralPath $temporary -Depth 3
Move-Item -LiteralPath $temporary -Destination $encryptedPath -Force

$signing = Import-Clixml -LiteralPath $signingSecretsPath
$storePassword = Convert-SecureToPlainText $signing.StorePassword
$keyPassword = Convert-SecureToPlainText $signing.KeyPassword
try {
    $keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
    Set-RepositorySecret "ANDROID_KEYSTORE_BASE64" $keystoreBase64
    Set-RepositorySecret "ANDROID_KEYSTORE_PASSWORD" $storePassword
    Set-RepositorySecret "ANDROID_KEY_ALIAS" $signing.Alias
    Set-RepositorySecret "ANDROID_KEY_PASSWORD" $keyPassword
    Set-RepositorySecret "API_BASE_URL" "https://47.103.157.213/api"
    Set-RepositorySecret "APP_RELEASE_BASE_URL" "https://47.103.157.213"
    Set-RepositorySecret "APP_RELEASE_UPLOAD_TOKEN" $uploadToken
    Set-RepositorySecret "MQTT_HOST" ($properties["mqtt.host"] ?? "47.103.157.213")
    Set-RepositorySecret "MQTT_PORT" ($properties["mqtt.port"] ?? "1883")
    Set-RepositorySecret "MQTT_USERNAME" ($properties["mqtt.username"] ?? "app_user_001")
    Set-RepositorySecret "MQTT_PASSWORD" $mqttPassword
} finally {
    $storePassword = $null
    $keyPassword = $null
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

$sanitized = Get-Content -LiteralPath $localPropertiesPath -Encoding UTF8 | Where-Object { $_ -notmatch '^mqtt\.password=' }
Set-Content -LiteralPath $localPropertiesPath -Value $sanitized -Encoding UTF8
Write-Host "GitHub Secrets 名称验证通过；MQTT 密码已迁移到当前用户绑定的 DPAPI 加密配置。"
