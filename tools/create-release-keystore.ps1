param([string]$Alias = "robot-app")

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$signingDirectory = Join-Path $repoRoot ".local-tools\signing"
$keystorePath = Join-Path $signingDirectory "robot-release.jks"
$signingEnvPath = Join-Path $signingDirectory "release-signing-secrets.env"

function Read-EnvFile([string]$Path) {
    $values = @{}
    if (!(Test-Path -LiteralPath $Path)) { return $values }
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if (!$line -or $line.StartsWith("#")) { continue }
        $index = $line.IndexOf("=")
        if ($index -gt 0) { $values[$line.Substring(0, $index).Trim()] = $line.Substring($index + 1).Trim().Trim('"') }
    }
    return $values
}

function Resolve-Keytool {
    $javaHome = $env:JAVA_HOME
    if (!$javaHome -or !(Test-Path -LiteralPath (Join-Path $javaHome "bin\keytool.exe"))) {
        $bundled = Get-ChildItem -LiteralPath (Join-Path $repoRoot ".local-tools") -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\keytool.exe") } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($bundled) { $javaHome = $bundled.FullName }
    }
    if (!$javaHome) { throw "未找到 JDK；请配置 Windows JAVA_HOME。" }
    $candidate = Join-Path $javaHome "bin\keytool.exe"
    if (!(Test-Path -LiteralPath $candidate)) { throw "未找到 keytool：$candidate" }
    return $candidate
}

function Test-Keystore($keytool, $storePassword, $keyPassword, $keyAlias) {
    $env:ROBOT_STORE_PASSWORD = $storePassword
    $env:ROBOT_KEY_PASSWORD = $keyPassword
    try {
        & $keytool -list -keystore $keystorePath -alias $keyAlias `
            -storepass:env ROBOT_STORE_PASSWORD -keypass:env ROBOT_KEY_PASSWORD *> $null
        if ($LASTEXITCODE -ne 0) { throw "签名库、别名或密码验证失败；未修改任何现有文件。" }
    } finally {
        Remove-Item Env:ROBOT_STORE_PASSWORD,Env:ROBOT_KEY_PASSWORD -ErrorAction SilentlyContinue
    }
}

$keytool = Resolve-Keytool
New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null
$signing = Read-EnvFile $signingEnvPath

if ((Test-Path -LiteralPath $keystorePath) -and $signing.Count -gt 0) {
    Test-Keystore $keytool $signing.ANDROID_KEYSTORE_PASSWORD $signing.ANDROID_KEY_PASSWORD $signing.ANDROID_KEY_ALIAS
    Write-Host "已有签名库与明文签名配置验证通过，未覆盖任何文件。"
    exit 0
}

if ($signing.Count -gt 0 -and !(Test-Path -LiteralPath $keystorePath)) {
    throw "发现签名配置但签名库缺失。请恢复 robot-release.jks，脚本不会自动创建新密钥覆盖身份。"
}

$isExisting = Test-Path -LiteralPath $keystorePath
$storePassword = Read-Host $(if ($isExisting) { "输入现有签名库密码" } else { "输入新的签名库密码（至少 12 位）" })
$keyPassword = Read-Host $(if ($isExisting) { "输入现有签名密钥密码" } else { "输入新的签名密钥密码（至少 12 位）" })
try {
    if ($storePassword.Length -lt 12 -or $keyPassword.Length -lt 12) { throw "密码长度必须至少为 12 位。" }
    if ($isExisting) {
        Test-Keystore $keytool $storePassword $keyPassword $Alias
    } else {
        $env:ROBOT_STORE_PASSWORD = $storePassword
        $env:ROBOT_KEY_PASSWORD = $keyPassword
        try {
            & $keytool -genkeypair -v -storetype JKS -keystore $keystorePath -alias $Alias `
                -keyalg RSA -keysize 4096 -validity 10000 `
                -dname "CN=Solar Robot App, OU=Android, O=Robot Project, C=CN" `
                -storepass:env ROBOT_STORE_PASSWORD -keypass:env ROBOT_KEY_PASSWORD
            if ($LASTEXITCODE -ne 0) { throw "keytool 创建签名库失败。" }
        } finally {
            Remove-Item Env:ROBOT_STORE_PASSWORD,Env:ROBOT_KEY_PASSWORD -ErrorAction SilentlyContinue
        }
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $signingEnvPath) | Out-Null
    @(
        "ANDROID_KEYSTORE_FILE=robot-release.jks"
        "ANDROID_KEYSTORE_PASSWORD=$storePassword"
        "ANDROID_KEY_ALIAS=$Alias"
        "ANDROID_KEY_PASSWORD=$keyPassword"
    ) | Set-Content -LiteralPath $signingEnvPath -Encoding UTF8
    Write-Host "签名库已验证；签名参数已明文保存到：$signingEnvPath"
} finally {
    Remove-Item Env:ROBOT_STORE_PASSWORD,Env:ROBOT_KEY_PASSWORD -ErrorAction SilentlyContinue
    $storePassword = $null
    $keyPassword = $null
}
