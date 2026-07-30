param(
    [string]$Serial = "",
    [string]$OutputDir = "",
    [switch]$StaticOnly,
    [switch]$AllowRobotCommands
)

. (Join-Path $PSScriptRoot "PageTest.Common.ps1")
$context = Start-PageTest -Page "home" -Serial $Serial -OutputDir $OutputDir -StaticOnly:$StaticOnly

$layouts = @(
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/layout-sw600dp/activity_main.xml"
)
$buttons = @(
    "btnDeviceList", "btnReloadMap", "btnCenterRobot",
    "btnStart", "btnStopRun", "btnPause", "btnResume", "btnReplan",
    "btnEmergency", "btnClearEstop", "btnRetryCommand", "btnViewLogs"
)
$fields = @(
    "tvDeviceName", "tvToolbarTime", "batteryIndicator",
    "mapPreviewView", "tvMapState", "tvMapMeta",
    "tvHomeOnline", "tvHomeWorkStatus", "tvHomeControlMode", "tvHomeBattery",
    "tvHomeLinearSpeed", "tvHomeAngularSpeed", "tvHomeDeviceStatus",
    "tvHomeMovementStatus", "commandHistoryTable", "tvCommandState"
)
$navigation = @("navHome", "navMap", "navRemote", "navStatus")
Test-PageLayoutIds $layouts ($buttons + $fields + $navigation)
Test-PageLayoutIds @("app/src/main/res/layout/dialog_coverage_task.xml") @(
    "tvCoverageMap", "cbUseCurrentPose", "groupCoverageStart",
    "etStartBlockId", "etStartCellRow", "etStartCellCol",
    "etStartInnerRow", "etStartInnerCol", "etStartHeading",
    "etTargetBlockIds", "cbGlobalPlan"
)
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/ui/main/MainActivity.kt" @{
    "返回设备列表按钮" = 'btnDeviceList\.setOnClickListener.*shutdownMqtt\(\).*DeviceListActivity'
    "地图重载按钮" = 'btnReloadMap\.setOnClickListener\s*\{\s*viewModel\.retryMapDownload'
    "地图居中按钮" = 'btnCenterRobot\.setOnClickListener'
    "开始任务按钮" = 'btnStart\.setOnClickListener\s*\{\s*showCoverageTaskDialog'
    "停止任务按钮" = 'btnStopRun\.setOnClickListener.*sendMissionCommand\("停止任务",\s*"stop"\)'
    "暂停任务按钮" = 'btnPause\.setOnClickListener.*"pause"'
    "恢复任务按钮" = 'btnResume\.setOnClickListener.*"resume"'
    "重新规划按钮" = 'btnReplan\.setOnClickListener.*"replan"'
    "紧急停止按钮" = 'btnEmergency\.setOnClickListener.*"estop"'
    "解除急停按钮" = 'btnClearEstop\.setOnClickListener.*"clear_estop"'
    "失败重试按钮" = 'btnRetryCommand\.setOnClickListener.*retryLastCommand'
    "日志入口按钮" = 'btnViewLogs\.setOnClickListener'
    "首页导航" = 'navHome\.setOnClickListener\s*\{\s*showPage\(Page\.HOME\)'
    "地图导航" = 'navMap\.setOnClickListener\s*\{\s*showPage\(Page\.MAP\)'
    "手动导航" = 'navRemote\.setOnClickListener\s*\{\s*showPage\(Page\.REMOTE\)'
    "详情导航" = 'navStatus\.setOnClickListener\s*\{\s*showPage\(Page\.STATUS\)'
    "开始弹窗当前位姿" = 'cbUseCurrentPose\.isChecked'
    "开始弹窗目标区域" = 'etTargetBlockIds\.text'
    "开始弹窗显式起点" = 'CoverageStart\(\s*blockId\s*=\s*blockId'
    "开始弹窗全局规划" = 'globalPlan\s*=\s*dialogBinding\.cbGlobalPlan\.isChecked'
    "在线状态字段" = 'tvHomeOnline.*deviceOnline'
    "工作状态字段" = 'tvHomeWorkStatus.*workStatus'
    "控制模式字段" = 'tvHomeControlMode.*controlMode'
    "电量字段" = 'tvHomeBattery.*batteryPercent'
    "线速度字段" = 'tvHomeLinearSpeed.*linearSpeedCms'
    "角速度字段" = 'tvHomeAngularSpeed.*angularSpeedRadps'
    "设备状态字段" = 'tvHomeDeviceStatus.*deviceStatus'
    "运动状态字段" = 'tvHomeMovementStatus.*movementStatus'
    "命令历史字段" = 'recentCommandLogs\.observe'
}
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/viewmodel/MainViewModel.kt" @{
    "start地图参数" = 'CoverageCommandParams\(\s*mapId\s*=\s*map\.mapId'
    "start目标校验" = 'targetBlockIds.*distinct'
    "start起点校验" = 'selection\.useCurrentPose'
    "任务命令目标missionId" = 'mapOf\("targetMissionId"\s*to\s*missionId\)'
    "命令防抖" = 'if\s*\(!debounce\(\)\)'
    "命令发布" = 'mqtt\.publishCmd\(command\)'
}

