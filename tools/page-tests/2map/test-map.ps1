param(
    [string]$Serial = "",
    [string]$OutputDir = "",
    [switch]$StaticOnly
)

. (Join-Path $PSScriptRoot "..\PageTest.Common.ps1")
$context = Start-PageTest -Page "map" -Serial $Serial -OutputDir $OutputDir -StaticOnly:$StaticOnly

$layouts = @(
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/layout-sw600dp/activity_main.xml"
)
$buttons = @("btnMapReset", "btnMapCenter", "btnMapZoomIn", "btnMapZoomOut", "btnMapLocate")
$fields = @("mapPageView", "tvMapPageState", "tvMapPageMeta", "tvMapBridgeLegend")
Test-PageLayoutIds $layouts ($buttons + $fields + @("navMap"))
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/ui/main/MainActivity.kt" @{
    "地图复位按钮" = 'btnMapReset\.setOnClickListener.*resetViewport'
    "地图居中按钮" = 'btnMapCenter\.setOnClickListener.*centerMapOnRobot'
    "地图放大按钮" = 'btnMapZoomIn\.setOnClickListener.*zoomIn'
    "地图缩小按钮" = 'btnMapZoomOut\.setOnClickListener.*zoomOut'
    "机器人定位按钮" = 'btnMapLocate\.setOnClickListener.*centerMapOnRobot'
    "地图加载状态" = 'tvMapPageState\.text\s*=\s*stateText'
    "光伏板数量" = 'tvMapPageMeta\.text.*cells\.size'
    "桥接区域数量" = 'tvMapBridgeLegend\.text.*bridges\.size'
    "地图模型绑定" = 'mapPageView\.setMap\(readyMap\)'
    "位姿模型绑定" = 'mapPageView\.setRobot\(position,\s*history\)'
}
Test-PageSourcePatterns "app/src/main/java/com/robot/solar/network/mqtt/CloudCommMqttManager.kt" @{
    "MQTT map订阅" = 'topicMap\(productType,\s*deviceId\)'
    "MQTT pose订阅" = 'topicPose\(productType,\s*deviceId\)'
    "地图HTTP下载" = 'httpClient\.newCall\(request\)'
    "地图缓存" = 'cacheFileFor\(mapId,\s*version\)'
    "地图校验" = 'verifyChecksumIfNeeded'
}

if (!$StaticOnly) {
    Open-PageTestApp
    $initial = Get-PageUiDump "launch"
    if (Test-MainPageReady $initial) {
        $dump = Enter-PageByNavigation "map"
        if ($dump) {
            foreach ($id in $buttons) { [void](Test-PageUiNode $dump $id "button") }
            [void](Test-PageUiNode $dump "mapPageView" "field" -AllowMissing -MissingMessage "自绘地图不暴露给 UIAutomator；由截图人工核对地图、机器人和轨迹")
            [void](Test-PageUiNode $dump "tvMapPageState" "field" -AllowMissing -MissingMessage "地图 READY 时该状态字段按设计隐藏")
            [void](Test-PageUiNode $dump "tvMapPageMeta" "field")
            [void](Test-PageUiNode $dump "tvMapBridgeLegend" "field")
            foreach ($id in $buttons) {
                $current = Get-PageUiDump "before-$id"
                [void](Invoke-PageUiTap $current $id "无副作用地图操作")
            }
            [void](Save-PageScreenshot "map-final")
        }
    }
}

exit (Complete-PageTest)
