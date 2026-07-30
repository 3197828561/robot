Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:PageTestResults = [System.Collections.Generic.List[object]]::new()
$script:PageTestContext = $null

function Read-PageTestLocalProperties {
    param([Parameter(Mandatory = $true)][string]$Path)
    $properties = @{}
    if (!(Test-Path -LiteralPath $Path)) { return $properties }
    foreach ($rawLine in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $line = $rawLine.Trim()
        if (!$line -or $line.StartsWith("#") -or $line.StartsWith("!")) { continue }
        $separator = $line.IndexOf("=")
        if ($separator -le 0) { continue }
        $key = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim().Trim('"')
        # java.util.Properties escapes Windows drive separators as C\:/ or C\:/
        $value = $value -replace '\\([:= ])', '$1'
        $value = $value -replace '\\\\', '\'
        $properties[$key] = $value
    }
    return $properties
}

function Add-PageTestResult {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("PASS", "FAIL", "SKIP", "INFO")][string]$Status,
        [Parameter(Mandatory = $true)][string]$Kind,
        [Parameter(Mandatory = $true)][string]$Target,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $item = [pscustomobject]@{
        timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        page = $script:PageTestContext.Page
        status = $Status
        kind = $Kind
        target = $Target
        message = $Message
    }
    $script:PageTestResults.Add($item)
    $color = switch ($Status) {
        "PASS" { "Green" }
        "FAIL" { "Red" }
        "SKIP" { "Yellow" }
        default { "Cyan" }
    }
    Write-Host "[$Status][$Kind] $Target - $Message" -ForegroundColor $color
}

function Start-PageTest {
    param(
        [Parameter(Mandatory = $true)][string]$Page,
        [string]$Serial = "",
        [string]$OutputDir = "",
        [switch]$StaticOnly
    )
    $script:PageTestResults.Clear()
    $scriptDir = Split-Path -Parent $MyInvocation.PSCommandPath
    if (!$scriptDir) { $scriptDir = $PSScriptRoot }
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
    if (!$OutputDir) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $OutputDir = Join-Path $repoRoot ".codex-artifacts\page-tests\$stamp-$Page"
    } elseif (![System.IO.Path]::IsPathRooted($OutputDir)) {
        $OutputDir = Join-Path $repoRoot $OutputDir
    }
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

    $context = [pscustomobject]@{
        Page = $Page
        RepoRoot = $repoRoot
        OutputDir = (Resolve-Path $OutputDir).Path
        StaticOnly = [bool]$StaticOnly
        Adb = $null
        Serial = $Serial
        DumpIndex = 0
        LastDump = $null
    }
    $script:PageTestContext = $context

    Add-PageTestResult INFO "environment" "branch" ((git -C $repoRoot branch --show-current) | Out-String).Trim()
    if ($StaticOnly) {
        Add-PageTestResult INFO "environment" "runtime" "StaticOnly：跳过 ADB 运行检查"
        return $context
    }

    $properties = Read-PageTestLocalProperties (Join-Path $repoRoot "local.properties")
    $adbPath = if ($properties.ContainsKey("adb.path")) { $properties["adb.path"] } else { "" }
    if (!$adbPath -and $properties.ContainsKey("sdk.dir")) {
        $adbPath = Join-Path $properties["sdk.dir"] "platform-tools\adb.exe"
    }
    if (!$adbPath -or !(Test-Path -LiteralPath $adbPath)) {
        throw "未找到 ADB。请在已忽略的 local.properties 配置 adb.path 或 sdk.dir。"
    }
    $context.Adb = (Resolve-Path -LiteralPath $adbPath).Path

    $deviceLines = & $context.Adb devices |
        Select-Object -Skip 1 |
        Where-Object { $_ -match "^\S+\s+device$" }
    $deviceSerials = @($deviceLines | ForEach-Object { ($_ -split "\s+")[0] })
    if (!$context.Serial) {
        if ($deviceSerials.Count -eq 1) {
            $context.Serial = $deviceSerials[0]
        } elseif ($deviceSerials.Count -eq 0) {
            throw "没有可用的 Android 设备/模拟器。请先启动设备。"
        } else {
            throw "检测到多个 Android 设备，请使用 -Serial 指定目标。"
        }
    }
    if ($context.Serial -notin $deviceSerials) {
        throw "指定设备未处于 device 状态：$($context.Serial)"
    }
    Add-PageTestResult PASS "environment" "adb-device" "ADB 设备可用：$($context.Serial)"
    return $context
}

