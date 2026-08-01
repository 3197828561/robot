param(
    [string]$MosquittoDir = "",
    [string]$LocalProperties = "local.properties",
    [string]$HostNameOverride = "",
    [string]$PortOverride = "",
    [string]$UsernameOverride = "",
    [string]$PasswordOverride = "",
    [string]$ProductTypeOverride = "",
    [string]$DeviceIdOverride = "",
    [int]$MaxMessages = 0
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_mqtt-common.ps1")

if ($MaxMessages -lt 0) { throw "MaxMessages cannot be negative." }

$context = New-RobotMqttContext -ScriptDirectory $PSScriptRoot `
    -MosquittoDir $MosquittoDir -LocalProperties $LocalProperties `
    -HostNameOverride $HostNameOverride -PortOverride $PortOverride `
    -UsernameOverride $UsernameOverride -PasswordOverride $PasswordOverride `
    -ProductTypeOverride $ProductTypeOverride -DeviceIdOverride $DeviceIdOverride

Write-Host ""
Write-Host "Robot command listener (no replies)"
Write-Host "Broker: $($context.HostName):$($context.Port)"
Write-Host "Username: $($context.Username)"
Write-Host "Device: $($context.ProductType)/$($context.DeviceId)"
Write-Host "Topics: $($context.TopicPrefix)/cmd, $($context.TopicPrefix)/remote"
Write-Host "Press Ctrl+C to stop."
Write-Host ""

$arguments = New-MqttClientArgs $context -VerboseSubscribe
$arguments += @("-q", "1", "-t", "$($context.TopicPrefix)/cmd", "-t", "$($context.TopicPrefix)/remote")
if ($MaxMessages -gt 0) { $arguments += @("-C", [string]$MaxMessages) }

& $context.SubExe @arguments | ForEach-Object {
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
    Write-Host (Format-JsonText $payload)
}
exit $LASTEXITCODE
