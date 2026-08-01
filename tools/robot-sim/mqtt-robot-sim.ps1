param(
    [ValidateSet("interactive", "auto", "menu", "listen", "smoke")]
    [string]$Mode = "auto",
    [string]$MosquittoDir = "",
    [string]$LocalProperties = "local.properties",
    [string]$HostNameOverride = "",
    [string]$PortOverride = "",
    [string]$UsernameOverride = "",
    [string]$PasswordOverride = "",
    [string]$ProductTypeOverride = "",
    [string]$DeviceIdOverride = "",
    [string]$MapJsonUrl = "http://47.103.157.213/maps/crawler/crawler_00000001/map_2_v1.json",
    [ValidateSet("normal", "failed", "timeout")]
    [string]$NextCommandResult = "normal",
    [switch]$ListenOnly,
    [switch]$MenuOnly,
    [switch]$NoAutoAck
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..\..")
if ($LocalProperties -eq "local.properties") {
    $LocalProperties = Join-Path $RepoRoot "local.properties"
}

if ($ListenOnly) { $Mode = "listen" }
if ($MenuOnly) { $Mode = "menu" }

function Read-LocalProperties {
    param([string]$Path)
    $props = @{}
    if (!(Test-Path $Path)) { return $props }
    foreach ($rawLine in (Get-Content $Path -Encoding UTF8)) {
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) { continue }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) { continue }
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
    Write-Host "[ROBOT -> APP][HEARTBEAT] online=$Online"
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
    $payload["yawDeg"] = [Math]::Round($script:HeadingDeg, 1)
    $payload["pitchDeg"] = 3.2
    $payload["temperatureC"] = 36.8
    $payload["totalMileageM"] = 1280.0
    $payload["cleanedRows"] = 12
    $payload["pressureKpa"] = 102.4
    $payload["antiFallLeftM"] = 0.85
    $payload["antiFallRightM"] = 0.82
    $payload["missionId"] = if ($script:MissionId) { $script:MissionId } else { $null }
    $payload["taskKind"] = if ($script:TaskKind) { $script:TaskKind } else { $null }
    $payload["runState"] = $script:RunState
    $payload["operationalMode"] = $script:OperationalMode
    $payload["safetyState"] = $script:SafetyState
    $payload["phase"] = $script:MissionPhase
    $payload["activeAction"] = $script:ActiveAction
    $payload["waypointIndex"] = $script:WaypointIndex
    $payload["waypointCount"] = $script:WaypointCount
    $payload["errorCode"] = $script:MissionErrorCode
    $payload["errorRetryable"] = $script:MissionErrorRetryable
    $payload["errorSource"] = $script:MissionErrorSource
    $payload["errorMessage"] = $script:MissionErrorMessage
    Invoke-MqttPub "$TopicPrefix/status" (ConvertTo-CompactJson $payload)
    Write-Host "[ROBOT -> APP][STATUS] run=$script:RunState mode=$script:OperationalMode safety=$script:SafetyState mission=$script:MissionId linear=$script:LinearSpeed angular=$script:AngularSpeed"
}

function Publish-Pose {
    $payload = New-BasePayload
    $payload["mapId"] = $script:MapId
    $payload["mapVersion"] = $script:MapVersion
    $payload["blockId"] = $script:CurrentBlockId
    $payload["cellId"] = $script:CurrentCellId
    $payload["cellRow"] = $script:CellRow
    $payload["cellCol"] = $script:CellCol
    $payload["innerRow"] = $script:InnerRow
    $payload["innerCol"] = $script:InnerCol
    $payload["headingCode"] = $script:HeadingCode
    $payload["heading"] = $script:HeadingName
    Invoke-MqttPub "$TopicPrefix/pose" (ConvertTo-CompactJson $payload)
    Write-Host "[ROBOT -> APP][POSE] map=$script:MapId block=$script:CurrentBlockId cell=$script:CurrentCellId inner=$script:InnerRow,$script:InnerCol heading=$script:HeadingName"
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
    Write-Host "[ROBOT -> APP][ACK] cmd=$Cmd cmdId=$CmdId status=$AckStatus error=$ErrorCode"
}

function Publish-MapNotice {
    param([string]$Url = "")
    if ([string]::IsNullOrWhiteSpace($Url)) {
        Write-Host "[SKIP] map notice because -MapJsonUrl is empty; App can keep the local demo map"
        return
    }
    $payload = New-BasePayload
    $payload["mapId"] = $script:MapId
    $payload["mapName"] = "simulated-map"
    $payload["mapVersion"] = $script:MapVersion
    $payload["mapJsonUrl"] = $Url
    $payload["fileSizeBytes"] = $null
    $payload["checksum"] = $null
    Invoke-MqttPub "$TopicPrefix/map" (ConvertTo-CompactJson $payload)
    Write-Host "[ROBOT -> APP][MAP] id=$script:MapId version=$script:MapVersion url='$Url'"
}

