Set-StrictMode -Version Latest

function Read-LocalProperties {
    param([string]$Path)

    $properties = @{}
    if (!(Test-Path -LiteralPath $Path)) { return $properties }

    foreach ($rawLine in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $line = $rawLine.Trim()
        if (!$line -or $line.StartsWith("#")) { continue }
        $separator = $line.IndexOf("=")
        if ($separator -le 0) { continue }
        $key = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim().Trim('"')
        $properties[$key] = $value
    }
    return $properties
}

function Get-LocalProperty {
    param(
        [hashtable]$Properties,
        [string[]]$Keys,
        [string]$Default = ""
    )

    foreach ($key in $Keys) {
        if ($Properties.ContainsKey($key) -and $Properties[$key]) {
            return $Properties[$key]
        }
    }
    return $Default
}

function Resolve-MqttExecutable {
    param(
        [string]$MosquittoDir,
        [string]$FileName,
        [string[]]$PathNames
    )

    if ($MosquittoDir) {
        $candidate = Join-Path $MosquittoDir $FileName
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    foreach ($name in $PathNames) {
        $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { return $command.Source }
    }
    throw "Cannot find $FileName. Configure mqtt.client.dir in local.properties or pass -MosquittoDir."
}

function New-RobotMqttContext {
    param(
        [string]$ScriptDirectory,
        [string]$MosquittoDir = "",
        [string]$LocalProperties = "local.properties",
        [string]$HostNameOverride = "",
        [string]$PortOverride = "",
        [string]$UsernameOverride = "",
        [string]$PasswordOverride = "",
        [string]$ProductTypeOverride = "",
        [string]$DeviceIdOverride = ""
    )

    $repoRoot = Resolve-Path (Join-Path $ScriptDirectory "..\..")
    if ($LocalProperties -eq "local.properties") {
        $LocalProperties = Join-Path $repoRoot "local.properties"
    }
    $properties = Read-LocalProperties $LocalProperties
    $resolvedMosquittoDir = if ($MosquittoDir) {
        $MosquittoDir
    } else {
        Get-LocalProperty $properties @("mqtt.client.dir", "mosquitto.dir") "C:\Program Files\Mosquitto"
    }

    $hostName = if ($HostNameOverride) { $HostNameOverride } else { Get-LocalProperty $properties @("mqtt.host") "127.0.0.1" }
    $port = if ($PortOverride) { $PortOverride } else { Get-LocalProperty $properties @("mqtt.port") "1883" }
    $username = if ($UsernameOverride) { $UsernameOverride } else { Get-LocalProperty $properties @("mqtt.robot.username", "mqtt.username") "" }
    $password = if ($PasswordOverride) { $PasswordOverride } else { Get-LocalProperty $properties @("mqtt.robot.password", "mqtt.password") "" }
    $productType = if ($ProductTypeOverride) { $ProductTypeOverride } else { Get-LocalProperty $properties @("mqtt.product_type") "crawler" }
    $deviceId = if ($DeviceIdOverride) { $DeviceIdOverride } else { Get-LocalProperty $properties @("mqtt.default_device_id") "crawler_00000001" }

    [pscustomobject]@{
        HostName = $hostName
        Port = $port
        Username = $username
        Password = $password
        ProductType = $productType
        DeviceId = $deviceId
        TopicPrefix = "device/$productType/$deviceId"
        PubExe = Resolve-MqttExecutable $resolvedMosquittoDir "mosquitto_pub.exe" @("mosquitto_pub.exe", "mosquitto_pub")
        SubExe = Resolve-MqttExecutable $resolvedMosquittoDir "mosquitto_sub.exe" @("mosquitto_sub.exe", "mosquitto_sub")
    }
}

function New-MqttClientArgs {
    param(
        [pscustomobject]$Context,
        [switch]$VerboseSubscribe
    )

    $arguments = @("-h", $Context.HostName, "-p", [string]$Context.Port)
    if ($Context.Username) { $arguments += @("-u", $Context.Username) }
    if ($Context.Password) { $arguments += @("-P", $Context.Password) }
    if ($VerboseSubscribe) { $arguments += "-v" }
    return $arguments
}

function New-RobotBasePayload {
    param([pscustomobject]$Context)

    [ordered]@{
        version = "1.0"
        deviceId = $Context.DeviceId
        productType = $Context.ProductType
        timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
    }
}

function ConvertTo-CompactJson {
    param([object]$Payload)
    $Payload | ConvertTo-Json -Compress -Depth 16
}

function Publish-MqttJson {
    param(
        [pscustomobject]$Context,
        [string]$Topic,
        [object]$Payload,
        [int]$Qos = 1
    )

    $payloadFile = [System.IO.Path]::GetTempFileName()
    try {
        $json = if ($Payload -is [string]) { $Payload } else { ConvertTo-CompactJson $Payload }
        [System.IO.File]::WriteAllText($payloadFile, $json, [System.Text.UTF8Encoding]::new($false))
        $arguments = New-MqttClientArgs $Context
        $arguments += @("-q", [string]$Qos, "-t", $Topic, "-f", $payloadFile)
        & $Context.PubExe @arguments | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "mosquitto_pub failed with exit code $LASTEXITCODE" }
    } finally {
        Remove-Item -LiteralPath $payloadFile -ErrorAction SilentlyContinue
    }
}

function Get-JsonProperty {
    param(
        [object]$Object,
        [string]$Name,
        [object]$Default = $null
    )

    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    return $property.Value
}

function Format-JsonText {
    param([string]$Json)
    try {
        return ($Json | ConvertFrom-Json -ErrorAction Stop | ConvertTo-Json -Depth 16)
    } catch {
        return $Json
    }
}
