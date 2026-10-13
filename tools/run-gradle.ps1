param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")
$localPropertiesPath = Join-Path $RepoRoot "local.properties"

function Read-KeyValueFile([string]$Path) {
    $values = @{}
    if (!(Test-Path -LiteralPath $Path)) { return $values }
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if (!$line -or $line.StartsWith("#")) { continue }
        $index = $line.IndexOf("=")
        if ($index -gt 0) {
            $values[$line.Substring(0, $index).Trim()] = $line.Substring($index + 1).Trim().Trim('"')
        }
    }
    return $values
}

$localProperties = Read-KeyValueFile $localPropertiesPath

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    $bundled = Get-ChildItem -LiteralPath (Join-Path $RepoRoot ".local-tools") -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\java.exe") } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($bundled) { return $bundled.FullName }

    $java = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($java) { return Split-Path -Parent (Split-Path -Parent $java.Source) }

    throw "JDK 21 not found. Configure the Windows JAVA_HOME environment variable."
}

$javaHome = Resolve-JavaHome
$env:JAVA_HOME = $javaHome

$localSdk = $localProperties["sdk.dir"]
if ($localSdk) { $localSdk = $localSdk.Replace('\:', ':').Replace('\\', '\') }
$androidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $localSdk }
if (!$androidHome -or !(Test-Path -LiteralPath $androidHome)) {
    throw "Android SDK not found. Set sdk.dir in local.properties or configure Windows ANDROID_HOME."
}
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = $androidHome }
if (!$env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $androidHome }

$gradleWrapper = Join-Path $RepoRoot "gradlew.bat"
$signingDirectory = Join-Path $RepoRoot ".local-tools\signing"
$keystorePath = Join-Path $signingDirectory "robot-release.jks"
$signingEnvPath = Join-Path $signingDirectory "release-signing-secrets.env"
$runtimeEnvPath = Join-Path $RepoRoot ".local-tools\config\app-runtime.env"
$savedEnvironment = @{}

function Set-ProcessValue([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if (!$savedEnvironment.ContainsKey($Name)) {
        $savedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

try {
    $signing = Read-KeyValueFile $signingEnvPath
    if ((Test-Path -LiteralPath $keystorePath) -and $signing.Count -gt 0) {
        Set-ProcessValue "ANDROID_KEYSTORE_FILE" $keystorePath
        Set-ProcessValue "ANDROID_KEYSTORE_PASSWORD" $signing["ANDROID_KEYSTORE_PASSWORD"]
        Set-ProcessValue "ANDROID_KEY_ALIAS" $signing["ANDROID_KEY_ALIAS"]
        Set-ProcessValue "ANDROID_KEY_PASSWORD" $signing["ANDROID_KEY_PASSWORD"]
    }

    $runtime = Read-KeyValueFile $runtimeEnvPath
    $runtimeToLocal = [ordered]@{
        API_BASE_URL = "api.base.url"
        MQTT_HOST = "mqtt.host"
        MQTT_PORT = "mqtt.port"
        MQTT_USERNAME = "mqtt.username"
        MQTT_PASSWORD = "mqtt.password"
        MQTT_PRODUCT_TYPE = "mqtt.product_type"
        MQTT_DEFAULT_DEVICE_ID = "mqtt.default_device_id"
    }
    foreach ($name in $runtimeToLocal.Keys) {
        if ([string]::IsNullOrWhiteSpace($localProperties[$runtimeToLocal[$name]])) {
            Set-ProcessValue $name $runtime[$name]
        }
    }

    & $gradleWrapper @GradleArgs
    $gradleExitCode = $LASTEXITCODE
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}
exit $gradleExitCode