function Reset-MissionError {
    $script:MissionErrorCode = 0
    $script:MissionErrorRetryable = $false
    $script:MissionErrorSource = ""
    $script:MissionErrorMessage = ""
}

function Ensure-SimMissionId {
    if (!$script:MissionId) {
        $script:MissionId = "mission-sim-001"
    }
}

function Set-SimScenario {
    param(
        [ValidateSet("idle", "running", "paused", "succeeded", "failed", "low_battery", "fault", "estop", "normal")]
        [string]$Name
    )

    switch ($Name) {
        "idle" {
            $script:MissionId = ""
            $script:TaskKind = ""
            $script:RunState = "idle"
            $script:OperationalMode = "auto"
            $script:SafetyState = "normal"
            $script:MissionPhase = "none"
            $script:ActiveAction = ""
            $script:WaypointIndex = 0
            $script:WaypointCount = 0
            $script:WorkStatus = "stopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "auto"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            $script:Battery = 88
            Reset-MissionError
        }
        "running" {
            Ensure-SimMissionId
            $script:TaskKind = "coverage"
            $script:RunState = "running"
            $script:OperationalMode = "auto"
            $script:SafetyState = "normal"
            $script:MissionPhase = "executing"
            $script:ActiveAction = "cross_panel"
            $script:WaypointIndex = [Math]::Max(1, $script:WaypointIndex)
            $script:WaypointCount = [Math]::Max(6, $script:WaypointCount)
            $script:WorkStatus = "running"
            $script:MovementStatus = "moving"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "auto"
            $script:LinearSpeed = 12
            $script:AngularSpeed = 0
            Reset-MissionError
        }
        "paused" {
            Ensure-SimMissionId
            $script:TaskKind = "coverage"
            $script:RunState = "paused"
            $script:OperationalMode = "auto"
            $script:SafetyState = "normal"
            $script:MissionPhase = "executing"
            $script:ActiveAction = ""
            $script:WaypointIndex = [Math]::Max(1, $script:WaypointIndex)
            $script:WaypointCount = [Math]::Max(6, $script:WaypointCount)
            $script:WorkStatus = "stopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "auto"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            Reset-MissionError
        }
        "succeeded" {
            Ensure-SimMissionId
            $script:TaskKind = "coverage"
            $script:RunState = "succeeded"
            $script:OperationalMode = "auto"
            $script:SafetyState = "normal"
            $script:MissionPhase = "none"
            $script:ActiveAction = ""
            $script:WaypointIndex = [Math]::Max(6, $script:WaypointCount)
            $script:WaypointCount = [Math]::Max(6, $script:WaypointCount)
            $script:WorkStatus = "completed"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "auto"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            Reset-MissionError
        }
        "failed" {
            Ensure-SimMissionId
            $script:TaskKind = "coverage"
            $script:RunState = "failed"
            $script:OperationalMode = "auto"
            $script:SafetyState = "normal"
            $script:MissionPhase = "none"
            $script:ActiveAction = ""
            $script:WorkStatus = "fault"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "fault"
            $script:ControlMode = "auto"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            $script:MissionErrorCode = 1001
            $script:MissionErrorRetryable = $true
            $script:MissionErrorSource = "mission_planner"
            $script:MissionErrorMessage = "simulated mission failure"
        }
        "low_battery" {
            $script:SafetyState = "low_battery"
            $script:Battery = 12
            $script:WorkStatus = "stopped"
            $script:MovementStatus = "stopped"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            Reset-MissionError
        }
        "fault" {
            $script:SafetyState = "fault"
            $script:WorkStatus = "fault"
            $script:MovementStatus = "blocked"
            $script:DeviceStatus = "fault"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            $script:MissionErrorCode = 2001
            $script:MissionErrorRetryable = $false
            $script:MissionErrorSource = "robot_sim"
            $script:MissionErrorMessage = "simulated safety fault"
        }
        "estop" {
            $script:SafetyState = "estop"
            $script:WorkStatus = "estopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "estop"
            $script:ActiveAction = ""
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            Reset-MissionError
        }
        "normal" {
            $script:SafetyState = "normal"
            $script:Battery = [Math]::Max(50, $script:Battery)
            $script:DeviceStatus = "normal"
            $script:MovementStatus = "stopped"
            $script:ControlMode = $script:OperationalMode
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
            Reset-MissionError
        }
    }
    Write-Host "[SCENARIO] $Name"
    Publish-Status
}

function Move-SimPose {
    $script:InnerCol = ($script:InnerCol + 1) % 6
    if ($script:InnerCol -eq 0) {
        $script:CellCol = ($script:CellCol + 1) % 3
        $script:CurrentCellId = 1 + $script:CellCol
    }
    Publish-Pose
}

