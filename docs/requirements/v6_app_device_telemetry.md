# APP 设备遥测接入说明

本文用于指导 APP 接入 `cloud_comm` 新增的 GPS、温度、总电流和姿态字段。字段的协议
定义以 [cloud_comm API](../API.md) 为准；本文只规定 APP 的订阅、数据模型、展示规则、
兼容策略和验收条件。

## 1. 改造结论

APP 不需要新增 MQTT Topic，也不需要通过 HTTP 查询这些数据。新增字段位于现有 `status`
消息中。

APP 需要完成：

1. 扩展 `status` 数据模型和 JSON 解析器；
2. 在设备详情页展示 GPS、机身温度、H7/RK3588 CPU 温度、总电流和姿态；
3. 正确处理 `null`、字段缺失、非法数值和设备离线；
4. 保留 H7 温度等当前没有有效上游数据的 UI 占位；
5. 增加新字段的解析、展示和兼容性测试。

## 2. MQTT 订阅

### 2.1 设备状态

```text
device/{productType}/{deviceId}/status
```

| 属性 | 值 |
| --- | --- |
| 方向 | Robot → APP |
| MQTT QoS | 0 |
| retain | `false` |
| 默认周期 | 1000ms，可由 Robot 参数修改 |
| Payload | UTF-8 JSON |

QoS 0 允许偶发丢包，APP 不应因为漏掉一帧立即清空页面。每次收到合法 `status` 后，用新值
整体更新设备状态。

### 2.2 在线状态

```text
device/{productType}/{deviceId}/heartbeat
```

APP 继续使用 `heartbeat` 判断设备在线。当前建议连续 3000ms 没有收到心跳后将设备标记为
离线。离线时可以保留最后一次数值用于诊断，但必须明显标记为“离线数据”或统一显示 `--`，
不能让用户误认为数据仍在实时更新。

## 3. 新增字段

这些字段在当前 Robot 版本中都会出现在 `status` JSON 中，但值可能是 `null`。为了兼容旧版
Robot 和灰度升级，APP 模型应同时允许字段缺失。

| 字段 | APP 类型 | 单位/取值 | 当前数据来源 | 展示建议 |
| --- | --- | --- | --- | --- |
| `latitudeDeg` | nullable number | `-90..90` 度 | `/fcu/status` | 纬度，建议保留 6 位小数 |
| `longitudeDeg` | nullable number | `-180..180` 度 | `/fcu/status` | 经度，建议保留 6 位小数 |
| `gpsStatus` | nullable integer | `0/1/2/3` | `/fcu/status` | 映射为无定位/2D/3D/RTK 固定 |
| `internalTemperatureCelsius` | nullable number | °C | `/system/internal_temperature` | 机身内部温度 |
| `h7CpuTemperatureCelsius` | nullable number | °C | `/fcu/h7_cpu_temperature` | H7 CPU 温度，保留占位 |
| `rk3588CpuTemperatureCelsius` | nullable number | °C | RK3588 thermal sysfs | RK3588 CPU/SoC 温度 |
| `totalCurrentAmpere` | nullable number | A | `/fcu/battery.current` | 整机总电流，保留符号 |
| `rollDeg` | nullable number | ° | `/fcu/status` | 横滚角 |
| `pitchDeg` | nullable number | ° | `/fcu/status` | 俯仰角 |
| `yawDeg` | nullable number | ° | `/fcu/status` | 航向角，正北为 0，顺时针增加 |
| `panelTiltDeg` | nullable number | `0..180` 度 | roll/pitch 推算 | 显示为“板面倾角估算” |

当前数据可用性：

| 字段组 | Robot 侧状态 |
| --- | --- |
| GPS、roll/pitch/yaw、板面倾角 | `rover_fcu_bridge` 的 `/fcu/status` 有有效数据时可用 |
| 机身内部温度 | Cloud 接口已完成，但仓库内暂时没有上游发布者 |
| H7 CPU 温度 | Cloud 占位接口已完成，FCU 协议和 bridge 暂未提供数据 |
| RK3588 CPU 温度 | Cloud 已读取 Linux thermal sysfs，需在目标板确认 thermal zone |
| 总电流 | Cloud 接口已完成，但 bridge 当前发布 `NaN`，所以通常为 `null` |

