param(
    [string]$Serial = "",
    [string]$OutputDir = "",
    [switch]$StaticOnly,
    [switch]$AllowRobotCommands
)

. (Join-Path $PSScriptRoot "..\PageTest.Common.ps1")
$context = Start-PageTest -Page "manual-control" -Serial $Serial -OutputDir $OutputDir -StaticOnly:$StaticOnly

$mainLayouts = @(
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/layout-sw600dp/activity_main.xml"
)
$speedLayouts = @(
    "app/src/main/res/layout/view_manual_speed_control.xml",
    "app/src/main/res/layout-sw600dp/view_manual_speed_control.xml"
)
$mainButtons = @("btnEnterManualMode", "btnReturnAutoMode", "btnRemoteStop", "btnRemoteEmergency")
$speedButtons = @(
    "btnSpeedSlow", "btnSpeedStandard", "btnSpeedHigh",
    "btnLinearMinus", "btnLinearPlus", "btnAngularMinus", "btnAngularPlus"
)
$fields = @(
    "tvRemoteModeState", "directionPad", "tvRemoteHint", "tvRemoteStatus",
    "manualSpeedControl", "sliderLinearSpeed", "sliderAngularSpeed",
    "tvLinearSpeedValue", "tvAngularSpeedValue"
)
Test-PageLayoutIds $mainLayouts ($mainButtons + @("tvRemoteModeState", "directionPad", "tvRemoteHint", "tvRemoteStatus", "manualSpeedControl", "navRemote"))
Test-PageLayoutIds $speedLayouts ($speedButtons + @("sliderLinearSpeed", "sliderAngularSpeed", "tvLinearSpeedValue", "tvAngularSpeedValue"))
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/ui/main/MainActivity.kt" @{
    "进入手动模式" = 'btnEnterManualMode\.setOnClickListener.*enterRemoteMode'
    "切回自动模式" = 'btnReturnAutoMode\.setOnClickListener.*exitRemoteMode'
    "普通停止" = 'btnRemoteStop\.setOnClickListener'
    "紧急停止" = 'btnRemoteEmergency\.setOnClickListener'
    "方向按下" = 'onPress\(direction:\s*ManualDirection\).*startRemote'
    "方向松开" = 'onRelease\(\).*stopRemote'
    "多方向冲突" = 'onConflict\(\).*ordinaryRemoteStop'
    "速度设置回调" = 'manualSpeedControl\.onSettingsChanged\s*=\s*viewModel::setManualSpeedSettings'
    "手动状态字段" = 'tvRemoteStatus\.text\s*=\s*remoteDetails'
}
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/ui/main/ManualSpeedControlView.kt" @{
    "慢中快三档按钮" = 'presetGroup\.addOnButtonCheckedListener'
    "线速度Slider" = 'linearSlider\.addOnChangeListener'
    "角速度Slider" = 'angularSlider\.addOnChangeListener'
    "线速度减按钮" = 'btnLinearMinus.*setOnClickListener'
    "线速度加按钮" = 'btnLinearPlus.*setOnClickListener'
    "角速度减按钮" = 'btnAngularMinus.*setOnClickListener'
    "角速度加按钮" = 'btnAngularPlus.*setOnClickListener'
    "线速度显示字段" = 'linearValue\.text'
    "角速度显示字段" = 'angularValue\.text'
}
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/viewmodel/ManualSpeedSettings.kt" @{
    "UI线速度范围" = 'UI_MAX_LINEAR_SPEED_CMS\s*=\s*RemoteControlContract\.MAX_LINEAR_SPEED_CMS'
    "UI角速度范围" = 'UI_MAX_ANGULAR_SPEED_RADPS\s*=\s*RemoteControlContract\.MAX_ANGULAR_SPEED_RADPS'
    "方向符号参与线速度" = 'normalized\.linearSpeedCms\s*\*\s*direction\.linearSign'
    "方向符号参与角速度" = 'normalized\.angularSpeedRadps\s*\*\s*direction\.angularSign'
}
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/ui/main/DirectionPadView.kt" @{
    "前进正线速度" = 'FORWARD\(1,\s*0\)'
    "后退负线速度" = 'BACKWARD\(-1,\s*0\)'
    "左转正角速度" = 'LEFT\(0,\s*1\)'
    "右转负角速度" = 'RIGHT\(0,\s*-1\)'
}
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/network/mqtt/CloudCommMqttManager.kt" @{
    "remote发布" = 'publishRemote\(linearSpeedCms:\s*Double'
    "remote Topic" = 'topicRemote\(productType,\s*deviceId\)'
    "线速度限幅" = 'RemoteControlContract\.clampLinear'
    "角速度限幅" = 'RemoteControlContract\.clampAngular'
}

