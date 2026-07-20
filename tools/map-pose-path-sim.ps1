param(
    [string]$MosquittoDir = "C:\Program Files\Mosquitto",
    [string]$LocalProperties = "local.properties",
    [string]$HostNameOverride = "",
    [string]$PortOverride = "",
    [string]$UsernameOverride = "",
    [string]$PasswordOverride = "",
    [string]$ProductTypeOverride = "",
    [string]$DeviceIdOverride = "",
    [string]$MapUrl = "http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json",
    [int]$PoseIntervalMs = 400,
    [int]$HeartbeatIntervalMs = 1000,
    [int]$StatusIntervalMs = 1500,
    [int]$MapNoticeIntervalSec = 10
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

function ConvertTo-CompactJson {
    param([object]$Payload)
    $Payload | ConvertTo-Json -Compress -Depth 16
}

function New-BasePayload {
    [ordered]@{
        version = "1.0"
        deviceId = $DeviceId
        productType = $ProductType
        timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
    }
}

function Invoke-MqttPub {
    param(
        [string]$Topic,
        [string]$Payload
    )
    $payloadFile = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($payloadFile, $Payload, [System.Text.UTF8Encoding]::new($false))
        $args = New-MqttArgs
        $args += @("-q", "1", "-t", $Topic, "-f", $payloadFile)
        & $PubExe @args | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "mosquitto_pub failed with exit code $LASTEXITCODE"
        }
    } finally {
        Remove-Item -LiteralPath $payloadFile -ErrorAction SilentlyContinue
    }
}

function Publish-Heartbeat {
    $payload = New-BasePayload
    $payload["online"] = $true
    Invoke-MqttPub "$TopicPrefix/heartbeat" (ConvertTo-CompactJson $payload)
    Write-Host "[UP] heartbeat online=True"
}

function Publish-Status {
    $payload = New-BasePayload
    $payload["workStatus"] = "stopped"
    $payload["controlMode"] = "manual"
    $payload["batteryPercent"] = 88.0
    $payload["linearSpeedCms"] = 0.0
    $payload["angularSpeedRadps"] = 0.0
    $payload["deviceStatus"] = "normal"
    $payload["movementStatus"] = "stopped"
    $payload["yawDeg"] = 0.0
    $payload["pitchDeg"] = 0.0
    $payload["temperatureC"] = 36.5
    $payload["totalMileageM"] = 0.0
    $payload["cleanedRows"] = 0
    $payload["pressureKpa"] = 101.3
    $payload["antiFallLeftM"] = 0.8
    $payload["antiFallRightM"] = 0.8
    Invoke-MqttPub "$TopicPrefix/status" (ConvertTo-CompactJson $payload)
    Write-Host "[UP] status stopped/manual/normal"
}

function Publish-MapNotice {
    $payload = New-BasePayload
    $payload["mapId"] = [long]$Map.map_id
    $payload["mapName"] = "map_$($Map.map_id)_v$($Map.version)"
    $payload["mapVersion"] = [int]$Map.version
    $payload["mapJsonUrl"] = $MapUrl
    $payload["fileSizeBytes"] = $MapFileSize
    $payload["checksum"] = $null
    Invoke-MqttPub "$TopicPrefix/map" (ConvertTo-CompactJson $payload)
    Write-Host "[UP] map id=$($Map.map_id) version=$($Map.version) url=$MapUrl"
}

function Publish-Pose {
    param([object]$Pose)
    $payload = New-BasePayload
    $payload["mapId"] = [long]$Map.map_id
    $payload["mapVersion"] = [int]$Map.version
    $payload["blockId"] = [long]$Pose.blockId
    $payload["cellId"] = [long]$Pose.cellId
    $payload["cellRow"] = [int]$Pose.cellRow
    $payload["cellCol"] = [int]$Pose.cellCol
    $payload["innerRow"] = [int]$Pose.innerRow
    $payload["innerCol"] = [int]$Pose.innerCol
    $payload["headingCode"] = [int]$Pose.headingCode
    $payload["heading"] = $Pose.heading
    Invoke-MqttPub "$TopicPrefix/pose" (ConvertTo-CompactJson $payload)
    Write-Host "[UP] pose block=$($Pose.blockId) cell=$($Pose.cellId) row=$($Pose.cellRow)/$($Pose.innerRow) col=$($Pose.cellCol)/$($Pose.innerCol) heading=$($Pose.heading)"
}

function Get-HeadingName {
    param([int]$Code)
    switch ($Code) {
        0 { "block_u_positive" }
        1 { "block_u_negative" }
        2 { "block_v_positive" }
        3 { "block_v_negative" }
        default { "block_u_positive" }
    }
}