因此，APP 不应以 H7 温度、机身温度或总电流暂时为 `null` 判断协议异常。

## 4. APP 数据模型

以下为语言无关的模型示意。具体使用 Dart、Kotlin、Swift 或 TypeScript 时保持相同的可空
语义即可。

```text
DeviceTelemetry
  latitudeDeg: number?
  longitudeDeg: number?
  gpsStatus: integer?
  internalTemperatureCelsius: number?
  h7CpuTemperatureCelsius: number?
  rk3588CpuTemperatureCelsius: number?
  totalCurrentAmpere: number?
  rollDeg: number?
  pitchDeg: number?
  yawDeg: number?
  panelTiltDeg: number?
```

解析器要求：

- JSON `null` 解析为语言中的空值；
- 字段缺失也解析为空值，兼容旧 Robot；
- 字段类型不是 number，或数值为非有限值时按空值处理并记录诊断日志；
- 不使用 `0` 作为解析失败的默认值；
- 未识别的新字段直接忽略；
- 收到其他 `deviceId` 或 `productType` 的消息时不得更新当前设备页面。

不要将模型声明为不可空的基础数值类型，否则常见的默认值初始化会把“没有数据”错误显示
为 `0°C`、`0A` 或经纬度 `(0, 0)`。

## 5. GPS 处理

APP 只有在以下条件同时满足时才显示有效坐标或更新地图 GPS 标记：

```text
gpsStatus in [1, 2, 3]
AND latitudeDeg != null
AND longitudeDeg != null
AND latitudeDeg in [-90, 90]
AND longitudeDeg in [-180, 180]
```

状态文案：

| `gpsStatus` | 文案 |
| ---: | --- |
| `null` | GPS 状态未知 |
| `0` | 无定位 |
| `1` | 2D 定位 |
| `2` | 3D 定位 |
| `3` | RTK 固定解 |
| 其他 | 未知定位状态 |

即使 `gpsStatus` 为 `1..3`，只要任一坐标为空或越界，也必须隐藏 GPS 标记并显示“定位数据
不可用”。不能回退到 `(0, 0)`。

这里的经纬度是地理坐标，不替代 MQTT `pose` 中基于清扫地图的离散位置。APP 应继续使用
`pose` 在光伏板地图上显示机器人，用 `status` 经纬度显示设备地理位置。

## 6. 温度、电流和姿态展示

建议的展示格式：

| UI 名称 | 字段 | 有效值格式 | 空值格式 |
| --- | --- | --- | --- |
| 机身内部温度 | `internalTemperatureCelsius` | `36.5 °C` | `--` |
| H7 CPU 温度 | `h7CpuTemperatureCelsius` | `47.3 °C` | `--` |
| RK3588 CPU 温度 | `rk3588CpuTemperatureCelsius` | `58.3 °C` | `--` |
| 整机总电流 | `totalCurrentAmpere` | `-7.20 A` | `--` |
| 横滚角 | `rollDeg` | `3.0°` | `--` |
| 俯仰角 | `pitchDeg` | `4.0°` | `--` |
| 航向角 | `yawDeg` | `125.0°` | `--` |
| 板面倾角估算 | `panelTiltDeg` | `5.0°` | `--` |

显示规则：

- `totalCurrentAmpere` 必须保留正负号。上游尚未确认符号对应充电还是放电，因此 APP 暂时
  不显示“充电中/放电中”等推导状态，也不要取绝对值；
- `panelTiltDeg` 由 Cloud 使用 roll/pitch 合成，是机身平面的倾角。只有机器人机身与光伏板
  表面平行时，才可近似视为光伏板倾角；
- `panelTiltDeg` 的 UI 名称必须包含“估算”或等价提示，不能标为传感器直接测量值；
- 温度告警阈值当前不属于协议。产品侧没有确认阈值前，只展示数值，不自行变红或触发告警；
- APP 可以将 roll/pitch/yaw 放在设备诊断详情页，将板面倾角放在主要运行状态页。

## 7. Payload 示例

### 7.1 有部分有效数据

