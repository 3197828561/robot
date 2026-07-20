param(
    [string]$MosquittoDir = "C:\Program Files\Mosquitto",
    [string]$LocalProperties = "local.properties",
    [string]$HostNameOverride = "",
    [string]$PortOverride = "",
    [string]$UsernameOverride = "",
    [string]$PasswordOverride = "",
    [string]$ProductTypeOverride = "",
    [string]$DeviceIdOverride = "",
    [int]$IntervalMs = 1000
)

$ErrorActionPreference = "Stop"

function Read-LocalProperties {
    param([string]$Path)
    $props = @{}
    if (!(Test-Path $Path)) { return $props }
    Get-Content $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) { return }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) { return }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim().Trim('"')
        $props[$key] = $value
    }
    return $props
}

function Get-Prop {
    param(
        [hashtable]$Props,
        [string[]]$Keys,
        [string]$Default = ""
    )
    foreach ($key in $Keys) {
        if ($Props.ContainsKey($key) -and $Props[$key]) {
            return $Props[$key]
        }
    }
    return $Default
}

function Resolve-MosquittoExe {
    param(
        [string]$FileName,
        [string[]]$PathNames
    )
    $candidate = Join-Path $MosquittoDir $FileName
    if ($MosquittoDir -and (Test-Path $candidate)) {
        return $candidate
    }
    foreach ($name in $PathNames) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd) { return $cmd.Source }
    }
    throw "Cannot find $FileName. Install Mosquitto clients, add them to PATH, or pass -MosquittoDir."
}

function New-MqttArgs {
    $args = @("-h", $HostName, "-p", "$Port")
    if ($Username) { $args += @("-u", $Username) }
    if ($Password) { $args += @("-P", $Password) }
    return $args
}

function New-HeartbeatPayload {
    [ordered]@{
        version = "1.0"
        deviceId = $DeviceId
        productType = $ProductType
        timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        online = $true
    } | ConvertTo-Json -Compress
}

function Publish-Heartbeat {
    $payloadFile = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($payloadFile, (New-HeartbeatPayload), [System.Text.UTF8Encoding]::new($false))
        $args = New-MqttArgs
        $args += @("-q", "1", "-t", "$TopicPrefix/heartbeat", "-f", $payloadFile)
        & $PubExe @args | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "mosquitto_pub failed with exit code $LASTEXITCODE"
        }
    } finally {
        Remove-Item -LiteralPath $payloadFile -ErrorAction SilentlyContinue
    }
}

if ($IntervalMs -lt 300) {
    throw "IntervalMs must be at least 300."
}

$props = Read-LocalProperties $LocalProperties
$HostName = if ($HostNameOverride) { $HostNameOverride } else { Get-Prop $props @("mqtt.host") "47.103.157.213" }
$Port = if ($PortOverride) { $PortOverride } else { Get-Prop $props @("mqtt.port") "1883" }
$Username = if ($UsernameOverride) { $UsernameOverride } else { Get-Prop $props @("mqtt.robot.username", "mqtt.username") "app_user_001" }
$Password = if ($PasswordOverride) { $PasswordOverride } else { Get-Prop $props @("mqtt.robot.password", "mqtt.password") "" }
$ProductType = if ($ProductTypeOverride) { $ProductTypeOverride } else { Get-Prop $props @("mqtt.product_type") "crawler" }
$DeviceId = if ($DeviceIdOverride) { $DeviceIdOverride } else { Get-Prop $props @("mqtt.default_device_id") "crawler_00000001" }
$PubExe = Resolve-MosquittoExe "mosquitto_pub.exe" @("mosquitto_pub.exe", "mosquitto_pub")
$TopicPrefix = "device/$ProductType/$DeviceId"

Write-Host ""
Write-Host "Device online heartbeat simulator"
Write-Host "Broker: ${HostName}:$Port"
Write-Host "Username: $Username"
Write-Host "Device: $ProductType/$DeviceId"
Write-Host "Topic: $TopicPrefix/heartbeat"
Write-Host "Interval: ${IntervalMs}ms"
Write-Host "Press Ctrl+C to stop."
Write-Host ""

while ($true) {
    Publish-Heartbeat
    Write-Host "[UP] heartbeat online=True $(Get-Date -Format HH:mm:ss)"
    Start-Sleep -Milliseconds $IntervalMs
}
