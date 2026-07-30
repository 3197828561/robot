# 地图页测试与数据流说明

## 1. 运行测试

```powershell
# 仅核对布局 ID、按钮绑定、MQTT/HTTP/缓存代码链
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-map.ps1 -StaticOnly

# 在当前连接的模拟器/测试机上逐按钮执行无副作用地图操作
powershell -ExecutionPolicy Bypass -File tools\page-tests\test-map.ps1
```

地图与轨迹联调数据可由现有脚本发布：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\map-pose-path-sim.ps1 -MapUrl "https://测试服务器/map.json"
```

## 2. 完整数据流

```text
Robot 发布 map 通知
  topic: device/{productType}/{deviceId}/map
  payload: MapMessage(mapId,mapVersion,mapJsonUrl,fileSizeBytes,checksum...)
  -> CloudCommMqttManager.handleMessage()
  -> isValidEnvelope(version/deviceId/productType)
  -> handleMapMessage()
  -> HTTP GET mapJsonUrl
  -> 大小/sha256/mapId/version 校验
  -> APP cacheDir/maps/{productType}/{deviceId}/{mapId}_{version}.json
  -> PvMapParser.parse()
  -> MapUiState
  -> MainViewModel.mapState
  -> MainActivity.bindMap()
  -> mapPageView.setMap()

Robot 发布 pose
  topic: device/{productType}/{deviceId}/pose
  -> PoseMessage
  -> MainViewModel.pose
  -> MainActivity.bindPose()
  -> PvMapParser.resolvePose(PvMap, PoseMessage)
  -> MapPosition + 最近 10 秒轨迹
  -> mapPageView.setRobot(position, history)
```

地图 JSON 不是从 MQTT payload 直接渲染；MQTT 只传通知和下载地址，地图文件通过 HTTP 下载后校验、缓存、解析。

## 3. 按钮逐项说明

| ID | 功能 | 调用链 | 网络副作用 | 验收标准 |
|---|---|---|---|---|
| `btnMapReset` | 复位视图 | `MainActivity -> mapPageView.resetViewport()` | 无 | 恢复显示完整地图，不改变地图/pose 数据 |
| `btnMapCenter` | 机器人居中 | `centerMapOnRobot() -> centerRobot()` | 无 | 有有效 pose 时居中；无位置时显示明确提示 |
| `btnMapZoomIn` | 放大 | `mapPageView.zoomIn()` | 无 | 比例增大，仍受 View 的最大比例限制 |
| `btnMapZoomOut` | 缩小 | `mapPageView.zoomOut()` | 无 | 比例减小，仍受最小比例限制 |
| `btnMapLocate` | 定位机器人 | 与居中相同 | 无 | 机器人进入画面中心 |
| `navHome/navRemote/navStatus` | 切换页面 | `MainActivity.showPage()` | 离开手动页时可能发零速；地图页本身无 | 仅目标 section 可见 |

地图画布还支持 View 内部的触摸缩放/平移；如甲方修改手势，应在地图自定义 View 的 touch/scale 逻辑中修改，不应放到 Activity。

## 4. 字段与画布逐项说明

| ID | 内容 | 来源 | 空/失败行为 |
|---|---|---|---|
| `mapPageView` | 光伏板 cell、桥接区、机器人、轨迹 | `PvMap` + `PoseMessage` | 无 READY 地图时不绘制机器人 |
| `tvMapPageState` | 暂无地图/正在加载/地图已加载/加载失败 | `MapUiState.status` | READY 后隐藏 |
| `tvMapPageMeta` | `■ 光伏板区域（N）` | `PvMap.cells.size` | 无地图时仅显示图例名称 |
| `tvMapBridgeLegend` | `■ 板间桥接区域（N）` | `PvMap.bridges.size` | 无地图时仅显示图例名称 |

与地图相关但展示在主页/详情页的字段：

- `tvMapMeta`：来自 `MapMessage.mapName/mapId/mapVersion`；
- 详情页 `mapId/mapVersion`：来自 `currentMapState.map`；
- 详情页 `blockId/cellId/heading`：来自最新 `PoseMessage`。

## 5. 接口字段和校验

### MapMessage

| 字段 | 用途 | 必要性 |
|---|---|---|
| `version` | 协议版本 | 必须与 APP 支持版本相符 |
| `deviceId/productType` | 设备身份隔离 | 必须匹配当前选中设备 |
| `mapId/mapVersion` | 缓存键、内容一致性校验 | 下载有效地图时必需 |
| `mapName` | 主页元信息 | 可空 |
| `mapJsonUrl` | HTTP 下载地址 | 新地图下载必需 |
| `fileSizeBytes` | 下载大小校验 | 可空；存在时必须一致 |
| `checksum` | SHA-256 校验 | 可空；存在时必须一致 |

### PoseMessage

`mapId/mapVersion` 必须能与当前地图对应；`blockId/cellRow/cellCol/innerRow/innerCol` 用来解析画布位置，`headingCode/heading` 用来确定朝向。无法解析的 pose 不应伪造坐标，而是不显示机器人。

## 6. 缓存和异常逻辑

- 缓存目录是 APP 私有 `cacheDir`，不是 Room。
- 当前地图通知会写入 SharedPreferences，重启后可恢复同一设备最后地图。
- 强制重载会先删除明确的当前缓存文件再下载。
- 最大地图文件为 20 MiB。
- HTTP 非成功、空响应、大小不符、checksum 不符、mapId/version 不符、JSON 解析失败都会进入 FAILED 或保留旧地图并显示失败原因。
- 地图日志写入结构化 Room 日志，但地图二进制/JSON 本体不写入数据库。

## 7. 甲方变更定位

- 地图页布局和按钮：两套 `activity_main.xml` 的 `sectionMap`。
- 地图颜色、绘制、缩放和平移：地图自定义 View。
- 地图 JSON 数据结构：`map/PvMapModels.kt`。
- 地图 JSON 解析和 pose 坐标换算：`map/PvMapParser.kt`。
- MQTT map/pose DTO：`network/mqtt/MqttModels.kt`。
- 下载、校验、缓存、Topic：`CloudCommMqttManager.kt`。
- 页面状态文字和图例数量：`MainActivity.bindMap()/bindPose()`。

## 8. 人工验收清单

1. 首次无缓存：发布 map 通知，观察下载中、READY、完整地图。
2. 发布连续 pose：机器人位置和朝向正确，轨迹只保留最近约 10 秒。
3. 逐个点击复位、居中、放大、缩小、定位并截图对比。
4. 测试错误 URL、404、超 20 MiB、错误 checksum、错误 mapId/version。
5. 断网后重启 APP，验证同一设备缓存恢复；切换设备不得串用缓存。
6. phone 与 sw600dp 平板均检查按钮不遮挡、图例不截断。

