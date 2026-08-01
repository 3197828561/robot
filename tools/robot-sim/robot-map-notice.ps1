param(
    [string]$MapUrl = "http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json",
    [string]$MapName = "",
    [string]$MosquittoDir = "",
    [string]$LocalProperties = "local.properties",
    [string]$HostNameOverride = "",
    [string]$PortOverride = "",
    [string]$UsernameOverride = "",
    [string]$PasswordOverride = "",
    [string]$ProductTypeOverride = "crawler",
    [string]$DeviceIdOverride = "crawler_00000001"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_mqtt-common.ps1")

if (!$MapUrl.StartsWith("http://") -and !$MapUrl.StartsWith("https://")) {
    throw "MapUrl must use HTTP or HTTPS."
}

$context = New-RobotMqttContext -ScriptDirectory $PSScriptRoot `
    -MosquittoDir $MosquittoDir -LocalProperties $LocalProperties `
    -HostNameOverride $HostNameOverride -PortOverride $PortOverride `
    -UsernameOverride $UsernameOverride -PasswordOverride $PasswordOverride `
    -ProductTypeOverride $ProductTypeOverride -DeviceIdOverride $DeviceIdOverride

$mapFile = [System.IO.Path]::GetTempFileName()
try {
    Invoke-WebRequest -Uri $MapUrl -OutFile $mapFile -UseBasicParsing -TimeoutSec 20
    $bytes = [System.IO.File]::ReadAllBytes($mapFile)
    if ($bytes.Length -eq 0) { throw "Downloaded map is empty." }
    $map = [System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json -ErrorAction Stop

    $mapId = [long](Get-JsonProperty $map "map_id" -1)
    $mapVersion = [long](Get-JsonProperty $map "version" -1)
    if ($mapId -lt 0 -or $mapVersion -lt 0) {
        throw "Map JSON must contain non-negative map_id and version."
    }

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $checksum = ($sha256.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        $sha256.Dispose()
    }

    $payload = New-RobotBasePayload $context
    $payload["mapId"] = $mapId
    $payload["mapName"] = if ($MapName) { $MapName } else { "map_${mapId}_v${mapVersion}" }
    $payload["mapVersion"] = $mapVersion
    $payload["mapJsonUrl"] = $MapUrl
    $payload["fileSizeBytes"] = [long]$bytes.Length
    $payload["checksum"] = "sha256:$checksum"

    $topic = "$($context.TopicPrefix)/map"
    Publish-MqttJson $context $topic $payload

    Write-Host ""
    Write-Host "Robot map notice published"
    Write-Host "Broker: $($context.HostName):$($context.Port)"
    Write-Host "Username: $($context.Username)"
    Write-Host "Device: $($context.ProductType)/$($context.DeviceId)"
    Write-Host "Topic: $topic"
    Write-Host "Map: id=$mapId version=$mapVersion bytes=$($bytes.Length)"
    Write-Host "URL: $MapUrl"
} finally {
    Remove-Item -LiteralPath $mapFile -ErrorAction SilentlyContinue
}
