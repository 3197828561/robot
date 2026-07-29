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
& $gradleWrapper @GradleArgs
exit $LASTEXITCODE
