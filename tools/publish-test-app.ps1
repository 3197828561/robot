param(
    [string]$Repository = "3197828561/robot",
    [string]$Branch = "feature/app-update-channel"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$projectGh = Join-Path $repoRoot ".local-tools\gh\bin\gh.exe"
$installedGh = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"
$gh = if (Test-Path $projectGh) { $projectGh } elseif (Test-Path $installedGh) { $installedGh } else { (Get-Command gh -ErrorAction SilentlyContinue).Source }
$signingPath = Join-Path $repoRoot ".local-tools\signing\release-secrets.clixml"
$keystorePath = Join-Path $repoRoot ".local-tools\signing\robot-release.jks"
$distributionPath = Join-Path $repoRoot ".local-tools\secrets\distribution-secrets.clixml"
$propertiesPath = Join-Path $repoRoot "local.properties"
$verificationDirectory = Join-Path $repoRoot ".local-tools\verification"
$apkPath = Join-Path $verificationDirectory "latest-test.apk"

function Convert-SecureToPlainText([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Read-Property([string]$Name) {
    foreach ($line in Get-Content -LiteralPath $propertiesPath -Encoding UTF8) {
        if ($line -match "^$([regex]::Escape($Name))=(.+)$") { return $matches[1].Trim().Trim('"') }
    }
    return $null
}

if (!$gh) { throw "缺少 GitHub CLI。" }
foreach ($path in $gh, $signingPath, $keystorePath, $distributionPath) {
    if (!(Test-Path -LiteralPath $path)) { throw "发布验证缺少文件：$path" }
}
& $gh auth status --hostname github.com *> $null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI 尚未登录。" }

$startedAt = [DateTimeOffset]::UtcNow.AddSeconds(-10)
& $gh workflow run publish-app.yml --repo $Repository --ref $Branch -f channel=test
if ($LASTEXITCODE -ne 0) { throw "触发 GitHub Actions 失败。" }

$run = $null
for ($attempt = 0; $attempt -lt 20 -and !$run; $attempt++) {
    Start-Sleep -Seconds 3
    $runs = & $gh run list --repo $Repository --workflow publish-app.yml --branch $Branch `
        --event workflow_dispatch --limit 5 --json databaseId,createdAt,status,conclusion | ConvertFrom-Json
    $run = $runs | Where-Object { [DateTimeOffset]::Parse($_.createdAt) -ge $startedAt } | Select-Object -First 1
}
if (!$run) { throw "未找到刚触发的 GitHub Actions 运行。" }

Write-Host "GitHub Actions run $($run.databaseId) 已触发，等待测试、签名和上传完成。"
& $gh run watch $run.databaseId --repo $Repository --exit-status
if ($LASTEXITCODE -ne 0) { throw "GitHub Actions 发布失败，请查看 run $($run.databaseId)。" }

$distribution = Import-Clixml -LiteralPath $distributionPath
New-Item -ItemType Directory -Force -Path $verificationDirectory | Out-Null
$credential = [Management.Automation.PSCredential]::new(
    $distribution.AppDownloadUsername,
    $distribution.AppDownloadPassword
)
Invoke-WebRequest -Uri "https://47.103.157.213/downloads/app/test" `
    -Authentication Basic -Credential $credential -OutFile $apkPath -UseBasicParsing

$sdkDirectory = Read-Property "sdk.dir"
if (!$sdkDirectory) { throw "local.properties 缺少 sdk.dir。" }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkDirectory "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$apkSigner = Join-Path $buildTools.FullName "apksigner.bat"
if (!(Test-Path $apkSigner)) { throw "未找到 apksigner。" }

$certificateOutput = & $apkSigner verify --verbose --print-certs $apkPath 2>&1
if ($LASTEXITCODE -ne 0) { throw "服务器下载的 APK 签名验证失败。" }
$apkDigestLine = $certificateOutput | Where-Object { $_ -match 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)' } | Select-Object -First 1
if (!$apkDigestLine) { throw "无法读取 APK 签名证书摘要。" }
$null = $apkDigestLine -match 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)'
$apkDigest = $matches[1].ToLowerInvariant()

$signing = Import-Clixml -LiteralPath $signingPath
$storePassword = Convert-SecureToPlainText $signing.StorePassword
$certificatePath = Join-Path $verificationDirectory "release-cert.der"
$javaHome = Read-Property "java.home"
$keytool = Join-Path $javaHome "bin\keytool.exe"
$env:ROBOT_STORE_PASSWORD = $storePassword
try {
    & $keytool -exportcert -keystore $keystorePath -alias $signing.Alias `
        -storepass:env ROBOT_STORE_PASSWORD -file $certificatePath *> $null
    if ($LASTEXITCODE -ne 0) { throw "无法导出本地发布证书进行比对。" }
    $localDigest = (Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($localDigest -ne $apkDigest) { throw "云端 APK 与本地固定发布证书不一致。" }
} finally {
    Remove-Item Env:ROBOT_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $certificatePath -ErrorAction SilentlyContinue
    $storePassword = $null
}

Write-Host "发布链路验证通过：GitHub Actions、HTTPS 上传、鉴权下载、APK 签名证书一致。"
