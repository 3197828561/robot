param(
    [string]$MosquittoDir = "",
    [string]$LocalProperties = "local.properties",
    [string]$HostNameOverride = "",
    [string]$PortOverride = "",
    [string]$UsernameOverride = "",
    [string]$PasswordOverride = "",
    [string]$ProductTypeOverride = "",
    [string]$DeviceIdOverride = "",
    [int]$IntervalMs = 1000,
    [int]$Count = 0
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_mqtt-common.ps1")

if ($IntervalMs -lt 300) { throw "IntervalMs must be at least 300." }
if ($Count -lt 0) { throw "Count cannot be negative." }

$context = New-RobotMqttContext -ScriptDirectory $PSScriptRoot `
    -MosquittoDir $MosquittoDir -LocalProperties $LocalProperties `
    -HostNameOverride $HostNameOverride -PortOverride $PortOverride `
    -UsernameOverride $UsernameOverride -PasswordOverride $PasswordOverride `
    -ProductTypeOverride $ProductTypeOverride -DeviceIdOverride $DeviceIdOverride

Write-Host ""
Write-Host "Robot online simulator"
Write-Host "Broker: $($context.HostName):$($context.Port)"
Write-Host "Username: $($context.Username)"
Write-Host "Device: $($context.ProductType)/$($context.DeviceId)"
Write-Host "Topic: $($context.TopicPrefix)/heartbeat"
Write-Host "Press Ctrl+C to stop."
Write-Host ""

$sent = 0
while ($Count -eq 0 -or $sent -lt $Count) {
    $payload = New-RobotBasePayload $context
    $payload["online"] = $true
    Publish-MqttJson $context "$($context.TopicPrefix)/heartbeat" $payload
    $sent++
    Write-Host "[ROBOT -> APP][HEARTBEAT] online=true count=$sent $(Get-Date -Format HH:mm:ss)"
    if ($Count -eq 0 -or $sent -lt $Count) { Start-Sleep -Milliseconds $IntervalMs }
}