if (!$StaticOnly) {
    Open-PageTestApp
    $initial = Get-PageUiDump "launch"
    if (Test-MainPageReady $initial) {
        $dump = Enter-PageByNavigation "home"
        if ($dump) {
            foreach ($id in $buttons) {
                if ($id -eq "btnRetryCommand") {
                    [void](Test-PageUiNode $dump $id "button" -AllowMissing -MissingMessage "仅失败/超时状态启用，且可能位于当前滚动视口外；按文档构造失败态补测")
                } else {
                    [void](Test-PageUiNode $dump $id "button")
                }
            }
            $accessibilityOptional = @("batteryIndicator", "mapPreviewView", "commandHistoryTable", "tvMapState", "tvCommandState")
            foreach ($id in $fields) {
                if ($id -in $accessibilityOptional) {
                    $reason = switch ($id) {
                        "tvMapState" { "地图 READY 时按设计隐藏；NO_MAP/DOWNLOADING/FAILED 状态需专项补测" }
                        "tvCommandState" { "可能位于当前滚动视口外；由布局/绑定审计及命令专项人工补测" }
                        default { "自定义绘制控件可能不暴露给 UIAutomator；由布局审计和截图人工核对" }
                    }
                    [void](Test-PageUiNode $dump $id "field" -AllowMissing -MissingMessage $reason)
                } else {
                    [void](Test-PageUiNode $dump $id "field")
                }
            }
            foreach ($id in $navigation) { [void](Test-PageUiNode $dump $id "navigation") }

            [void](Invoke-PageUiTap $dump "btnCenterRobot" "验证预览地图居中")
            $dump = Get-PageUiDump "after-center"
            [void](Invoke-PageUiTap $dump "btnReloadMap" "验证地图重新加载入口")

            $dump = Get-PageUiDump "before-log"
            if (Invoke-PageUiTap $dump "btnViewLogs" "打开日志页面") {
                $logDump = Get-PageUiDump "log-page"
                $hasLogPage = @($logDump.Xml.SelectNodes("//node") | Where-Object {
                    ([string]$_.text) -match "日志"
                }).Count -gt 0
                if ($hasLogPage) {
                    Add-PageTestResult PASS "navigation" "btnViewLogs" "日志页面已打开"
                } else {
                    Add-PageTestResult FAIL "navigation" "btnViewLogs" "点击后未识别到日志页面"
                }
                [void](Invoke-PageAdb @("shell", "input", "keyevent", "4"))
                Start-Sleep -Milliseconds 500
            }

            $commandButtons = @(
                "btnStart", "btnStopRun", "btnPause", "btnResume", "btnReplan",
                "btnEmergency", "btnClearEstop", "btnRetryCommand"
            )
            if ($AllowRobotCommands) {
                Add-PageTestResult INFO "safety" "robot-commands" "已显式允许机器人指令；仅触发当前启用按钮"
                foreach ($id in $commandButtons) {
                    $current = Get-PageUiDump "before-$id"
                    [void](Invoke-PageUiTap $current $id "机器人指令按钮测试")
                    if ($id -eq "btnStart") {
                        [void](Invoke-PageAdb @("shell", "input", "keyevent", "4"))
                    }
                }
            } else {
                foreach ($id in $commandButtons) {
                    Add-PageTestResult SKIP "action" $id "未传入 -AllowRobotCommands；只完成布局、绑定和运行状态检查"
                }
            }
            [void](Save-PageScreenshot "home-final")
        }
    }
}

exit (Complete-PageTest)
