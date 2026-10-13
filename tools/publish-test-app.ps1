param(
    [string]$Repository = "3197828561/robot",
    [string]$Branch = "feature/app-update-channel"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$projectGh = Join-Path $repoRoot ".local-tools\gh\bin\gh.exe"
$installedGh = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"
$gh = if (Test-Path $projectGh) { $projectGh } elseif (Test-Path $installedGh) { $installedGh } else { (Get-Command gh -ErrorAction SilentlyContinue).Source }
$signingPath = Join-Path $repoRoot "..\import-message\app-signing\release-signing-secrets.env"
$keystorePath = Join-Path $repoRoot ".local-tools\signing\robot-release.jks"
$distributionPath = Join-Path $repoRoot "..\import-message\app\config\distribution-secrets.env"
$localPropertiesPath = Join-Path $repoRoot "local.properties"
$verificationDirectory = Join-Path $repoRoot ".local-tools\verification"
$apkPath = Join-Path $verificationDirectory "latest-test.apk"

function Read-EnvFile([string]$Path) {
    $values = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if (!$line -or $line.StartsWith("#")) { continue }
        $index = $line.IndexOf("=")
        if ($index -gt 0) { $values[$line.Substring(0, $index).Trim()] = $line.Substring($index + 1).Trim().Trim('"') }
    }
    return $values
}

if (!$gh) { throw "缺少 GitHub CLI。" }
foreach ($path in $gh, $signingPath, $keystorePath, $distributionPath) {
    if (!(Test-Path -LiteralPath $path)) { throw "发布验证缺少文件：$path" }
}
& $gh auth status --hostname github.com *> $null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI 尚未登录。" }

$startedAt = [DateTimeOffset]::UtcNow.AddSeconds(-10)
$dispatchOutput = @(& $gh workflow run publish-app.yml --repo $Repository --ref $Branch -f channel=test)
if ($LASTEXITCODE -ne 0) { throw "触发 GitHub Actions 失败。" }

$run = $null
$dispatchText = $dispatchOutput -join "`n"
if ($dispatchText -match '/actions/runs/(?<id>\d+)') {
    $run = [pscustomobject]@{ databaseId = [long]$matches.id }
} else {
    for ($attempt = 0; $attempt -lt 20 -and !$run; $attempt++) {
        Start-Sleep -Seconds 3
        $runs = & $gh run list --repo $Repository --workflow publish-app.yml --branch $Branch `
            --event workflow_dispatch --limit 5 --json databaseId,createdAt,status,conclusion | ConvertFrom-Json
        $run = $runs | Where-Object { [DateTimeOffset]::Parse($_.createdAt) -ge $startedAt } | Select-Object -First 1
    }
}
if (!$run) { throw "未找到刚触发的 GitHub Actions 运行。" }

Write-Host "GitHub Actions run $($run.databaseId) 已触发，等待测试、签名和上传完成。"
& $gh run watch $run.databaseId --repo $Repository --exit-status
if ($LASTEXITCODE -ne 0) { throw "GitHub Actions 发布失败，请查看 run $($run.databaseId)。" }

$distribution = Read-EnvFile $distributionPath
New-Item -ItemType Directory -Force -Path $verificationDirectory | Out-Null
$basicBytes = [Text.Encoding]::UTF8.GetBytes("$($distribution.APP_DOWNLOAD_USERNAME):$($distribution.APP_DOWNLOAD_PASSWORD)")
$headers = @{ Authorization = "Basic $([Convert]::ToBase64String($basicBytes))" }
Invoke-WebRequest -Uri "https://47.103.157.213/downloads/app/test" `
    -Headers $headers -OutFile $apkPath -UseBasicParsing

$localProperties = Read-EnvFile $localPropertiesPath
$localSdk = $localProperties["sdk.dir"]
if ($localSdk) { $localSdk = $localSdk.Replace('\:', ':').Replace('\\', '\') }
$sdkDirectory = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $localSdk }
if (!$sdkDirectory -or !(Test-Path -LiteralPath $sdkDirectory)) {
    throw "未找到 Android SDK；请配置 local.properties 的 sdk.dir 或 Windows ANDROID_HOME。"
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkDirectory "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$apkSigner = Join-Path $buildTools.FullName "apksigner.bat"
if (!(Test-Path $apkSigner)) { throw "未找到 apksigner。" }

$javaHome = $env:JAVA_HOME
if (!$javaHome -or !(Test-Path -LiteralPath (Join-Path $javaHome "bin\keytool.exe"))) {
    $bundledJdk = Get-ChildItem -LiteralPath (Join-Path $repoRoot ".local-tools") -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\keytool.exe") } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($bundledJdk) { $javaHome = $bundledJdk.FullName }
}
if (!$javaHome) { throw "未找到 JDK；请配置 Windows JAVA_HOME。" }
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $javaHome
try {
    $certificateOutput = & $apkSigner verify --verbose --print-certs $apkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "服务器下载的 APK 签名验证失败。" }
} finally {
    $env:JAVA_HOME = $previousJavaHome
}
$apkDigestLine = $certificateOutput | Where-Object { $_ -match 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)' } | Select-Object -First 1
if (!$apkDigestLine) { throw "无法读取 APK 签名证书摘要。" }
$null = $apkDigestLine -match 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)'
$apkDigest = $matches[1].ToLowerInvariant()

$signing = Read-EnvFile $signingPath
$storePassword = $signing.ANDROID_KEYSTORE_PASSWORD
$certificatePath = Join-Path $verificationDirectory "release-cert.der"
$keytool = Join-Path $javaHome "bin\keytool.exe"
$env:ROBOT_STORE_PASSWORD = $storePassword
try {
    & $keytool -exportcert -keystore $keystorePath -alias $signing.ANDROID_KEY_ALIAS `
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