function Invoke-PageAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    if ($script:PageTestContext.StaticOnly) {
        throw "StaticOnly 模式不能调用 ADB"
    }
    $output = & $script:PageTestContext.Adb -s $script:PageTestContext.Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB 命令失败：$($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Open-PageTestApp {
    [void](Invoke-PageAdb @("shell", "monkey", "-p", "com.robot.solar", "-c", "android.intent.category.LAUNCHER", "1"))
    Start-Sleep -Milliseconds 1800
}

function Get-PageUiDump {
    param([string]$Label = "ui")
    $script:PageTestContext.DumpIndex++
    $safeLabel = $Label -replace "[^a-zA-Z0-9_-]", "-"
    $remote = "/sdcard/page-test-$($script:PageTestContext.Page)-$($script:PageTestContext.DumpIndex).xml"
    $local = Join-Path $script:PageTestContext.OutputDir ("{0:D2}-{1}.xml" -f $script:PageTestContext.DumpIndex, $safeLabel)
    [void](Invoke-PageAdb @("shell", "uiautomator", "dump", "--compressed", $remote))
    [void](Invoke-PageAdb @("pull", $remote, $local))
    [xml]$xml = Get-Content -LiteralPath $local -Raw -Encoding UTF8
    $script:PageTestContext.LastDump = [pscustomobject]@{ Path = $local; Xml = $xml }
    return $script:PageTestContext.LastDump
}

function Save-PageScreenshot {
    param([string]$Label = "screen")
    $safeLabel = $Label -replace "[^a-zA-Z0-9_-]", "-"
    $remote = "/sdcard/page-test-$($script:PageTestContext.Page)-$safeLabel.png"
    $local = Join-Path $script:PageTestContext.OutputDir "$safeLabel.png"
    [void](Invoke-PageAdb @("shell", "screencap", "-p", $remote))
    [void](Invoke-PageAdb @("pull", $remote, $local))
    Add-PageTestResult INFO "evidence" $safeLabel "截图：$local"
    return $local
}

function Find-PageUiNode {
    param(
        [Parameter(Mandatory = $true)]$Dump,
        [Parameter(Mandatory = $true)][string]$Id
    )
    $suffix = "/id/$Id"
    return @($Dump.Xml.SelectNodes("//node") | Where-Object {
        $_.'resource-id' -eq "com.robot.solar:id/$Id" -or $_.'resource-id'.EndsWith($suffix)
    }) | Select-Object -First 1
}

function Test-PageUiNode {
    param(
        [Parameter(Mandatory = $true)]$Dump,
        [Parameter(Mandatory = $true)][string]$Id,
        [ValidateSet("button", "field", "navigation", "container")][string]$Kind = "field",
        [string]$TextPattern = "",
        [switch]$AllowMissing,
        [string]$MissingMessage = "当前页面层级中未找到"
    )
    $node = Find-PageUiNode $Dump $Id
    if (!$node) {
        if ($AllowMissing) {
            Add-PageTestResult SKIP $Kind $Id $MissingMessage
        } else {
            Add-PageTestResult FAIL $Kind $Id $MissingMessage
        }
        return $null
    }
    $text = [string]$node.text
    if ($TextPattern -and $text -notmatch $TextPattern) {
        Add-PageTestResult FAIL $Kind $Id "文本不符合 /$TextPattern/，实际：$text"
    } else {
        $state = "enabled=$($node.enabled), clickable=$($node.clickable)"
        $message = if ($text) { "存在；$state；text=$text" } else { "存在；$state" }
        Add-PageTestResult PASS $Kind $Id $message
    }
    return $node
}

function Get-PageNodeCenter {
    param([Parameter(Mandatory = $true)]$Node)
    $bounds = [string]$Node.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "无法解析控件 bounds：$bounds"
    }
    return [pscustomobject]@{
        Left = [int]$Matches[1]
        Top = [int]$Matches[2]
        Right = [int]$Matches[3]
        Bottom = [int]$Matches[4]
        X = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        Y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
}

function Invoke-PageUiTap {
    param(
        [Parameter(Mandatory = $true)]$Dump,
        [Parameter(Mandatory = $true)][string]$Id,
        [string]$Purpose = "点击"
    )
    $node = Find-PageUiNode $Dump $Id
    if (!$node) {
        Add-PageTestResult FAIL "action" $Id "$Purpose：找不到控件"
        return $false
    }
    if ([string]$node.enabled -ne "true") {
        Add-PageTestResult SKIP "action" $Id "$Purpose：当前状态禁用，需按文档满足前置状态"
        return $false
    }
    $point = Get-PageNodeCenter $node
    [void](Invoke-PageAdb @("shell", "input", "tap", "$($point.X)", "$($point.Y)"))
    Start-Sleep -Milliseconds 650
    Add-PageTestResult PASS "action" $Id "$Purpose：已触发"
    return $true
}

function Invoke-PageLongPressAt {
    param(
        [Parameter(Mandatory = $true)][int]$X,
        [Parameter(Mandatory = $true)][int]$Y,
        [int]$DurationMs = 900,
        [Parameter(Mandatory = $true)][string]$Target
    )
    [void](Invoke-PageAdb @("shell", "input", "swipe", "$X", "$Y", "$X", "$Y", "$DurationMs"))
    Start-Sleep -Milliseconds 350
    Add-PageTestResult PASS "action" $Target "长按 ${DurationMs}ms 并松开"
}

function Test-PageLayoutIds {
    param(
        [Parameter(Mandatory = $true)][string[]]$RelativeLayouts,
        [Parameter(Mandatory = $true)][string[]]$Ids
    )
    foreach ($layout in $RelativeLayouts) {
        $path = Join-Path $script:PageTestContext.RepoRoot $layout
        $content = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        foreach ($id in $Ids) {
            if ($content -match [regex]::Escape("@+id/$id")) {
                Add-PageTestResult PASS "layout" "$layout::$id" "布局声明存在"
            } else {
                Add-PageTestResult FAIL "layout" "$layout::$id" "布局缺少该 ID"
            }
        }
    }
}

function Test-PageSourcePatterns {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][hashtable]$Patterns
    )
    $path = Join-Path $script:PageTestContext.RepoRoot $RelativePath
    $content = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    foreach ($target in ($Patterns.Keys | Sort-Object)) {
        if ([regex]::IsMatch(
            $content,
            [string]$Patterns[$target],
            [System.Text.RegularExpressions.RegexOptions]::Singleline
        )) {
            Add-PageTestResult PASS "binding" $target "代码绑定存在：$RelativePath"
        } else {
            Add-PageTestResult FAIL "binding" $target "未匹配代码绑定：$RelativePath"
        }
    }
}

