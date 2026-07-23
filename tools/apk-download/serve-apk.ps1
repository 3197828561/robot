param(
    [int]$Port = 8080,
    [string]$ApkPath = "robot-app-debug.apk"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $root "..\..")
$apk = Join-Path $root $ApkPath

if (-not (Test-Path -LiteralPath $apk)) {
    $fallback = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (Test-Path -LiteralPath $fallback) {
        Copy-Item -LiteralPath $fallback -Destination $apk -Force
    } else {
        throw "APK not found: $apk"
    }
}

$file = Get-Item -LiteralPath $apk
$addresses = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object {
        $_.IPAddress -notlike "127.*" -and
        $_.IPAddress -notlike "169.254.*" -and
        $_.PrefixOrigin -ne "WellKnown"
    } |
    Select-Object -ExpandProperty IPAddress -Unique

Write-Host ""
Write-Host "Serving APK:" -ForegroundColor Green
Write-Host "  $($file.FullName)"
Write-Host ""
Write-Host "Open one of these URLs on your phone:" -ForegroundColor Green
foreach ($ip in $addresses) {
    Write-Host "  http://$ip`:$Port/robot-app-debug.apk"
}
Write-Host ""
Write-Host "Keep this window open while downloading. Press Ctrl+C to stop."
Write-Host ""

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
$listener.Start()

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
            $requestLine = $reader.ReadLine()
            while (($line = $reader.ReadLine()) -ne $null -and $line -ne "") {}

            if ($requestLine -notmatch "^GET\s+/robot-app-debug\.apk(\?.*)?\s+HTTP/") {
                $body = [System.Text.Encoding]::UTF8.GetBytes("Use /robot-app-debug.apk")
                $header = "HTTP/1.1 404 Not Found`r`nContent-Type: text/plain; charset=utf-8`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
                $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                $stream.Write($headerBytes, 0, $headerBytes.Length)
                $stream.Write($body, 0, $body.Length)
            } else {
                $header = "HTTP/1.1 200 OK`r`nContent-Type: application/vnd.android.package-archive`r`nContent-Disposition: attachment; filename=""robot-app-debug.apk""`r`nContent-Length: $($file.Length)`r`nConnection: close`r`n`r`n"
                $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                $stream.Write($headerBytes, 0, $headerBytes.Length)
                $fs = [System.IO.File]::OpenRead($file.FullName)
                try {
                    $fs.CopyTo($stream)
                } finally {
                    $fs.Dispose()
                }
                Write-Host "Downloaded by $($client.Client.RemoteEndPoint)"
            }
        } catch {
            Write-Warning $_.Exception.Message
        } finally {
            $client.Close()
        }
    }
} finally {
    $listener.Stop()
}
