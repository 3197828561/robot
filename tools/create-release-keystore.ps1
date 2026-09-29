param([string]$Alias = "robot-app")

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$propertiesPath = Join-Path $repoRoot "local.properties"
$signingDirectory = Join-Path $repoRoot ".local-tools\signing"
$keystorePath = Join-Path $signingDirectory "robot-release.jks"
$secretsPath = Join-Path $signingDirectory "release-secrets.clixml"

function Convert-SecureToPlainText([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Resolve-Keytool {
    $javaHome = $env:JAVA_HOME
    if (Test-Path -LiteralPath $propertiesPath) {
        foreach ($line in Get-Content -LiteralPath $propertiesPath -Encoding UTF8) {
            if ($line -match '^java\.home=(.+)$') { $javaHome = $matches[1].Trim().Trim('"') }
        }
    }
    if (!$javaHome) { throw "未找到 JDK；请先在 local.properties 配置 java.home。" }
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

if ((Test-Path -LiteralPath $keystorePath) -and (Test-Path -LiteralPath $secretsPath)) {
    $saved = Import-Clixml -LiteralPath $secretsPath
    $storePassword = Convert-SecureToPlainText $saved.StorePassword
    $keyPassword = Convert-SecureToPlainText $saved.KeyPassword
    try { Test-Keystore $keytool $storePassword $keyPassword $saved.Alias }
    finally { $storePassword = $null; $keyPassword = $null }
    Write-Host "已有签名库与 DPAPI 加密配置验证通过，未覆盖任何文件。"
    exit 0
}

if ((Test-Path -LiteralPath $secretsPath) -and !(Test-Path -LiteralPath $keystorePath)) {
    throw "发现加密配置但签名库缺失。请从离线备份恢复 robot-release.jks，脚本不会自动创建新密钥覆盖身份。"
}

$isExisting = Test-Path -LiteralPath $keystorePath
$storeSecure = Read-Host $(if ($isExisting) { "输入现有签名库密码" } else { "输入新的签名库密码（至少 12 位）" }) -AsSecureString
$keySecure = Read-Host $(if ($isExisting) { "输入现有签名密钥密码" } else { "输入新的签名密钥密码（至少 12 位）" }) -AsSecureString
$storePassword = Convert-SecureToPlainText $storeSecure
$keyPassword = Convert-SecureToPlainText $keySecure
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

    $encrypted = [pscustomobject]@{
        SchemaVersion = 1
        Alias = $Alias
        StorePassword = $storeSecure
        KeyPassword = $keySecure
    }
    $temporary = "$secretsPath.tmp"
    $encrypted | Export-Clixml -LiteralPath $temporary -Depth 3
    Move-Item -LiteralPath $temporary -Destination $secretsPath
    Write-Host "签名库已验证；密码已通过 Windows DPAPI 加密保存，仅当前 Windows 用户可解密。"
    Write-Host "请离线备份：$keystorePath"
} finally {
    Remove-Item Env:ROBOT_STORE_PASSWORD,Env:ROBOT_KEY_PASSWORD -ErrorAction SilentlyContinue
    $storePassword = $null
    $keyPassword = $null
}
