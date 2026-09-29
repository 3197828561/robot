param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs,
    [string]$LocalProperties = "local.properties"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")
if ($LocalProperties -eq "local.properties") {
    $LocalProperties = Join-Path $RepoRoot "local.properties"
}

function Read-LocalProperties {
    param([string]$Path)
    $props = @{}
    if (!(Test-Path -LiteralPath $Path)) { return $props }
    foreach ($rawLine in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
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

$props = Read-LocalProperties $LocalProperties
$javaHome = $props["java.home"]
if (!$javaHome) {
    $javaHome = $env:JAVA_HOME
}
if (!$javaHome -or !(Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "JDK not found. Set java.home in local.properties to a JDK 21 directory."
}

$env:JAVA_HOME = $javaHome
$gradleWrapper = Join-Path $RepoRoot "gradlew.bat"
$signingDirectory = Join-Path $RepoRoot ".local-tools\signing"
$keystorePath = Join-Path $signingDirectory "robot-release.jks"
$secretsPath = Join-Path $signingDirectory "release-secrets.clixml"
$distributionSecretsPath = Join-Path $RepoRoot ".local-tools\secrets\distribution-secrets.clixml"
$savedEnvironment = @{}

function Convert-SecureToPlainText([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

try {
    if ((Test-Path -LiteralPath $keystorePath) -and (Test-Path -LiteralPath $secretsPath)) {
        $saved = Import-Clixml -LiteralPath $secretsPath
        foreach ($name in "ANDROID_KEYSTORE_FILE", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD") {
            $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        }
        $env:ANDROID_KEYSTORE_FILE = $keystorePath
        $env:ANDROID_KEYSTORE_PASSWORD = Convert-SecureToPlainText $saved.StorePassword
        $env:ANDROID_KEY_ALIAS = $saved.Alias
        $env:ANDROID_KEY_PASSWORD = Convert-SecureToPlainText $saved.KeyPassword
    }
    if (Test-Path -LiteralPath $distributionSecretsPath) {
        $distribution = Import-Clixml -LiteralPath $distributionSecretsPath
        $savedEnvironment["MQTT_PASSWORD"] = [Environment]::GetEnvironmentVariable("MQTT_PASSWORD", "Process")
        $env:MQTT_PASSWORD = Convert-SecureToPlainText $distribution.MqttPassword
    }
    & $gradleWrapper @GradleArgs
    $gradleExitCode = $LASTEXITCODE
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}
exit $gradleExitCode