function New-PosePath {
    param([object]$PvMap)
    $path = New-Object System.Collections.Generic.List[object]
    $innerRows = [int]$PvMap.cell_model.inner_rows
    $innerCols = [int]$PvMap.cell_model.inner_cols

    foreach ($block in @($PvMap.blocks | Sort-Object block_id)) {
        $blockCells = @($PvMap.cells | Where-Object { $_.block_id -eq $block.block_id })
        for ($row = 0; $row -lt [int]$block.rows; $row++) {
            $cellCols = if (($row % 2) -eq 0) {
                0..([int]$block.cols - 1)
            } else {
                ([int]$block.cols - 1)..0
            }
            foreach ($cellCol in $cellCols) {
                $cell = $blockCells | Where-Object { $_.row -eq $row -and $_.col -eq $cellCol } | Select-Object -First 1
                if ($null -eq $cell) { continue }
                $cellForward = (($row % 2) -eq 0)
                for ($innerRow = 0; $innerRow -lt $innerRows; $innerRow++) {
                    $innerForward = if ($cellForward) { ($innerRow % 2) -eq 0 } else { ($innerRow % 2) -ne 0 }
                    $innerColsSeq = if ($innerForward) { 0..($innerCols - 1) } else { ($innerCols - 1)..0 }
                    $headingCode = if ($innerForward) { 0 } else { 1 }
                    foreach ($innerCol in $innerColsSeq) {
                        $path.Add([pscustomobject]@{
                            blockId = [long]$block.block_id
                            cellId = [long]$cell.cell_id
                            cellRow = [int]$cell.row
                            cellCol = [int]$cell.col
                            innerRow = [int]$innerRow
                            innerCol = [int]$innerCol
                            headingCode = [int]$headingCode
                            heading = Get-HeadingName $headingCode
                        })
                    }
                }
            }
        }
    }

    if ($path.Count -eq 0) {
        throw "No pose points were generated from the map."
    }
    return $path
}

if ($PoseIntervalMs -lt 50) { throw "PoseIntervalMs must be at least 50." }
if ($HeartbeatIntervalMs -lt 300) { throw "HeartbeatIntervalMs must be at least 300." }
if ($StatusIntervalMs -lt 300) { throw "StatusIntervalMs must be at least 300." }
if ($MapNoticeIntervalSec -lt 1) { throw "MapNoticeIntervalSec must be at least 1." }

$props = Read-LocalProperties $LocalProperties
$HostName = if ($HostNameOverride) { $HostNameOverride } else { Get-Prop $props @("mqtt.host") "47.103.157.213" }
$Port = if ($PortOverride) { $PortOverride } else { Get-Prop $props @("mqtt.port") "1883" }
$Username = if ($UsernameOverride) { $UsernameOverride } else { Get-Prop $props @("mqtt.robot.username", "mqtt.username") "app_user_001" }
$Password = if ($PasswordOverride) { $PasswordOverride } else { Get-Prop $props @("mqtt.robot.password", "mqtt.password") "" }
$ProductType = if ($ProductTypeOverride) { $ProductTypeOverride } else { Get-Prop $props @("mqtt.product_type") "crawler" }
$DeviceId = if ($DeviceIdOverride) { $DeviceIdOverride } else { Get-Prop $props @("mqtt.default_device_id") "crawler_00000001" }
$PubExe = Resolve-MosquittoExe "mosquitto_pub.exe" @("mosquitto_pub.exe", "mosquitto_pub")
$TopicPrefix = "device/$ProductType/$DeviceId"

$MapFile = Join-Path ([System.IO.Path]::GetTempPath()) "map_2_v1.json"
Invoke-WebRequest -Uri $MapUrl -OutFile $MapFile
$MapFileSize = (Get-Item $MapFile).Length
$Map = Get-Content -Path $MapFile -Raw -Encoding UTF8 | ConvertFrom-Json
$PosePath = @(New-PosePath $Map)

Write-Host ""
Write-Host "Map and pose path simulator"
Write-Host "Broker: ${HostName}:$Port"
Write-Host "Username: $Username"
Write-Host "Device: $ProductType/$DeviceId"
Write-Host "Map: id=$($Map.map_id) version=$($Map.version)"
Write-Host "Map URL: $MapUrl"
Write-Host "Pose points: $($PosePath.Count)"
Write-Host "Pose interval: ${PoseIntervalMs}ms"
Write-Host "Topics: $TopicPrefix/map, $TopicPrefix/pose"
Write-Host "Press Ctrl+C to stop."
Write-Host ""

Publish-Heartbeat
Publish-Status
Publish-MapNotice

$poseIndex = 0
$lastHeartbeat = [DateTime]::MinValue
$lastStatus = [DateTime]::MinValue
$lastMap = Get-Date
$lastPose = [DateTime]::MinValue

while ($true) {
    $now = Get-Date
    if (($now - $lastHeartbeat).TotalMilliseconds -ge $HeartbeatIntervalMs) {
        Publish-Heartbeat
        $lastHeartbeat = $now
    }
    if (($now - $lastStatus).TotalMilliseconds -ge $StatusIntervalMs) {
        Publish-Status
        $lastStatus = $now
    }
    if (($now - $lastMap).TotalSeconds -ge $MapNoticeIntervalSec) {
        Publish-MapNotice
        $lastMap = $now
    }
    if (($now - $lastPose).TotalMilliseconds -ge $PoseIntervalMs) {
        Publish-Pose $PosePath[$poseIndex]
        $poseIndex = ($poseIndex + 1) % $PosePath.Count
        $lastPose = $now
    }
    Start-Sleep -Milliseconds 50
}
