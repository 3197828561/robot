param(
    [ValidateSet("auto", "menu", "listen")]
    [string]$Mode = "auto",
    [string]$MosquittoDir = "C:\Program Files\Mosquitto",
    [string]$LocalProperties = "local.properties",
    [string]$HostNameOverride = "",
    [string]$PortOverride = "",
    [string]$UsernameOverride = "",
    [string]$PasswordOverride = "",
    [string]$ProductTypeOverride = "",
    [string]$DeviceIdOverride = "",
    [switch]$ListenOnly,
    [switch]$MenuOnly,
    [switch]$NoAutoAck
)

$ErrorActionPreference = "Stop"

if ($ListenOnly) { $Mode = "listen" }
if ($MenuOnly) { $Mode = "menu" }

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

function New-MqttArgs {
    param([switch]$VerboseSubscribe)
    $args = @("-h", $HostName, "-p", "$Port")
    if ($Username) { $args += @("-u", $Username) }
    if ($Password) { $args += @("-P", $Password) }
    if ($VerboseSubscribe) { $args += "-v" }
    return $args
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

function New-BasePayload {
    [ordered]@{
        version = "1.0"
        deviceId = $DeviceId
        productType = $ProductType
        timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
    }
}

function ConvertTo-CompactJson {
    param([object]$Payload)
    $Payload | ConvertTo-Json -Compress -Depth 8
}

function Publish-Heartbeat {
    param([bool]$Online)
    $payload = New-BasePayload
    $payload["online"] = $Online
    Invoke-MqttPub "$TopicPrefix/heartbeat" (ConvertTo-CompactJson $payload)
    Write-Host "[UP] heartbeat online=$Online"
}

function Publish-Status {
    param(
        [string]$WorkStatus = $script:WorkStatus,
        [string]$MovementStatus = $script:MovementStatus,
        [string]$DeviceStatus = $script:DeviceStatus,
        [string]$ControlMode = $script:ControlMode,
        [double]$Battery = $script:Battery,
        [double]$Linear = $script:LinearSpeed,
        [double]$Angular = $script:AngularSpeed
    )
    $payload = New-BasePayload
    $payload["workStatus"] = $WorkStatus
    $payload["controlMode"] = $ControlMode
    $payload["batteryPercent"] = $Battery
    $payload["linearSpeedCms"] = [Math]::Round($Linear, 2)
    $payload["angularSpeedRadps"] = [Math]::Round($Angular, 3)
    $payload["deviceStatus"] = $DeviceStatus
    $payload["movementStatus"] = $MovementStatus
    $payload["currentBlockId"] = $script:CurrentBlockId
    $payload["currentCellId"] = $script:CurrentCellId
    $payload["positionX"] = [Math]::Round($script:PositionX, 2)
    $payload["positionY"] = [Math]::Round($script:PositionY, 2)
    $payload["headingDeg"] = [Math]::Round($script:HeadingDeg, 1)
    $payload["yawDeg"] = [Math]::Round($script:HeadingDeg, 1)
    $payload["pitchDeg"] = 3.2
    $payload["temperatureC"] = 36.8
    $payload["totalMileageM"] = 1280.0
    $payload["cleanedRows"] = 12
    $payload["pressureKpa"] = 102.4
    $payload["antiFallLeftM"] = 0.85
    $payload["antiFallRightM"] = 0.82
    Invoke-MqttPub "$TopicPrefix/status" (ConvertTo-CompactJson $payload)
    Write-Host "[UP] status work=$WorkStatus movement=$MovementStatus device=$DeviceStatus speed=$Linear/$Angular"
}

function Publish-CmdAck {
    param(
        [string]$CmdId,
        [string]$Cmd,
        [string]$AckStatus = "success",
        [string]$Message = "simulated ack",
        [string]$ErrorCode = ""
    )
    $payload = New-BasePayload
    $payload["cmdId"] = $CmdId
    $payload["cmd"] = $Cmd
    $payload["ackStatus"] = $AckStatus
    $payload["message"] = $Message
    if ($ErrorCode) { $payload["errorCode"] = $ErrorCode }
    Invoke-MqttPub "$TopicPrefix/cmd_ack" (ConvertTo-CompactJson $payload)
    Write-Host "[UP] cmd_ack cmd=$Cmd cmdId=$CmdId status=$AckStatus"
}

function Publish-MapNotice {
    param([string]$Url = "")
    $payload = New-BasePayload
    $payload["mapId"] = "map_sim_001"
    $payload["mapName"] = "simulated-map"
    $payload["mapVersion"] = 1
    $payload["mapJsonUrl"] = $Url
    $payload["fileSizeBytes"] = 0
    $payload["checksum"] = ""
    Invoke-MqttPub "$TopicPrefix/map" (ConvertTo-CompactJson $payload)
    Write-Host "[UP] map notice url='$Url'"
}

function Apply-Cmd {
    param([string]$Cmd)
    switch ($Cmd) {
        "start" {
            $script:WorkStatus = "running"
            $script:MovementStatus = "moving"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "auto"
            $script:LinearSpeed = 12
            $script:AngularSpeed = 0
        }
        "stop" {
            $script:WorkStatus = "stopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "manual"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "estop" {
            $script:WorkStatus = "estopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "estop"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "clear_estop" {
            $script:WorkStatus = "stopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "manual"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
    }
}

function Handle-DownlinkLine {
    param([string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) { return }
    $firstSpace = $Line.IndexOf(" ")
    if ($firstSpace -le 0) {
        Write-Host "[DOWN] $Line"
        return
    }
    $topic = $Line.Substring(0, $firstSpace)
    $json = $Line.Substring($firstSpace + 1)
    Write-Host "[DOWN] $topic $json"
    try {
        $msg = $json | ConvertFrom-Json
        if ($topic.EndsWith("/cmd")) {
            $cmdId = [string]$msg.cmdId
            $cmd = [string]$msg.cmd
            if (!$cmdId) { $cmdId = "missing_cmd_id" }
            if (!$cmd) { $cmd = "unknown" }
            if ($cmd -in @("start", "stop", "estop", "clear_estop")) {
                Apply-Cmd $cmd
                if (!$NoAutoAck) {
                    Publish-CmdAck -CmdId $cmdId -Cmd $cmd -AckStatus "success" -Message "$cmd accepted by simulator"
                }
                Publish-Status
            } else {
                if (!$NoAutoAck) {
                    Publish-CmdAck -CmdId $cmdId -Cmd $cmd -AckStatus "failed" -Message "unsupported cmd" -ErrorCode "SIM_UNSUPPORTED_CMD"
                }
            }
        } elseif ($topic.EndsWith("/remote")) {
            $script:LinearSpeed = [double]$msg.linearSpeedCms
            $script:AngularSpeed = [double]$msg.angularSpeedRadps
            $moving = [Math]::Abs($script:LinearSpeed) -gt 0.01 -or [Math]::Abs($script:AngularSpeed) -gt 0.01
            $script:WorkStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "manual"
            $script:MovementStatus = if ($moving) { "moving" } else { "stopped" }
            $script:HeadingDeg = ($script:HeadingDeg + $script:AngularSpeed * 8.0) % 360.0
            $script:PositionX += $script:LinearSpeed / 100.0
            Publish-Status
        }
    } catch {
        Write-Host "[WARN] failed to parse downlink: $($_.Exception.Message)"
    }
}

function Start-DownlinkSubscriber {
    $script:SubOut = [System.IO.Path]::GetTempFileName()
    $script:SubErr = [System.IO.Path]::GetTempFileName()
    $args = New-MqttArgs -VerboseSubscribe
    $args += @("-q", "1", "-t", "$TopicPrefix/cmd", "-t", "$TopicPrefix/remote")
    $process = Start-Process -FilePath $SubExe -ArgumentList $args -RedirectStandardOutput $script:SubOut -RedirectStandardError $script:SubErr -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 300
    if ($process.HasExited) {
        $err = Get-Content $script:SubErr -Raw -ErrorAction SilentlyContinue
        throw "mosquitto_sub exited early. $err"
    }
    $script:SubReadOffset = 0
    return $process
}

function Read-NewSubscriberLines {
    if (!(Test-Path $script:SubOut)) { return @() }
    $fs = [System.IO.File]::Open($script:SubOut, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        if ($script:SubReadOffset -gt $fs.Length) { $script:SubReadOffset = 0 }
        $fs.Seek($script:SubReadOffset, [System.IO.SeekOrigin]::Begin) | Out-Null
        $reader = [System.IO.StreamReader]::new($fs)
        $text = $reader.ReadToEnd()
        $script:SubReadOffset = $fs.Position
        if ([string]::IsNullOrWhiteSpace($text)) { return @() }
        return $text -split "(`r`n|`n|`r)" | Where-Object { $_ -and $_.Trim().Length -gt 0 }
    } finally {
        $fs.Close()
    }
}

function Stop-DownlinkSubscriber {
    param($Process)
    if ($Process -and !$Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $script:SubOut -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $script:SubErr -ErrorAction SilentlyContinue
}

function Run-ListenOnly {
    Write-Host "Listening for App messages. Press Ctrl+C to exit."
    $args = New-MqttArgs -VerboseSubscribe
    $args += @("-q", "1", "-t", "$TopicPrefix/cmd", "-t", "$TopicPrefix/remote")
    & $SubExe @args
    exit $LASTEXITCODE
}

function Run-AutoRobot {
    Write-Host "Auto robot is running. Press Ctrl+C to exit."
    Write-Host "It publishes heartbeat/status and automatically acks App cmd with the same cmdId."
    Write-Host ""
    $sub = Start-DownlinkSubscriber
    try {
        $lastHeartbeat = [DateTime]::MinValue
        $lastStatus = [DateTime]::MinValue
        while ($true) {
            foreach ($line in Read-NewSubscriberLines) {
                Handle-DownlinkLine $line
            }
            $now = Get-Date
            if (($now - $lastHeartbeat).TotalMilliseconds -ge 1000) {
                Publish-Heartbeat $true
                $lastHeartbeat = $now
            }
            if (($now - $lastStatus).TotalMilliseconds -ge 1500) {
                Publish-Status
                $lastStatus = $now
            }
            Start-Sleep -Milliseconds 250
        }
    } finally {
        Stop-DownlinkSubscriber $sub
    }
}

function Run-Menu {
    Write-Host "Manual simulator menu. For automatic button acks, use default Mode=auto."
    while ($true) {
        Write-Host ""
        Write-Host "1. Heartbeat online"
        Write-Host "2. Heartbeat offline"
        Write-Host "3. Status: stopped/manual/normal (enables joystick and start)"
        Write-Host "4. Status: running/auto/normal (enables stop)"
        Write-Host "5. Status: estopped"
        Write-Host "6. Status: fault"
        Write-Host "7. Ack success by cmdId"
        Write-Host "8. Ack failed by cmdId"
        Write-Host "9. Map notice without URL"
        Write-Host "0. Exit"
        $choice = Read-Host "Choose"
        switch ($choice) {
            "1" { Publish-Heartbeat $true }
            "2" { Publish-Heartbeat $false }
            "3" {
                $script:WorkStatus = "stopped"; $script:MovementStatus = "stopped"; $script:DeviceStatus = "normal"; $script:ControlMode = "manual"; $script:LinearSpeed = 0; $script:AngularSpeed = 0
                Publish-Status
            }
            "4" {
                $script:WorkStatus = "running"; $script:MovementStatus = "moving"; $script:DeviceStatus = "normal"; $script:ControlMode = "auto"; $script:LinearSpeed = 12; $script:AngularSpeed = 0
                Publish-Status
            }
            "5" {
                $script:WorkStatus = "estopped"; $script:MovementStatus = "stopped"; $script:DeviceStatus = "normal"; $script:ControlMode = "estop"; $script:LinearSpeed = 0; $script:AngularSpeed = 0
                Publish-Status
            }
            "6" {
                $script:WorkStatus = "fault"; $script:MovementStatus = "blocked"; $script:DeviceStatus = "fault"; $script:ControlMode = "auto"; $script:LinearSpeed = 0; $script:AngularSpeed = 0
                Publish-Status
            }
            "7" {
                $cmdId = Read-Host "cmdId from listen output"
                $cmd = Read-Host "cmd, e.g. start"
                Publish-CmdAck -CmdId $cmdId -Cmd $cmd -AckStatus "success" -Message "$cmd accepted by simulator"
            }
            "8" {
                $cmdId = Read-Host "cmdId from listen output"
                $cmd = Read-Host "cmd, e.g. start"
                Publish-CmdAck -CmdId $cmdId -Cmd $cmd -AckStatus "failed" -Message "simulated failure" -ErrorCode "SIM_FAIL"
            }
            "9" { Publish-MapNotice }
            "0" { break }
            default { Write-Host "Unknown choice" }
        }
    }
}

$props = Read-LocalProperties $LocalProperties
$HostName = if ($HostNameOverride) { $HostNameOverride } else { Get-Prop $props @("mqtt.host") "47.103.157.213" }
$Port = if ($PortOverride) { $PortOverride } else { Get-Prop $props @("mqtt.port") "1883" }
$Username = if ($UsernameOverride) { $UsernameOverride } else { Get-Prop $props @("mqtt.robot.username", "mqtt.username") "app_user_001" }
$Password = if ($PasswordOverride) { $PasswordOverride } else { Get-Prop $props @("mqtt.robot.password", "mqtt.password") "" }
$ProductType = if ($ProductTypeOverride) { $ProductTypeOverride } else { Get-Prop $props @("mqtt.product_type") "crawler" }
$DeviceId = if ($DeviceIdOverride) { $DeviceIdOverride } else { Get-Prop $props @("mqtt.default_device_id") "crawler_00000001" }

$PubExe = Join-Path $MosquittoDir "mosquitto_pub.exe"
$SubExe = Join-Path $MosquittoDir "mosquitto_sub.exe"
if (!(Test-Path $PubExe)) { throw "Cannot find $PubExe" }
if (!(Test-Path $SubExe)) { throw "Cannot find $SubExe" }

$TopicPrefix = "device/$ProductType/$DeviceId"

$script:WorkStatus = "stopped"
$script:MovementStatus = "stopped"
$script:DeviceStatus = "normal"
$script:ControlMode = "manual"
$script:Battery = 88.0
$script:LinearSpeed = 0.0
$script:AngularSpeed = 0.0
$script:CurrentBlockId = "block-A"
$script:CurrentCellId = "cell-001"
$script:PositionX = 1.0
$script:PositionY = 2.0
$script:HeadingDeg = 0.0

Write-Host ""
Write-Host "Robot MQTT simulator"
Write-Host "Mode: $Mode"
Write-Host "Broker: ${HostName}:$Port"
Write-Host "Username: $Username"
Write-Host "Device: $ProductType/$DeviceId"
Write-Host "Topics: $TopicPrefix/*"
Write-Host ""

switch ($Mode) {
    "listen" { Run-ListenOnly }
    "menu" { Run-Menu }
    "auto" { Run-AutoRobot }
}
