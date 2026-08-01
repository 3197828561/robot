param(
    [string]$MosquittoDir = "",
    [string]$LocalProperties = "local.properties",
    [string]$HostNameOverride = "",
    [string]$PortOverride = "",
    [string]$UsernameOverride = "",
    [string]$PasswordOverride = "",
    [string]$ProductTypeOverride = "",
    [string]$DeviceIdOverride = "",
    [int]$HeartbeatIntervalMs = 1000,
    [int]$StatusIntervalMs = 1000,
    [int]$RunSeconds = 0
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_mqtt-common.ps1")

if ($HeartbeatIntervalMs -lt 300) { throw "HeartbeatIntervalMs must be at least 300." }
if ($StatusIntervalMs -lt 300) { throw "StatusIntervalMs must be at least 300." }
if ($RunSeconds -lt 0) { throw "RunSeconds cannot be negative." }

$context = New-RobotMqttContext -ScriptDirectory $PSScriptRoot `
    -MosquittoDir $MosquittoDir -LocalProperties $LocalProperties `
    -HostNameOverride $HostNameOverride -PortOverride $PortOverride `
    -UsernameOverride $UsernameOverride -PasswordOverride $PasswordOverride `
    -ProductTypeOverride $ProductTypeOverride -DeviceIdOverride $DeviceIdOverride

$script:OperationalMode = "auto"
$script:SafetyState = "normal"
$script:LinearSpeed = 0.0
$script:AngularSpeed = 0.0
$script:LastRemoteAt = $null
$script:LastPrintedRemoteAt = $null
$script:LastPrintedLinear = $null
$script:LastPrintedAngular = $null
$script:ProcessedCommands = @{}

function Publish-Heartbeat {
    $payload = New-RobotBasePayload $context
    $payload["online"] = $true
    Publish-MqttJson $context "$($context.TopicPrefix)/heartbeat" $payload
}

function Publish-Status {
    $moving = [Math]::Abs($script:LinearSpeed) -gt 0.01 -or [Math]::Abs($script:AngularSpeed) -gt 0.001
    $payload = New-RobotBasePayload $context
    $payload["workStatus"] = "stopped"
    $payload["controlMode"] = $script:OperationalMode
    $payload["batteryPercent"] = 88.0
    $payload["linearSpeedCms"] = [Math]::Round($script:LinearSpeed, 2)
    $payload["angularSpeedRadps"] = [Math]::Round($script:AngularSpeed, 3)
    $payload["deviceStatus"] = "normal"
    $payload["movementStatus"] = if ($moving) { "moving" } else { "stopped" }
    $payload["yawDeg"] = 0.0
    $payload["pitchDeg"] = 0.0
    $payload["temperatureC"] = 36.5
    $payload["totalMileageM"] = 0.0
    $payload["cleanedRows"] = 0
    $payload["pressureKpa"] = 101.3
    $payload["antiFallLeftM"] = 0.8
    $payload["antiFallRightM"] = 0.8
    $payload["missionId"] = $null
    $payload["rootMissionId"] = $null
    $payload["taskKind"] = $null
    $payload["runState"] = "idle"
    $payload["orchestrationState"] = "idle"
    $payload["taskStackDepth"] = 0
    $payload["interruptionReason"] = $null
    $payload["operationalMode"] = $script:OperationalMode
    $payload["safetyState"] = $script:SafetyState
    $payload["phase"] = "none"
    $payload["activeAction"] = if ($script:OperationalMode -eq "manual") { "remote" } else { "" }
    $payload["waypointIndex"] = 0
    $payload["waypointCount"] = 0
    $payload["errorCode"] = 0
    $payload["errorRetryable"] = $false
    $payload["errorSource"] = ""
    $payload["errorMessage"] = ""
    Publish-MqttJson $context "$($context.TopicPrefix)/status" $payload
}

function Publish-CmdAck {
    param(
        [string]$CmdId,
        [string]$Cmd,
        [string]$AckStatus,
        [string]$ErrorCode = ""
    )

    $payload = New-RobotBasePayload $context
    $payload["cmdId"] = $CmdId
    $payload["cmd"] = $Cmd
    $payload["ackStatus"] = $AckStatus
    $payload["message"] = if ($AckStatus -eq "success") { "accepted" } else { "rejected" }
    if ($ErrorCode) { $payload["errorCode"] = $ErrorCode }
    Publish-MqttJson $context "$($context.TopicPrefix)/cmd_ack" $payload
    Write-Host "[ROBOT -> APP][ACK] cmd=$Cmd cmdId=$CmdId status=$AckStatus error=$ErrorCode"
}

function Write-RemoteMessage {
    param([double]$Linear, [double]$Angular, [int]$DurationMs)

    $now = Get-Date
    $changed = $null -eq $script:LastPrintedLinear -or
        [Math]::Abs($Linear - $script:LastPrintedLinear) -gt 0.001 -or
        [Math]::Abs($Angular - $script:LastPrintedAngular) -gt 0.0001
    $periodic = $null -eq $script:LastPrintedRemoteAt -or
        ($now - $script:LastPrintedRemoteAt).TotalMilliseconds -ge 1000
    if (!$changed -and !$periodic) { return }

    $direction = if ([Math]::Abs($Linear) -le 0.01 -and [Math]::Abs($Angular) -le 0.001) {
        "STOP"
    } elseif ([Math]::Abs($Linear) -gt 0.01) {
        if ($Linear -gt 0) { "FORWARD" } else { "BACKWARD" }
    } else {
        if ($Angular -gt 0) { "LEFT" } else { "RIGHT" }
    }
    Write-Host "[APP -> ROBOT][REMOTE] $direction linear=$Linear cm/s angular=$Angular rad/s duration=${DurationMs}ms"
    $script:LastPrintedLinear = $Linear
    $script:LastPrintedAngular = $Angular
    $script:LastPrintedRemoteAt = $now
}

function Handle-Cmd {
    param([string]$Json, [object]$Message)

    $cmdId = [string](Get-JsonProperty $Message "cmdId" "missing_cmd_id")
    $cmd = [string](Get-JsonProperty $Message "cmd" "unknown")
    Write-Host "[APP -> ROBOT][CMD] cmd=$cmd cmdId=$cmdId payload=$Json"

    if ($script:ProcessedCommands.ContainsKey($cmdId)) {
        $cached = $script:ProcessedCommands[$cmdId]
        if ($cached.Json -ne $Json) {
            Publish-CmdAck $cmdId $cmd "failed" "MISSION_INVALID_REQUEST"
        } else {
            Publish-CmdAck $cmdId $cmd $cached.Status $cached.ErrorCode
        }
        return
    }

    $status = "success"
    $errorCode = ""
    switch ($cmd) {
        "manual" {
            if ($script:SafetyState -ne "normal") {
                $status = "failed"
                $errorCode = "MISSION_ILLEGAL_STATE"
            } else {
                $script:OperationalMode = "manual"
                $script:LinearSpeed = 0
                $script:AngularSpeed = 0
            }
        }
        "auto" {
            $script:OperationalMode = "auto"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "estop" {
            $script:SafetyState = "estop"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "clear_estop" {
            if ($script:SafetyState -ne "estop") {
                $status = "failed"
                $errorCode = "MISSION_ILLEGAL_STATE"
            } else {
                $script:SafetyState = "normal"
            }
        }
        default {
            $status = "failed"
            $errorCode = "SIM_MANUAL_MODE_ONLY"
        }
    }

    $script:ProcessedCommands[$cmdId] = @{ Json = $Json; Status = $status; ErrorCode = $errorCode }
    Publish-CmdAck $cmdId $cmd $status $errorCode
    Publish-Status
}

function Handle-Remote {
    param([object]$Message)

    if ($script:OperationalMode -ne "manual" -or $script:SafetyState -ne "normal") {
        Write-Host "[DROP] remote requires operationalMode=manual and safetyState=normal"
        return
    }
    $linearValue = Get-JsonProperty $Message "linearSpeedCms"
    $angularValue = Get-JsonProperty $Message "angularSpeedRadps"
    if ($null -eq $linearValue -or $null -eq $angularValue) {
        Write-Host "[DROP] remote requires linearSpeedCms and angularSpeedRadps"
        return
    }
    try {
        $linear = [double]$linearValue
        $angular = [double]$angularValue
    } catch {
        Write-Host "[DROP] remote speed fields must be numeric"
        return
    }
    if ([double]::IsNaN($linear) -or [double]::IsInfinity($linear) -or
        [double]::IsNaN($angular) -or [double]::IsInfinity($angular) -or
        [Math]::Abs($linear) -gt 50.0 -or [Math]::Abs($angular) -gt 0.5) {
        Write-Host "[DROP] remote speed out of range"
        return
    }

    $durationMs = [int](Get-JsonProperty $Message "durationMs" 0)
    Write-RemoteMessage $linear $angular $durationMs
    $script:LinearSpeed = $linear
    $script:AngularSpeed = $angular
    $script:LastRemoteAt = Get-Date
}

function Handle-DownlinkLine {
    param([string]$Line)

    if ([string]::IsNullOrWhiteSpace($Line)) { return }
    $separator = $Line.IndexOf(" ")
    if ($separator -lt 1) { return }
    $topic = $Line.Substring(0, $separator)
    $json = $Line.Substring($separator + 1).Trim()
    try {
        $message = $json | ConvertFrom-Json -ErrorAction Stop
        if ($topic.EndsWith("/cmd")) { Handle-Cmd $json $message }
        elseif ($topic.EndsWith("/remote")) { Handle-Remote $message }
    } catch {
        Write-Host "[WARN] invalid downlink JSON: $($_.Exception.Message)"
    }
}

function Start-DownlinkSubscriber {
    $script:SubscriberOut = [System.IO.Path]::GetTempFileName()
    $script:SubscriberErr = [System.IO.Path]::GetTempFileName()
    $arguments = New-MqttClientArgs $context -VerboseSubscribe
    $arguments += @("-q", "1", "-t", "$($context.TopicPrefix)/cmd", "-t", "$($context.TopicPrefix)/remote")
    $process = Start-Process -FilePath $context.SubExe -ArgumentList $arguments `
        -RedirectStandardOutput $script:SubscriberOut -RedirectStandardError $script:SubscriberErr `
        -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 300
    if ($process.HasExited) {
        $errorText = Get-Content -LiteralPath $script:SubscriberErr -Raw -ErrorAction SilentlyContinue
        throw "mosquitto_sub exited early. $errorText"
    }
    $script:SubscriberOffset = 0
    return $process
}

function Read-NewSubscriberLines {
    $stream = [System.IO.File]::Open($script:SubscriberOut, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        [void]$stream.Seek($script:SubscriberOffset, [System.IO.SeekOrigin]::Begin)
        $reader = [System.IO.StreamReader]::new($stream)
        $text = $reader.ReadToEnd()
        $script:SubscriberOffset = $stream.Position
        if ([string]::IsNullOrWhiteSpace($text)) { return @() }
        return @($text -split "(`r`n|`n|`r)" | Where-Object { $_ -and $_.Trim() })
    } finally {
        $stream.Close()
    }
}

function Stop-DownlinkSubscriber {
    param($Process)
    if ($Process -and !$Process.HasExited) { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $script:SubscriberOut -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $script:SubscriberErr -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Robot manual-mode simulator"
Write-Host "Broker: $($context.HostName):$($context.Port)"
Write-Host "Username: $($context.Username)"
Write-Host "Device: $($context.ProductType)/$($context.DeviceId)"
Write-Host "Replies to: manual, auto, estop, clear_estop"
Write-Host "Logs remote speed messages. Press Ctrl+C to stop."
Write-Host ""

$subscriber = $null
try {
    $subscriber = Start-DownlinkSubscriber
    $startedAt = Get-Date
    $lastHeartbeatAt = [datetime]::MinValue
    $lastStatusAt = [datetime]::MinValue
    while ($RunSeconds -eq 0 -or ((Get-Date) - $startedAt).TotalSeconds -lt $RunSeconds) {
        $now = Get-Date
        if (($now - $lastHeartbeatAt).TotalMilliseconds -ge $HeartbeatIntervalMs) {
            Publish-Heartbeat
            $lastHeartbeatAt = $now
        }
        if (($now - $lastStatusAt).TotalMilliseconds -ge $StatusIntervalMs) {
            Publish-Status
            $lastStatusAt = $now
        }
        foreach ($line in @(Read-NewSubscriberLines)) { Handle-DownlinkLine $line }

        if ($null -ne $script:LastRemoteAt -and
            ((Get-Date) - $script:LastRemoteAt).TotalMilliseconds -ge 1000 -and
            ([Math]::Abs($script:LinearSpeed) -gt 0.01 -or [Math]::Abs($script:AngularSpeed) -gt 0.001)) {
            Write-Host "[WATCHDOG] no remote frame for 1s; speed reset to zero"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            Publish-Status
        }
        Start-Sleep -Milliseconds 50
    }
} finally {
    Stop-DownlinkSubscriber $subscriber
}
