param(
    [string]$Serial = "",
    [string]$OutputDir = "",
    [switch]$StaticOnly
)

. (Join-Path $PSScriptRoot "..\PageTest.Common.ps1")
$context = Start-PageTest -Page "status" -Serial $Serial -OutputDir $OutputDir -StaticOnly:$StaticOnly

$layouts = @(
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/layout-sw600dp/activity_main.xml"
)
Test-PageLayoutIds $layouts @("sectionStatus", "tvStatusDetails", "navStatus")

$statusFieldPatterns = @{
    "HTTP display_name" = '设备名称 display_name.*deviceDisplayName'
    "HTTP device_id" = '设备编号 device_id.*deviceId'
    "HTTP product_type" = '设备类型 product_type.*productType'
    "BuildConfig APP版本" = 'APP版本.*BuildConfig\.VERSION_NAME'
    "BuildConfig 接口能力" = '任务接口能力.*BuildConfig\.MISSION_COMMAND_API_CAPABILITY'
    "status timestamp" = '状态消息时间 timestamp.*status\?\.timestamp'
    "status workStatus" = '工作状态 workStatus.*status\?\.let'
    "status controlMode" = '控制模式 controlMode.*status\?\.let'
    "status batteryPercent" = '电量 batteryPercent.*status\?\.batteryPercent'
    "status linearSpeedCms" = '线速度 linearSpeedCms.*status\?\.linearSpeedCms'
    "status angularSpeedRadps" = '角速度 angularSpeedRadps.*status\?\.angularSpeedRadps'
    "status deviceStatus" = '设备状态 deviceStatus.*status\?\.let'
    "status movementStatus" = '运动状态 movementStatus.*status\?\.let'
    "status missionId" = '任务编号 missionId.*status\?\.missionId'
    "status taskKind" = '任务类型 taskKind.*status\?\.taskKind'
    "status runState" = '"runState：\$\{status\?\.runState'
    "status operationalMode" = '运行模式 operationalMode.*status\?\.operationalMode'
    "status safetyState" = '安全状态 safetyState.*status\?\.safetyState'
    "status phase" = '任务阶段 phase.*status\?\.phase'
    "status activeAction" = '当前动作 activeAction.*status\?\.activeAction'
    "status waypointIndex" = '航点索引 waypointIndex.*status\?\.waypointIndex'
    "status waypointCount" = '航点总数 waypointCount.*status\?\.waypointCount'
    "status errorCode" = '错误码 errorCode.*status\?\.missionErrorCode'
    "status errorRetryable" = '错误可重试 errorRetryable.*status\?\.errorRetryable'
    "status errorSource" = '错误来源 errorSource.*status\?\.errorSource'
    "status errorMessage" = '错误信息 errorMessage.*status\?\.errorMessage'
    "map mapId" = '地图编号 mapId.*currentMapState\.map\?\.mapId'
    "map mapVersion" = '地图版本 mapVersion.*currentMapState\.map\?\.mapVersion'
    "pose blockId" = '当前区域 blockId.*currentPose\?\.blockId'
    "pose cellId" = '当前单元 cellId.*currentPose\?\.cellId'
    "pose heading" = '机器人朝向 heading'
    "heartbeat 接收时间" = 'APP最近收到心跳.*tvLastHeartbeat'
}
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/ui/main/MainActivity.kt" $statusFieldPatterns
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/ui/main/MainActivity.kt" @{
    "详情页导航按钮" = 'navStatus\.setOnClickListener\s*\{\s*showPage\(Page\.STATUS\)'
    "详情文本绑定" = 'tvStatusDetails\.text\s*=\s*details'
}
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/network/mqtt/CloudCommMqttManager.kt" @{
    "heartbeat解析入口" = 'topic\.endsWith\("/heartbeat"\)'
    "status解析入口" = 'topic\.endsWith\("/status"\)'
    "map解析入口" = 'topic\.endsWith\("/map"\)'
    "pose解析入口" = 'topic\.endsWith\("/pose"\)'
    "消息身份校验" = 'isValidEnvelope\(obj,\s*topic\)'
}

if (!$StaticOnly) {
    Open-PageTestApp
    $initial = Get-PageUiDump "launch"
    if (Test-MainPageReady $initial) {
        $dump = Enter-PageByNavigation "status"
        if ($dump) {
            $node = Test-PageUiNode $dump "tvStatusDetails" "field" "设备列表 API"
            if ($node) {
                $text = [string]$node.text
                foreach ($label in @(
                    "display_name", "device_id", "product_type", "APP版本",
                    "timestamp", "workStatus", "controlMode", "batteryPercent",
                    "linearSpeedCms", "angularSpeedRadps", "deviceStatus", "movementStatus",
                    "missionId", "taskKind", "runState", "operationalMode", "safetyState",
                    "phase", "activeAction", "waypointIndex", "waypointCount",
                    "errorCode", "errorRetryable", "errorSource", "errorMessage",
                    "mapId", "mapVersion", "blockId", "cellId", "heading", "APP最近收到心跳"
                )) {
                    if ($text.Contains($label)) {
                        Add-PageTestResult PASS "field-value" $label "详情文本包含该真实字段标签"
                    } else {
                        Add-PageTestResult FAIL "field-value" $label "详情文本缺少该字段标签"
                    }
                }
            }
            Add-PageTestResult INFO "action" "status-page" "本页无业务按钮，仅验证底部导航和所有字段；上下滚动由人工视觉检查"
            [void](Save-PageScreenshot "status-final")
        }
    }
}

exit (Complete-PageTest)