function Get-RemoteDirection {
    param(
        [double]$Linear,
        [double]$Angular
    )
    if ([Math]::Abs($Linear) -le 0.01 -and [Math]::Abs($Angular) -le 0.001) { return "STOP" }
    if ([Math]::Abs($Linear) -gt [Math]::Abs($Angular)) {
        return $(if ($Linear -gt 0) { "FORWARD" } else { "BACKWARD" })
    }
    return $(if ($Angular -gt 0) { "LEFT" } else { "RIGHT" })
}

function Write-RemoteObservation {
    param(
        [double]$Linear,
        [double]$Angular,
        [int]$DurationMs
    )
    $now = Get-Date
    $changed =
        $null -eq $script:LastObservedLinear -or
        [Math]::Abs($Linear - $script:LastObservedLinear) -gt 0.001 -or
        [Math]::Abs($Angular - $script:LastObservedAngular) -gt 0.0001
    $periodic = $null -eq $script:LastRemoteObservationAt -or
        ($now - $script:LastRemoteObservationAt).TotalMilliseconds -ge 1000
    if ($changed -or $periodic) {
        $direction = Get-RemoteDirection $Linear $Angular
        Write-Host "[APP -> ROBOT][REMOTE] $direction linear=$Linear cm/s angular=$Angular rad/s duration=${DurationMs}ms"
        $script:LastObservedLinear = $Linear
        $script:LastObservedAngular = $Angular
        $script:LastRemoteObservationAt = $now
    }
}

