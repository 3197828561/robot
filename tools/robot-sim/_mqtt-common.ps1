Set-StrictMode -Version Latest

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
    throw "Cannot find $FileName. Configure MOSQUITTO_DIR, add Mosquitto to PATH, or pass -MosquittoDir."
}

function New-RobotMqttContext {
    param(
        [string]$ScriptDirectory,
        [string]$MosquittoDir = "",
        [string]$DeviceConfig = "",
        [string]$HostNameOverride = "",
        [string]$PortOverride = "",
        [string]$UsernameOverride = "",
        [string]$PasswordOverride = "",
        [string]$ProductTypeOverride = "",
        [string]$DeviceIdOverride = ""
    )

    $repoRoot = Resolve-Path (Join-Path $ScriptDirectory "..\..")
    if (!$DeviceConfig) {
        $DeviceConfig = Join-Path $repoRoot "..\import-message\robot-handoff\robot-device-secrets.json"
    }

    $config = $null
    if (Test-Path -LiteralPath $DeviceConfig) {
        $config = Get-Content -LiteralPath $DeviceConfig -Raw -Encoding UTF8 | ConvertFrom-Json
    }

    $resolvedMosquittoDir = if ($MosquittoDir) {
        $MosquittoDir
    } elseif ($env:MOSQUITTO_DIR) {
        $env:MOSQUITTO_DIR
    } else {
        "C:\Program Files\Mosquitto"
    }

    $hostName = if ($HostNameOverride) { $HostNameOverride } elseif ($config) { [string]$config.mqtt.host } else { "127.0.0.1" }
    $port = if ($PortOverride) { $PortOverride } elseif ($config) { [string]$config.mqtt.port } else { "1883" }
    $username = if ($UsernameOverride) { $UsernameOverride } elseif ($config) { [string]$config.mqtt.username } else { "" }
    $password = if ($PasswordOverride) { $PasswordOverride } elseif ($config) { [string]$config.mqtt.password } else { "" }
    $productType = if ($ProductTypeOverride) { $ProductTypeOverride } elseif ($config) { [string]$config.mqtt.productType } else { "crawler" }
    $deviceId = if ($DeviceIdOverride) { $DeviceIdOverride } elseif ($config) { [string]$config.mqtt.deviceId } else { "crawler_00000001" }

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