function Enter-PageByNavigation {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("home", "map", "remote", "status")][string]$Page
    )
    $id = switch ($Page) {
        "home" { "navHome" }
        "map" { "navMap" }
        "remote" { "navRemote" }
        "status" { "navStatus" }
    }
    $dump = Get-PageUiDump "before-nav-$Page"
    if (!(Invoke-PageUiTap $dump $id "切换到 $Page 页面")) {
        return $null
    }
    return Get-PageUiDump $Page
}

function Test-MainPageReady {
    param([Parameter(Mandatory = $true)]$Dump)
    $node = Find-PageUiNode $Dump "navHome"
    if (!$node) {
        Add-PageTestResult FAIL "precondition" "MainActivity" "未进入四页面工作台；请先登录并选择设备"
        return $false
    }
    Add-PageTestResult PASS "precondition" "MainActivity" "工作台已打开"
    return $true
}

function Complete-PageTest {
    $jsonPath = Join-Path $script:PageTestContext.OutputDir "results.json"
    $mdPath = Join-Path $script:PageTestContext.OutputDir "results.md"
    $script:PageTestResults | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

    $pass = @($script:PageTestResults | Where-Object status -eq "PASS").Count
    $fail = @($script:PageTestResults | Where-Object status -eq "FAIL").Count
    $skip = @($script:PageTestResults | Where-Object status -eq "SKIP").Count
    $info = @($script:PageTestResults | Where-Object status -eq "INFO").Count
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("# $($script:PageTestContext.Page) 页面测试结果")
    $lines.Add("")
    $lines.Add("- PASS: $pass")
    $lines.Add("- FAIL: $fail")
    $lines.Add("- SKIP: $skip")
    $lines.Add("- INFO: $info")
    $lines.Add("")
    $lines.Add("| 状态 | 类型 | 目标 | 说明 |")
    $lines.Add("|---|---|---|---|")
    foreach ($item in $script:PageTestResults) {
        $message = ([string]$item.message).Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
        $lines.Add("| $($item.status) | $($item.kind) | $($item.target) | $message |")
    }
    $lines | Set-Content -LiteralPath $mdPath -Encoding UTF8
    Write-Host ""
    Write-Host "结果：PASS=$pass FAIL=$fail SKIP=$skip INFO=$info"
    Write-Host "报告：$mdPath"
    return $(if ($fail -gt 0) { 1 } else { 0 })
}