if (!$StaticOnly) {
    Open-PageTestApp
    $initial = Get-PageUiDump "launch"
    if (Test-MainPageReady $initial) {
        $dump = Enter-PageByNavigation "remote"
        if ($dump) {
            foreach ($id in $mainButtons) { [void](Test-PageUiNode $dump $id "button") }
            foreach ($id in $speedButtons) { [void](Test-PageUiNode $dump $id "button") }
            $accessibilityOptional = @("directionPad", "manualSpeedControl", "sliderLinearSpeed", "sliderAngularSpeed")
            foreach ($id in $fields) {
                if ($id -in $accessibilityOptional) {
                    $reason = if ($id.StartsWith("slider")) {
                        "Material Slider 未暴露资源 ID；范围/步进由布局审计覆盖，拖动与边界由人工触控验收"
                    } else {
                        "自定义/容器控件可能不暴露给 UIAutomator；由子控件、绑定审计和截图核对"
                    }
                    [void](Test-PageUiNode $dump $id "field" -AllowMissing -MissingMessage $reason)
                } else {
                    [void](Test-PageUiNode $dump $id "field")
                }
            }

            foreach ($id in @("btnSpeedSlow", "btnSpeedStandard", "btnSpeedHigh")) {
                $current = Get-PageUiDump "before-$id"
                if (Invoke-PageUiTap $current $id "速度预设") {
                    $after = Get-PageUiDump "after-$id"
                    [void](Test-PageUiNode $after "tvLinearSpeedValue" "field")
                    [void](Test-PageUiNode $after "tvAngularSpeedValue" "field")
                }
            }
            foreach ($id in @("btnLinearMinus", "btnLinearPlus", "btnAngularMinus", "btnAngularPlus")) {
                $current = Get-PageUiDump "before-$id"
                [void](Invoke-PageUiTap $current $id "速度步进调整")
            }

            if ($AllowRobotCommands) {
                Add-PageTestResult INFO "safety" "robot-commands" "已显式允许模式切换、运动和停止指令"
                $current = Get-PageUiDump "before-manual"
                [void](Invoke-PageUiTap $current "btnEnterManualMode" "切换机器人手动模式")
                Start-Sleep -Milliseconds 1200
                $current = Get-PageUiDump "manual-mode"
                $pad = Find-PageUiNode $current "directionPad"
                if ($pad -and [string]$pad.enabled -eq "true") {
                    $box = Get-PageNodeCenter $pad
                    $thirdX = [int](($box.Right - $box.Left) / 3)
                    $thirdY = [int](($box.Bottom - $box.Top) / 3)
                    $points = @(
                        @{ Name = "forward"; X = $box.Left + $thirdX + [int]($thirdX / 2); Y = $box.Top + [int]($thirdY / 2) },
                        @{ Name = "left"; X = $box.Left + [int]($thirdX / 2); Y = $box.Top + $thirdY + [int]($thirdY / 2) },
                        @{ Name = "right"; X = $box.Left + 2 * $thirdX + [int]($thirdX / 2); Y = $box.Top + $thirdY + [int]($thirdY / 2) },
                        @{ Name = "backward"; X = $box.Left + $thirdX + [int]($thirdX / 2); Y = $box.Top + 2 * $thirdY + [int]($thirdY / 2) }
                    )
                    foreach ($point in $points) {
                        Invoke-PageLongPressAt $point.X $point.Y 900 "direction-$($point.Name)"
                    }
                } else {
                    Add-PageTestResult SKIP "action" "directionPad" "机器人尚未通过 status 确认 manual/normal，方向键不可用"
                }
                $current = Get-PageUiDump "before-stop"
                [void](Invoke-PageUiTap $current "btnRemoteStop" "普通停止")
                $current = Get-PageUiDump "before-auto"
                [void](Invoke-PageUiTap $current "btnReturnAutoMode" "切回自动模式")
                Add-PageTestResult SKIP "action" "btnRemoteEmergency" "急停需在隔离环境单独确认，脚本不与方向测试串行触发"
            } else {
                foreach ($id in @("btnEnterManualMode", "directionPad", "btnRemoteStop", "btnRemoteEmergency", "btnReturnAutoMode")) {
                    Add-PageTestResult SKIP "action" $id "未传入 -AllowRobotCommands；未向机器人发送控制消息"
                }
            }
            [void](Save-PageScreenshot "manual-control-final")
        }
    }
}

exit (Complete-PageTest)