以下只展示与本次改造相关的字段；真实 `status` 还包含任务、底盘、电量和安全字段。

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-25T08:30:00.123Z",
  "latitudeDeg": 31.2304,
  "longitudeDeg": 121.4737,
  "gpsStatus": 3,
  "internalTemperatureCelsius": null,
  "h7CpuTemperatureCelsius": null,
  "rk3588CpuTemperatureCelsius": 58.25,
  "totalCurrentAmpere": null,
  "rollDeg": 3.0,
  "pitchDeg": 4.0,
  "yawDeg": 125.0,
  "panelTiltDeg": 4.998
}
```

APP 应显示 GPS、RK3588 温度和四个姿态值；机身温度、H7 温度和总电流显示 `--`。

### 7.2 所有新增传感数据不可用

```json
{
  "latitudeDeg": null,
  "longitudeDeg": null,
  "gpsStatus": null,
  "internalTemperatureCelsius": null,
  "h7CpuTemperatureCelsius": null,
  "rk3588CpuTemperatureCelsius": null,
  "totalCurrentAmpere": null,
  "rollDeg": null,
  "pitchDeg": null,
  "yawDeg": null,
  "panelTiltDeg": null
}
```

APP 必须正常渲染页面，所有对应位置显示 `--`/“暂无数据”，不得抛出解析异常。

## 8. 刷新与陈旧数据

- 每条合法 `status` 覆盖上一条相应字段，包括用 `null` 清除旧值；
- 不得采用“新值为 `null` 时继续保留旧有效值”的合并策略，否则传感器断开后仍会显示旧值；
- `status.timestamp` 是 Cloud 的组包时间，不是各传感器的独立采样时间；
- 当前协议没有每个传感器的采样时间，也没有底层字段过期标志；
- APP 只能可靠判断设备整体在线状态，不能仅凭数值长时间不变判断某个传感器故障；
- 心跳超时后，整组遥测都应进入离线/陈旧状态。

## 9. 兼容策略

APP 需要支持以下组合：

| 场景 | APP 行为 |
| --- | --- |
| 旧 Robot 完全不包含新增字段 | 正常解析，其值均视为空 |
| 新 Robot 包含字段但值为 `null` | 显示 `--` |
| 新 Robot 返回有效数值 | 按单位和精度展示 |
| 单个字段类型错误 | 只将该字段置空，不丢弃整条 `status` |
| 收到未知字段 | 忽略，保持向前兼容 |
| 单帧 `status` 丢失 | 保留上一帧，依靠后续帧恢复 |
| heartbeat 超时 | 将设备及遥测标记为离线/陈旧 |

本次新增字段没有提升 Payload `version`，仍为 `"1.0"`。APP 不能通过版本号是否大于 1.0
判断字段是否存在，应按字段是否存在和是否为 `null` 解析。

## 10. APP 开发任务清单

- [ ] 扩展 `status` DTO/实体模型，全部使用可空类型；
- [ ] 扩展 JSON 解析和本地缓存序列化；
- [ ] 增加 GPS 状态和坐标展示；
- [ ] 增加机身、H7、RK3588 三项温度展示；
- [ ] 增加总电流展示，并保留正负号；
- [ ] 增加 roll、pitch、yaw 和板面倾角估算展示；
- [ ] 为所有空值提供统一 `--`/“暂无数据”状态；
- [ ] 在 heartbeat 超时后标记遥测离线；
- [ ] 确认页面切换设备时不会混入上一设备的 status；
- [ ] 增加解析单元测试和 MQTT 联调测试。

## 11. 最小验收用例

1. 收到全部字段有效的 payload，数值、单位和精度显示正确；
2. 收到全部为 `null` 的 payload，页面不报错且不显示假 0；
3. 先收到有效值，再收到同字段为 `null`，旧值被清除；
4. 收到完全不含新增字段的旧版 payload，任务状态等原有功能不受影响；
5. `gpsStatus=0` 或坐标缺失时不显示地图 GPS 标记；
6. 经纬度为 `(0, 0)` 且 `gpsStatus` 有效时，将其作为合法坐标，不得仅凭零值判无效；
7. 电流为负数时保留负号；
8. H7 温度长期为 `null` 时 UI 仍保留占位且不反复报错；
9. 丢失一帧 status 不闪烁，heartbeat 超过 3000ms 后进入离线状态；
10. 同时订阅多台设备时，数据只更新到匹配 `deviceId/productType` 的页面。

完成以上用例后，才可认为 APP 已兼容本次新增上报字段。真实数值是否可用，还需要在
RK3588、H7、FCU 和整机传感器接入完成后分别进行实机验收。