function Apply-Cmd {
    param(
        [string]$Cmd,
        [object]$Params
    )
    $targetMissionId = if ($Params -and $Params.targetMissionId) { [string]$Params.targetMissionId } else { "" }
    if ($Cmd -in @("stop", "pause", "resume", "replan")) {
        if (!$script:MissionId -or $targetMissionId -ne $script:MissionId) {
            return @{ Success = $false; ErrorCode = "MISSION_NOT_FOUND" }
        }
    }
    switch ($Cmd) {
        "start" {
            if (!$Params -or [string]$Params.taskKind -ne "coverage" -or !$Params.coverage) {
                return @{ Success = $false; ErrorCode = "MISSION_INVALID_REQUEST" }
            }
            $coverage = $Params.coverage
            $coverageFields = @($coverage.PSObject.Properties.Name)
            if (
                "mapId" -notin $coverageFields -or
                "mapVersion" -notin $coverageFields -or
                "useCurrentPose" -notin $coverageFields -or
                "targetBlockIds" -notin $coverageFields -or
                "globalPlan" -notin $coverageFields
            ) {
                return @{ Success = $false; ErrorCode = "MISSION_INVALID_REQUEST" }
            }
            $mapId = [long]$coverage.mapId
            $mapVersion = [long]$coverage.mapVersion
            if ($mapId -lt 0 -or $mapId -gt 4294967295 -or $mapVersion -lt 0 -or $mapVersion -gt 4294967295) {
                return @{ Success = $false; ErrorCode = "MISSION_INVALID_REQUEST" }
            }
            $targetIds = @($coverage.targetBlockIds)
            if ($targetIds.Count -eq 0) {
                return @{ Success = $false; ErrorCode = "MISSION_INVALID_REQUEST" }
            }
            $seenTargets = @{}
            foreach ($target in $targetIds) {
                $targetId = [long]$target
                if ($targetId -le 0 -or $seenTargets.ContainsKey($targetId)) {
                    return @{ Success = $false; ErrorCode = "MISSION_INVALID_REQUEST" }
                }
                $seenTargets[$targetId] = $true
            }
            if (![bool]$coverage.useCurrentPose) {
                if (!$coverage.start) {
                    return @{ Success = $false; ErrorCode = "MISSION_INVALID_REQUEST" }
                }
                $startFields = @($coverage.start.PSObject.Properties.Name)
                foreach ($required in @("blockId", "cellRow", "cellCol", "innerRow", "innerCol", "heading")) {
                    if ($required -notin $startFields) {
                        return @{ Success = $false; ErrorCode = "MISSION_INVALID_REQUEST" }
                    }
                }
                $heading = [int]$coverage.start.heading
                if ($heading -lt 0 -or $heading -gt 3) {
                    return @{ Success = $false; ErrorCode = "MISSION_INVALID_REQUEST" }
                }
            }
            $script:MissionId = "mission-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
            $script:TaskKind = "coverage"
            $script:RunState = "running"
            $script:MissionPhase = "executing"
            $script:ActiveAction = "cross_panel"
            $script:WaypointIndex = 0
            $script:WaypointCount = $targetIds.Count
            $script:OperationalMode = "auto"
            $script:SafetyState = "normal"
            $script:WorkStatus = "running"
            $script:MovementStatus = "moving"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "auto"
            $script:LinearSpeed = 12
            $script:AngularSpeed = 0
            $script:HeadingCode = 0
            $script:HeadingName = "block_u_positive"
        }
        "stop" {
            $script:RunState = "canceled"
            $script:MissionPhase = "none"
            $script:ActiveAction = ""
            $script:WorkStatus = "stopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "auto"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "pause" {
            if ($script:RunState -notin @("starting", "running")) {
                return @{ Success = $false; ErrorCode = "MISSION_ILLEGAL_STATE" }
            }
            $script:RunState = "paused"
            $script:ActiveAction = ""
            $script:MovementStatus = "stopped"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "resume" {
            if ($script:RunState -ne "paused") {
                return @{ Success = $false; ErrorCode = "MISSION_ILLEGAL_STATE" }
            }
            $script:RunState = "running"
            $script:MissionPhase = "executing"
            $script:ActiveAction = "cross_panel"
            $script:MovementStatus = "moving"
            $script:LinearSpeed = 12
        }
        "replan" {
            if ($script:TaskKind -ne "coverage" -or $script:RunState -notin @("starting", "running", "paused")) {
                return @{ Success = $false; ErrorCode = "MISSION_ILLEGAL_STATE" }
            }
            $script:MissionPhase = "planning"
            $script:ActiveAction = ""
        }
        "manual" {
            if ($script:SafetyState -ne "normal") {
                return @{ Success = $false; ErrorCode = "MISSION_ILLEGAL_STATE" }
            }
            if ($script:RunState -in @("starting", "running", "paused")) {
                $script:RunState = "canceled"
                $script:MissionPhase = "none"
            }
            $script:ActiveAction = "remote"
            $script:OperationalMode = "manual"
            $script:ControlMode = "manual"
            $script:WorkStatus = "stopped"
            $script:MovementStatus = "stopped"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "auto" {
            $script:OperationalMode = "auto"
            $script:ControlMode = "auto"
            $script:ActiveAction = ""
            $script:MovementStatus = "stopped"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "estop" {
            $script:SafetyState = "estop"
            $script:ActiveAction = ""
            $script:WorkStatus = "estopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "estop"
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
        "clear_estop" {
            if ($script:SafetyState -ne "estop") {
                return @{ Success = $false; ErrorCode = "MISSION_ILLEGAL_STATE" }
            }
            $script:SafetyState = "clearing_estop"
            $script:ClearEstopAt = (Get-Date).AddSeconds(1)
            $script:WorkStatus = "stopped"
            $script:MovementStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = $script:OperationalMode
            $script:LinearSpeed = 0
            $script:AngularSpeed = 0
        }
    }
    return @{ Success = $true; ErrorCode = "" }
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
    try {
        $msg = $json | ConvertFrom-Json
        if ($script:HeartbeatEnabled -eq $false) {
            Write-Host "[DROP] simulator is offline: $topic"
            return
        }
        if ($topic.EndsWith("/cmd")) {
            $cmdId = [string]$msg.cmdId
            $cmd = [string]$msg.cmd
            if (!$cmdId) { $cmdId = "missing_cmd_id" }
            if (!$cmd) { $cmd = "unknown" }
            Write-Host "[APP -> ROBOT][CMD] cmd=$cmd cmdId=$cmdId payload=$json"
            if ($script:ProcessedCommands.ContainsKey($cmdId)) {
                $cached = $script:ProcessedCommands[$cmdId]
                if (!$NoAutoAck) {
                    if ($cached.Payload -ne $json) {
                        Publish-CmdAck -CmdId $cmdId -Cmd $cmd -AckStatus "failed" -Message "cmdId reused with different payload" -ErrorCode "MISSION_INVALID_REQUEST"
                    } else {
                        Publish-CmdAck -CmdId $cmdId -Cmd $cached.Cmd -AckStatus $cached.AckStatus -Message $cached.Message -ErrorCode $cached.ErrorCode
                    }
                }
                return
            }
            if ($cmd -in @("start", "stop", "pause", "resume", "replan", "manual", "auto", "estop", "clear_estop")) {
                $dropAck = $false
                if ($script:NextCommandResult -eq "failed") {
                    $result = @{ Success = $false; ErrorCode = "MISSION_REJECTED" }
                    Write-Host "[SIM] next command forced to fail with MISSION_REJECTED"
                } else {
                    $result = Apply-Cmd -Cmd $cmd -Params $msg.params
                }
                if ($script:NextCommandResult -eq "timeout") {
                    $dropAck = $true
                    Write-Host "[SIM] ACK intentionally dropped once; retrying the same cmdId will return the cached result"
                }
                $script:NextCommandResult = "normal"
                $ackStatus = if ($result.Success) { "success" } else { "failed" }
                $ackMessage = if ($result.Success) { "accepted" } else { "rejected" }
                $script:ProcessedCommands[$cmdId] = @{
                    Payload = $json
                    Cmd = $cmd
                    AckStatus = $ackStatus
                    Message = $ackMessage
                    ErrorCode = $result.ErrorCode
                }
                if (!$NoAutoAck -and !$dropAck) {
                    if ($result.Success) {
                        Publish-CmdAck -CmdId $cmdId -Cmd $cmd -AckStatus "success" -Message "accepted"
                    } else {
                        Publish-CmdAck -CmdId $cmdId -Cmd $cmd -AckStatus "failed" -Message "rejected" -ErrorCode $result.ErrorCode
                    }
                }
                Publish-Status
                Publish-Pose
            } else {
                if (!$NoAutoAck) {
                    Publish-CmdAck -CmdId $cmdId -Cmd $cmd -AckStatus "failed" -Message "unsupported cmd" -ErrorCode "SIM_UNSUPPORTED_CMD"
                }
            }
        } elseif ($topic.EndsWith("/remote")) {
            if ($script:OperationalMode -ne "manual" -or $script:SafetyState -ne "normal") {
                Write-Host "[DROP] remote requires operationalMode=manual and safetyState=normal"
                return
            }
            if ($null -eq $msg.linearSpeedCms -or $null -eq $msg.angularSpeedRadps) {
                Write-Host "[DROP] remote requires linearSpeedCms and angularSpeedRadps"
                return
            }
            try {
                $linearSpeed = [double]$msg.linearSpeedCms
                $angularSpeed = [double]$msg.angularSpeedRadps
            } catch {
                Write-Host "[DROP] remote speed fields must be numeric"
                return
            }
            $invalidNumber =
                [double]::IsNaN($linearSpeed) -or [double]::IsInfinity($linearSpeed) -or
                [double]::IsNaN($angularSpeed) -or [double]::IsInfinity($angularSpeed)
            if ($invalidNumber -or [Math]::Abs($linearSpeed) -gt 50.0 -or [Math]::Abs($angularSpeed) -gt 0.5) {
                Write-Host "[DROP] remote speed out of range: linear [-50,50] cm/s, angular [-0.5,0.5] rad/s"
                return
            }
            $durationMs = if ($null -ne $msg.durationMs) { [int]$msg.durationMs } else { 0 }
            Write-RemoteObservation -Linear $linearSpeed -Angular $angularSpeed -DurationMs $durationMs
            $script:LinearSpeed = $linearSpeed
            $script:AngularSpeed = $angularSpeed
            $script:LastRemoteAt = Get-Date
            $moving = [Math]::Abs($script:LinearSpeed) -gt 0.01 -or [Math]::Abs($script:AngularSpeed) -gt 0.01
            $script:WorkStatus = "stopped"
            $script:DeviceStatus = "normal"
            $script:ControlMode = "manual"
            $script:MovementStatus = if ($moving) { "moving" } else { "stopped" }
            $script:HeadingDeg = ($script:HeadingDeg + $script:AngularSpeed * 8.0) % 360.0
            if ([Math]::Abs($script:LinearSpeed) -gt 0.01) {
                $script:InnerCol = ($script:InnerCol + 1) % 6
            }
            if ($script:AngularSpeed -gt 0.01) {
                $script:HeadingCode = 0
                $script:HeadingName = "block_u_positive"
            } elseif ($script:AngularSpeed -lt -0.01) {
                $script:HeadingCode = 2
                $script:HeadingName = "block_v_positive"
            }
            $now = Get-Date
            $feedbackChanged =
                $null -eq $script:LastRemoteFeedbackLinear -or
                [Math]::Abs($linearSpeed - $script:LastRemoteFeedbackLinear) -gt 0.001 -or
                [Math]::Abs($angularSpeed - $script:LastRemoteFeedbackAngular) -gt 0.0001
            $feedbackDue = $null -eq $script:LastRemoteFeedbackAt -or
                ($now - $script:LastRemoteFeedbackAt).TotalMilliseconds -ge 500
            if ($feedbackChanged -or $feedbackDue) {
                Publish-Status
                Publish-Pose
                $script:LastRemoteFeedbackLinear = $linearSpeed
                $script:LastRemoteFeedbackAngular = $angularSpeed
                $script:LastRemoteFeedbackAt = $now
            }
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

function Format-JsonText {
    param([string]$Json)

    $builder = [System.Text.StringBuilder]::new()
    $indent = 0
    $inString = $false
    $escaped = $false
    $newLine = [Environment]::NewLine

    for ($index = 0; $index -lt $Json.Length; $index++) {
        $char = $Json[$index]
        if ($inString) {
            [void]$builder.Append($char)
            if ($escaped) {
                $escaped = $false
            } elseif ($char -eq "\") {
                $escaped = $true
            } elseif ($char -eq '"') {
                $inString = $false
            }
            continue
        }

        if ($char -eq '"') {
            $inString = $true
            [void]$builder.Append($char)
            continue
        }
        if ([char]::IsWhiteSpace($char)) { continue }

        switch ($char) {
            { $_ -eq "{" -or $_ -eq "[" } {
                [void]$builder.Append($char)
                $next = $index + 1
                while ($next -lt $Json.Length -and [char]::IsWhiteSpace($Json[$next])) {
                    $next++
                }
                $closing = if ($char -eq "{") { "}" } else { "]" }
                if ($next -lt $Json.Length -and $Json[$next] -eq $closing) {
                    [void]$builder.Append($closing)
                    $index = $next
                } else {
                    $indent++
                    [void]$builder.Append($newLine)
                    [void]$builder.Append("  " * $indent)
                }
            }
            { $_ -eq "}" -or $_ -eq "]" } {
                $indent = [Math]::Max(0, $indent - 1)
                [void]$builder.Append($newLine)
                [void]$builder.Append("  " * $indent)
                [void]$builder.Append($char)
            }
            "," {
                [void]$builder.Append(",")
                [void]$builder.Append($newLine)
                [void]$builder.Append("  " * $indent)
            }
            ":" {
                [void]$builder.Append(": ")
            }
            default {
                [void]$builder.Append($char)
            }
        }
    }
    return $builder.ToString()
}

function Run-ListenOnly {
    Write-Host "Listening for App messages as standard 2-space JSON. Press Ctrl+C to exit."
    $args = New-MqttArgs -VerboseSubscribe
    $args += @("-q", "1", "-t", "$TopicPrefix/cmd", "-t", "$TopicPrefix/remote")
    & $SubExe @args | ForEach-Object {
        $line = [string]$_
        $separator = $line.IndexOf(" ")
        if ($separator -lt 1) {
            Write-Host $line
            return
        }

        $topic = $line.Substring(0, $separator)
        $payload = $line.Substring($separator + 1).Trim()
        Write-Host ""
        Write-Host "[$topic]" -ForegroundColor Cyan
        try {
            [void]($payload | ConvertFrom-Json -ErrorAction Stop)
            Write-Host (Format-JsonText $payload)
        } catch {
            Write-Host $payload
        }
    }
    exit $LASTEXITCODE
}

function Run-SmokeOnce {
    Write-Host "Publishing one MQTT smoke-test sequence for the App main pages."
    Publish-Heartbeat $true
    Publish-Status
    Publish-MapNotice $MapJsonUrl
    Publish-Pose
    Start-Sleep -Milliseconds 500

    $script:WorkStatus = "running"; $script:MovementStatus = "moving"; $script:DeviceStatus = "normal"; $script:ControlMode = "auto"; $script:LinearSpeed = 12; $script:AngularSpeed = 0
    $script:CellCol = 2; $script:InnerCol = 1; $script:HeadingCode = 0; $script:HeadingName = "block_u_positive"
    Publish-Status
    Publish-Pose
    Start-Sleep -Milliseconds 500

    $script:WorkStatus = "stopped"; $script:MovementStatus = "stopped"; $script:DeviceStatus = "normal"; $script:ControlMode = "manual"; $script:LinearSpeed = 0; $script:AngularSpeed = 0
    $script:CellCol = 3; $script:InnerCol = 2; $script:HeadingCode = 0; $script:HeadingName = "block_u_positive"
    Publish-Status
    Publish-Pose
    Start-Sleep -Milliseconds 500

    $script:WorkStatus = "estopped"; $script:MovementStatus = "stopped"; $script:DeviceStatus = "normal"; $script:ControlMode = "estop"; $script:LinearSpeed = 0; $script:AngularSpeed = 0
    Publish-Status
    Publish-Pose
    Start-Sleep -Milliseconds 500

    $script:WorkStatus = "fault"; $script:MovementStatus = "blocked"; $script:DeviceStatus = "fault"; $script:ControlMode = "auto"; $script:LinearSpeed = 0; $script:AngularSpeed = 0
    Publish-Status
    Publish-Pose
    Start-Sleep -Milliseconds 500

    $script:WorkStatus = "stopped"; $script:MovementStatus = "stopped"; $script:DeviceStatus = "normal"; $script:ControlMode = "manual"; $script:LinearSpeed = 0; $script:AngularSpeed = 0
    Publish-Status
    Publish-Pose
    Write-Host "Smoke sequence complete. Use Mode=auto for interactive command and remote-control testing."
}

function Run-AutoRobot {
    Write-Host "Auto robot is running. Press Ctrl+C to exit."
    Write-Host "It publishes heartbeat/status and automatically acks App cmd with the same cmdId."
    Write-Host ""
    $sub = Start-DownlinkSubscriber
    try {
        $lastHeartbeat = [DateTime]::MinValue
        $lastStatus = [DateTime]::MinValue
        $lastMap = [DateTime]::MinValue
        $lastPose = [DateTime]::MinValue
        while ($true) {
            foreach ($line in Read-NewSubscriberLines) {
                Handle-DownlinkLine $line
            }
            $now = Get-Date
            if ($script:ClearEstopAt -and $now -ge $script:ClearEstopAt) {
                $script:SafetyState = "normal"
                $script:ClearEstopAt = $null
                Publish-Status
            }
            if (
                $script:LastRemoteAt -and
                ([Math]::Abs($script:LinearSpeed) -gt 0.01 -or [Math]::Abs($script:AngularSpeed) -gt 0.001) -and
                ($now - $script:LastRemoteAt).TotalMilliseconds -ge 1000
            ) {
                $script:LinearSpeed = 0
                $script:AngularSpeed = 0
                $script:MovementStatus = "stopped"
                Write-Host "[WATCHDOG] no remote frame for 1000ms; simulated Robot stopped"
                Publish-Status
            }
            if (($now - $lastHeartbeat).TotalMilliseconds -ge 1000) {
                Publish-Heartbeat $true
                $lastHeartbeat = $now
            }
            if (($now - $lastStatus).TotalMilliseconds -ge 1500) {
                Publish-Status
                $lastStatus = $now
            }
            if (($now - $lastMap).TotalMilliseconds -ge 10000) {
                Publish-MapNotice $MapJsonUrl
                $lastMap = $now
            }
            if (($now - $lastPose).TotalMilliseconds -ge 1000) {
                Publish-Pose
                $lastPose = $now
            }
            Start-Sleep -Milliseconds 250
        }
    } finally {
        Stop-DownlinkSubscriber $sub
    }
}

function Show-InteractiveHelp {
    Write-Host ""
    Write-Host "================ Four-page manual test controls ================"
    Write-Host "  1  idle / auto / normal       2  running coverage mission"
    Write-Host "  3  paused mission             4  succeeded mission"
    Write-Host "  5  failed mission             6  low battery"
    Write-Host "  7  safety fault               8  emergency stop"
    Write-Host "  9  restore safety normal"
    Write-Host "  M  publish map notice         P  move/publish one pose"
    Write-Host "  O  toggle robot online/offline"
    Write-Host "  F  force next cmd ACK failed  T  drop next ACK once (timeout/retry)"
    Write-Host "  S  publish status now         H  show this help"
    Write-Host "  Q  quit"
    Write-Host "==============================================================="
    Write-Host "App cmd/remote and simulator ACK/status/pose are printed below."
    Write-Host ""
}

function Invoke-InteractiveKey {
    param([string]$Key)
    switch ($Key.ToLowerInvariant()) {
        "1" { Set-SimScenario "idle" }
        "2" { Set-SimScenario "running" }
        "3" { Set-SimScenario "paused" }
        "4" { Set-SimScenario "succeeded" }
        "5" { Set-SimScenario "failed" }
        "6" { Set-SimScenario "low_battery" }
        "7" { Set-SimScenario "fault" }
        "8" { Set-SimScenario "estop" }
        "9" { Set-SimScenario "normal" }
        "m" { Publish-MapNotice $MapJsonUrl }
        "p" { Move-SimPose }
        "o" {
            if ($script:HeartbeatEnabled) {
                Publish-Heartbeat $false
                $script:HeartbeatEnabled = $false
                Write-Host "[SCENARIO] simulator offline; heartbeat/status/pose and command feedback paused"
            } else {
                $script:HeartbeatEnabled = $true
                Publish-Heartbeat $true
                Publish-Status
                Publish-Pose
                Write-Host "[SCENARIO] simulator online"
            }
        }
        "f" {
            $script:NextCommandResult = "failed"
            Write-Host "[ARMED] the next new cmd will return ackStatus=failed / MISSION_REJECTED"
        }
        "t" {
            $script:NextCommandResult = "timeout"
            Write-Host "[ARMED] the next new cmd ACK will be dropped once; App retry receives cached ACK"
        }
        "s" { Publish-Status }
        "h" { Show-InteractiveHelp }
        "q" { return $false }
        default { Write-Host "[INFO] unknown key '$Key'; press H for help" }
    }
    return $true
}

function Run-InteractiveRobot {
    Write-Host "Interactive robot is running. Keep this terminal focused and press H for controls."
    Write-Host "Use the App manually; every cmd/remote message and Robot feedback is shown here."
    Show-InteractiveHelp
    $sub = Start-DownlinkSubscriber
    try {
        $lastHeartbeat = [DateTime]::MinValue
        $lastStatus = [DateTime]::MinValue
        $lastMap = [DateTime]::MinValue
        $lastPose = [DateTime]::MinValue
        while ($true) {
            foreach ($line in Read-NewSubscriberLines) {
                Handle-DownlinkLine $line
            }

            $now = Get-Date
            if ($script:HeartbeatEnabled) {
                if ($script:ClearEstopAt -and $now -ge $script:ClearEstopAt) {
                    $script:SafetyState = "normal"
                    $script:ClearEstopAt = $null
                    Publish-Status
                }
                if (
                    $script:LastRemoteAt -and
                    ([Math]::Abs($script:LinearSpeed) -gt 0.01 -or [Math]::Abs($script:AngularSpeed) -gt 0.001) -and
                    ($now - $script:LastRemoteAt).TotalMilliseconds -ge 1000
                ) {
                    $script:LinearSpeed = 0
                    $script:AngularSpeed = 0
                    $script:MovementStatus = "stopped"
                    Write-Host "[WATCHDOG] no remote frame for 1000ms; simulated Robot stopped"
                    Publish-Status
                }
                if (($now - $lastHeartbeat).TotalMilliseconds -ge 1000) {
                    Publish-Heartbeat $true
                    $lastHeartbeat = $now
                }
                if (($now - $lastStatus).TotalMilliseconds -ge 1500) {
                    Publish-Status
                    $lastStatus = $now
                }
                if (($now - $lastMap).TotalMilliseconds -ge 10000) {
                    Publish-MapNotice $MapJsonUrl
                    $lastMap = $now
                }
                if (($now - $lastPose).TotalMilliseconds -ge 1000) {
                    Publish-Pose
                    $lastPose = $now
                }
            }

            if ([Console]::KeyAvailable) {
                $key = [Console]::ReadKey($true).KeyChar.ToString()
                if (!(Invoke-InteractiveKey $key)) { break }
            }
            Start-Sleep -Milliseconds 50
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
        Write-Host "9. Pose update"
        Write-Host "10. Map notice with -MapJsonUrl"
        Write-Host "11. Smoke sequence for App main pages"
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
            "9" { Publish-Pose }
            "10" { Publish-MapNotice $MapJsonUrl }
            "11" { Run-SmokeOnce }
            "0" { break }
            default { Write-Host "Unknown choice" }
        }
    }
}

$props = Read-LocalProperties $LocalProperties
$MosquittoDir = if ($MosquittoDir) {
    $MosquittoDir
} else {
    Get-Prop $props @("mqtt.client.dir", "mosquitto.dir") "C:\Program Files\Mosquitto"
}
$HostName = if ($HostNameOverride) { $HostNameOverride } else { Get-Prop $props @("mqtt.host") "47.103.157.213" }
$Port = if ($PortOverride) { $PortOverride } else { Get-Prop $props @("mqtt.port") "1883" }
$Username = if ($UsernameOverride) { $UsernameOverride } else { Get-Prop $props @("mqtt.robot.username", "mqtt.username") "app_user_001" }
$Password = if ($PasswordOverride) { $PasswordOverride } else { Get-Prop $props @("mqtt.robot.password", "mqtt.password") "" }
$ProductType = if ($ProductTypeOverride) { $ProductTypeOverride } else { Get-Prop $props @("mqtt.product_type") "crawler" }
$DeviceId = if ($DeviceIdOverride) { $DeviceIdOverride } else { Get-Prop $props @("mqtt.default_device_id") "crawler_00000001" }

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

$PubExe = Resolve-MosquittoExe "mosquitto_pub.exe" @("mosquitto_pub.exe", "mosquitto_pub")
$SubExe = Resolve-MosquittoExe "mosquitto_sub.exe" @("mosquitto_sub.exe", "mosquitto_sub")

$TopicPrefix = "device/$ProductType/$DeviceId"

$script:WorkStatus = "stopped"
$script:MovementStatus = "stopped"
$script:DeviceStatus = "normal"
$script:MissionId = ""
$script:TaskKind = ""
$script:RunState = "idle"
$script:OperationalMode = "auto"
$script:SafetyState = "normal"
$script:MissionPhase = "none"
$script:ActiveAction = ""
$script:WaypointIndex = 0
$script:WaypointCount = 0
$script:MissionErrorCode = 0
$script:MissionErrorRetryable = $false
$script:MissionErrorSource = ""
$script:MissionErrorMessage = ""
$script:ClearEstopAt = $null
$script:ProcessedCommands = @{}
$script:NextCommandResult = $NextCommandResult
$script:HeartbeatEnabled = $true
$script:ControlMode = "auto"
$script:Battery = 88.0
$script:LinearSpeed = 0.0
$script:AngularSpeed = 0.0
$script:LastRemoteAt = $null
$script:LastObservedLinear = $null
$script:LastObservedAngular = $null
$script:LastRemoteObservationAt = $null
$script:LastRemoteFeedbackLinear = $null
$script:LastRemoteFeedbackAngular = $null
$script:LastRemoteFeedbackAt = $null
$script:MapId = 2
$script:MapVersion = 1
$script:CurrentBlockId = 1
$script:CurrentCellId = 2
$script:CellRow = 0
$script:CellCol = 1
$script:InnerRow = 0
$script:InnerCol = 0
$script:HeadingDeg = 0.0
$script:HeadingCode = 0
$script:HeadingName = "block_u_positive"

Write-Host ""
Write-Host "Robot MQTT simulator"
Write-Host "Mode: $Mode"
Write-Host "Broker: ${HostName}:$Port"
Write-Host "Username: $Username"
Write-Host "Device: $ProductType/$DeviceId"
Write-Host "Topics: $TopicPrefix/*"
Write-Host ""

switch ($Mode) {
    "interactive" { Run-InteractiveRobot }
    "listen" { Run-ListenOnly }
    "menu" { Run-Menu }
    "smoke" { Run-SmokeOnce }
    "auto" { Run-AutoRobot }
}
