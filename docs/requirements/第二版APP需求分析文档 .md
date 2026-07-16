# 解读文档：

------

# 一、APP端UI示例

APP 面向 Android 横屏平板设计，界面采用自适应布局，能够适配不同分辨率和屏幕尺寸，但仍需以 10.1 英寸及以上横屏平板作为主要设计与测试基准。

A版：

![ChatGPT Image 2026年7月16日 23_12_39](./%E7%AC%AC%E4%BA%8C%E7%89%88APP%E9%9C%80%E6%B1%82%E5%88%86%E6%9E%90%E6%96%87%E6%A1%A3%20.assets/ChatGPT%20Image%202026%E5%B9%B47%E6%9C%8816%E6%97%A5%2023_12_39.png)

# 二、接口设计

## 1.MQTT Topic 格式定义

MQTT Topic 统一采用：

```text
device/{productType}/{deviceId}/{topicType}
```

| 字段          | 说明                                                         | 开发阶段取值                                                 |
| ------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| `productType` | 设备类型（三类：`crawler`、`hanging`、`installer`，APP 分别展示为“履带式机器人”“挂轨式机器人”“安装机器人”）。 | `crawler`                                                    |
| `deviceId`    | 设备唯一编号，格式为 `{productType}_{8位数字}`               | `crawler_00000001`                                           |
| `topicType`   | 接口类型                                                     | `cmd`、`remote`、`heartbeat`、`status`、`cmd_ack`、`map`、`pose` |

示例：

```text
device/crawler/crawler_00000001/cmd
```

所有 Payload 使用 JSON 格式，字段采用 `camelCase`小驼峰风格命名。  
`timestamp` 使用 RFC3339 毫秒级 UTC 时间：

```json
"timestamp": "2026-07-08T07:51:00.123Z"
```

---

## 2.MQTT Broker 连接参数

```text
开发阶段环境配置：
MQTT Broker: 47.103.157.213
MQTT Port: 1883
MQTT Version: 3.1.1

APP端用户密码：
App MQTT Username: app_user_001
App MQTT Password: 此为公开文档暂不提供密码

robot端用户密码：
Robot MQTT Username: robot_device_001
Robot MQTT Password: 此为公开文档暂不提供密码
```

| 参数                | 说明                              |
| ------------------- | --------------------------------- |
| MQTT Broker         | MQTT 服务器地址                   |
| MQTT Port           | 第一版使用 `1883` 非 TLS 端口     |
| MQTT Version        | APP 与 Robot 统一使用 MQTT 3.1.1  |
| Username / Password | MQTT 登录认证信息，由系统配置提供 |

---

## 3.APP—Robot 上行接口

APP 向 Robot 发布 `cmd` 和 `remote` 消息。

### 3.1 cmd 控制命令接口

#### 1.Topic 名称

| 类型         | Topic                                 |
| ------------ | ------------------------------------- |
| 通用名       | `device/{productType}/{deviceId}/cmd` |
| 开发阶段取值 | `device/crawler/crawler_00000001/cmd` |

#### 2.字段、含义、取值

| 字段          | 类型   | 必填 | 含义         | 开发阶段取值                            |
| ------------- | ------ | ---: | ------------ | --------------------------------------- |
| `version`     | string |   是 | 接口版本     | 固定 `"1.0"`                            |
| `cmdId`       | string |   是 | 命令唯一 ID  | `cmd_{timestamp}`                       |
| `deviceId`    | string |   是 | 目标设备 ID  | `crawler_00000001`                      |
| `productType` | string |   是 | 设备类型     | `crawler`                               |
| `timestamp`   | string |   是 | 消息生成时间 | RFC3339 毫秒级 UTC 时间                 |
| `cmd`         | string |   是 | 控制命令     | `start`、`stop`、`estop`、`clear_estop` |
| `params`      | object |   是 | 命令参数     | 预留命令参数对象，第一版为空            |

| `cmd`         | 含义                     |
| ------------- | ------------------------ |
| `start`       | 开始自动运行             |
| `stop`        | 普通停止，不进入急停锁存 |
| `estop`       | 紧急停止并进入急停锁存   |
| `clear_estop` | 解除急停锁存             |

自动运行期间不处理遥控消息；用户需要手动遥控时，必须先执行 `stop`。

#### 3.完整示例

```json
{
  "version": "1.0",
  "cmdId": "cmd_20260708_000001",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "cmd": "start",
  "params": {}
}
```

---

### 3.2 remote 遥控接口

#### 1.Topic 名称

| 类型         | Topic                                    |
| ------------ | ---------------------------------------- |
| 通用名       | `device/{productType}/{deviceId}/remote` |
| 开发阶段取值 | `device/crawler/crawler_00000001/remote` |

#### 2.字段、含义、取值

| 字段                | 类型   | 必填 | 含义               | 开发阶段取值                                                 |
| ------------------- | ------ | ---: | ------------------ | ------------------------------------------------------------ |
| `version`           | string |   是 | 接口版本           | 固定 `"1.0"`                                                 |
| `deviceId`          | string |   是 | 目标设备 ID        | `crawler_00000001`                                           |
| `productType`       | string |   是 | 设备类型           | `crawler`                                                    |
| `timestamp`         | string |   是 | 消息生成时间       | RFC3339 毫秒级 UTC 时间                                      |
| `linearSpeedCms`    | number |   是 | 线速度，单位 cm/s  | 取值范围 `[-20,20]`，前进为正、后退为负，由方向控制按钮决定固定速度 |
| `angularSpeedRadps` | number |   是 | 角速度，单位 rad/s | 取值范围 `[-0.5,0.5]`，正负方向由 Robot 端统一定义，由左右转向按钮决定固定角速度 |
| `durationMs`        | int    |   是 | 当前控制量有效时间 | 第一版为 `300`ms                                             |

#### 3.遥控规则

| 项目                | 规则                                                         |
| ------------------- | ------------------------------------------------------------ |
| 控制方式            | 上下左右方向按钮，根据按钮类型发送固定线速度或角速度         |
| 前进                | 发送固定正线速度 `linearSpeedCms`，角速度为 0                |
| 后退                | 发送固定负线速度 `linearSpeedCms`，角速度为 0                |
| 左转                | 发送固定负角速度 `angularSpeedRadps`，线速度为 0             |
| 右转                | 发送固定正角速度 `angularSpeedRadps`，线速度为 0             |
| 启用条件            | Robot 在线、未急停，且当前不处于自动运行状态                 |
| 按下前 0.5 秒       | APP 不发送 `remote` 消息                                     |
| 持续按住超过 0.5 秒 | APP 按 20Hz 持续发送，约每 50ms 一次                         |
| 松开按钮            | 松开按钮时，APP 先发送一条线速度和角速度均为 0 的 remote 消息，然后停止周期发送。 |
| 自动运行状态        | Robot 不处理 `remote` 消息                                   |
| 自动运行切换        | Robot 进入自动任务前，应停止接受 remote 控制                 |
| 急停状态            | Robot 拒绝执行 `remote` 消息                                 |
| 超时停车            | Robot 超过 1000ms 未收到新消息时自动停车                     |
| 回执                | `remote` 为高频消息，不返回 `cmd_ack`                        |
| 多按钮同时按下      | 同时按多个方向按钮时 APP 发送零速度并提示用户                |

#### 4.完整示例

前进：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "linearSpeedCms": 20.0,
  "angularSpeedRadps": 0.0,
  "durationMs": 300
}
```

后退：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "linearSpeedCms": -20.0,
  "angularSpeedRadps": 0.0,
  "durationMs": 300
}
```

左转：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "linearSpeedCms": 0.0,
  "angularSpeedRadps": -0.5,
  "durationMs": 300
}
```

右转：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "linearSpeedCms": 0.0,
  "angularSpeedRadps": 0.5,
  "durationMs": 300
}
```

停止：

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "linearSpeedCms": 0,
  "angularSpeedRadps": 0,
  "durationMs": 300
}
```

---

## 4.Robot—APP 下行接口

Robot 向 APP 发布以下消息：

```text
device/{productType}/{deviceId}/heartbeat
device/{productType}/{deviceId}/status
device/{productType}/{deviceId}/cmd_ack
device/{productType}/{deviceId}/map
device/{productType}/{deviceId}/pose
```

### 4.1 heartbeat 心跳接口

#### 1.Topic 名称

| 类型         | Topic                                       |
| ------------ | ------------------------------------------- |
| 通用名       | `device/{productType}/{deviceId}/heartbeat` |
| 开发阶段取值 | `device/crawler/crawler_00000001/heartbeat` |

#### 2.用途

Robot 周期性上报心跳，APP 根据心跳判断设备在线状态。

#### 3.字段、含义、取值

| 字段          | 类型    | 必填 | 含义         | 开发阶段取值            |
| ------------- | ------- | ---: | ------------ | ----------------------- |
| `version`     | string  |   是 | 接口版本     | 固定 `"1.0"`            |
| `deviceId`    | string  |   是 | 设备 ID      | `crawler_00000001`      |
| `productType` | string  |   是 | 设备类型     | `crawler`               |
| `timestamp`   | string  |   是 | 消息生成时间 | RFC3339 毫秒级 UTC 时间 |
| `online`      | boolean |   是 | 在线状态     | 正常上报为 `true`       |

#### 4.规则

| 项目           | 规则                                                 |
| -------------- | ---------------------------------------------------- |
| 上报频率       | Robot 每 1000ms 上报一次                             |
| 离线判断       | APP 超过 3000ms 未收到心跳，显示离线                 |
| `online=false` | 正常情况下不要求 Robot 主动发送，由 APP 根据超时判断 |

#### 5.完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "online": true
}
```

---

### 4.2 status 设备状态接口

#### 1.Topic 名称

| 类型         | Topic                                    |
| ------------ | ---------------------------------------- |
| 通用名       | `device/{productType}/{deviceId}/status` |
| 开发阶段取值 | `device/crawler/crawler_00000001/status` |

#### 2.用途

Robot 周期性上报 APP 页面需要展示的基础设备状态信息。

#### 3.字段、含义、取值

| 字段                | 类型   | 必填 | 含义         | 开发阶段取值                                      |
| ------------------- | ------ | ---: | ------------ | ------------------------------------------------- |
| `version`           | string |   是 | 接口版本     | 固定 `"1.0"`                                      |
| `deviceId`          | string |   是 | 设备 ID      | `crawler_00000001`                                |
| `productType`       | string |   是 | 设备类型     | `crawler`                                         |
| `timestamp`         | string |   是 | 消息生成时间 | RFC3339 毫秒级 UTC 时间                           |
| `workStatus`        | string |   是 | 工作状态     | `idle`、`running`、`stopped`、`estopped`、`fault` |
| `controlMode`       | string |   是 | 控制模式     | `auto`、`manual`                                  |
| `batteryPercent`    | number |   是 | 电量百分比   | `0~100`                                           |
| `linearSpeedCms`    | number |   是 | 当前线速度   | 单位 cm/s                                         |
| `angularSpeedRadps` | number |   是 | 当前角速度   | 单位 rad/s                                        |
| `deviceStatus`      | string |   是 | 设备状态     | `normal`、`warning`、`fault`                      |
| `movementStatus`    | string |   是 | 运动状态     | `moving`、`stopped`、`turning`、`blocked`         |

#### 4.完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "workStatus": "running",
  "controlMode": "auto",
  "batteryPercent": 86,
  "linearSpeedCms": 20.0,
  "angularSpeedRadps": 0.0,
  "deviceStatus": "normal",
  "movementStatus": "moving"
}
```

---

### 4.3 cmd_ack 命令回执接口

#### 1.Topic 名称

| 类型         | Topic                                     |
| ------------ | ----------------------------------------- |
| 通用名       | `device/{productType}/{deviceId}/cmd_ack` |
| 开发阶段取值 | `device/crawler/crawler_00000001/cmd_ack` |

#### 2.用途

Robot 返回 `cmd` 命令的最终执行结果。

#### 3.字段、含义、取值

| 字段          | 类型        | 必填 | 含义        | 开发阶段取值                            |
| ------------- | ----------- | ---: | ----------- | --------------------------------------- |
| `version`     | string      |   是 | 接口版本    | 固定 `"1.0"`                            |
| `deviceId`    | string      |   是 | 设备 ID     | `crawler_00000001`                      |
| `productType` | string      |   是 | 设备类型    | `crawler`                               |
| `timestamp`   | string      |   是 | 回执时间    | RFC3339 毫秒级 UTC 时间                 |
| `cmdId`       | string      |   是 | 对应命令 ID | 与 APP 下发值一致                       |
| `cmd`         | string      |   是 | 对应命令    | `start`、`stop`、`estop`、`clear_estop` |
| `ackStatus`   | string      |   是 | 执行结果    | `success`、`failed`                     |
| `message`     | string      |   否 | 结果说明    | APP 直接展示                            |
| `errorCode`   | string/null |   否 | 错误码      | 成功时为 `null`                         |

#### 4.规则

- 每条 `cmd` 命令必须返回一条 `cmd_ack`。
- `cmd_ack.cmdId` 必须与 APP 下发的 `cmdId` 一致。
- 第一版只返回最终的 `success` 或 `failed`。
- `remote` 不返回 `cmd_ack`。

#### 5.完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.223Z",
  "cmdId": "cmd_20260708_000001",
  "cmd": "start",
  "ackStatus": "success",
  "message": "Start command success",
  "errorCode": null
}
```

---

### 4.4 map 地图通知接口

#### 1.Topic 名称

| 类型         | Topic                                 |
| ------------ | ------------------------------------- |
| 通用名       | `device/{productType}/{deviceId}/map` |
| 开发阶段取值 | `device/crawler/crawler_00000001/map` |

#### 2.用途

Robot 或 Cloud 在地图生成、上传或更新后，通过 MQTT 通知 APP 地图文件信息。APP 根据 `mapJsonUrl` 使用 HTTP/HTTPS 下载地图 JSON 文件。

```text
地图 JSON 文件
→ 上传云服务器或对象存储
→ 通过 map Topic 通知 APP
→ APP 下载、缓存并展示
```

#### 3.字段、含义、取值

| 字段            | 类型   | 必填 | 含义         | 开发阶段取值                              |
| --------------- | ------ | ---: | ------------ | ----------------------------------------- |
| `version`       | string |   是 | 接口版本     | 固定 `"1.0"`                              |
| `deviceId`      | string |   是 | 设备 ID      | `crawler_00000001`                        |
| `productType`   | string |   是 | 设备类型     | `crawler`                                 |
| `timestamp`     | string |   是 | 消息生成时间 | RFC3339 毫秒级 UTC 时间                   |
| `mapId`         | int    |   是 | 地图唯一 ID  | 接收值，与 Robot 地图 JSON 中 map_id 对应 |
| `mapName`       | string |   否 | 地图名称     | 接收值                                    |
| `mapVersion`    | int    |   是 | 地图版本号   | 与地图 JSON 中 version 字段保持一致       |
| `mapJsonUrl`    | string |   是 | 地图文件地址 | APP 可访问的 HTTP/HTTPS 地址              |
| `fileSizeBytes` | int    |   否 | 文件大小     | 单位 byte                                 |
| `checksum`      | string |   否 | 文件校验值   | 建议使用 SHA-256                          |

#### 4.规则

| 项目       | 规则                                                         |
| ---------- | ------------------------------------------------------------ |
| 传输方式   | MQTT 只传文件信息，不传完整地图 JSON                         |
| 地图格式   | APP 下载 Robot 端标准地图 JSON                               |
| 地图更新   | 地图内容变化时，mapVersion递增，并与地图 JSON 中 version 保持一致,mapVersion由Robot地图管理模块维护 |
| APP 缓存   | APP 根据 `mapId + mapVersion` 保存地图                       |
| 下载失败   | APP 提示失败并支持重新下载                                   |
| 文件校验   | 提供 `checksum` 时，APP 下载后进行校验                       |
| 地图一致性 | APP 根据 mapId + mapVersion 判断本地缓存地图是否有效         |

#### 5.完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "mapId": 1,
  "mapName": "PV Area A",
  "mapVersion": 1,
  "mapJsonUrl": "https://server.example.com/maps/map_20260708_000001.json",
  "fileSizeBytes": 23891,
  "checksum": "sha256-value"
}
```

### 4.5  pose 机器人地图位姿接口

#### 1.Topic 名称

| 类型         | Topic                                  |
| ------------ | -------------------------------------- |
| 通用名       | `device/{productType}/{deviceId}/pose` |
| 开发阶段取值 | `device/crawler/crawler_00000001/pose` |

#### 2.用途

Robot 周期性上报自身在当前地图中的实时位姿信息。

APP 根据 mapId、mapVersion、blockId、cellId、innerRow、innerCol 和 heading 信息，在地图中显示机器人位置和方向。

#### 3.字段、含义、取值

| 字段          | 类型   | 必填 | 含义           | 开发阶段取值                                                 |
| ------------- | ------ | ---: | -------------- | ------------------------------------------------------------ |
| `version`     | string |   是 | 接口版本       | 固定 `"1.0"`                                                 |
| `deviceId`    | string |   是 | 设备 ID        | `crawler_00000001`                                           |
| `productType` | string |   是 | 设备类型       | `crawler`                                                    |
| `timestamp`   | string |   是 | 消息生成时间   | RFC3339 毫秒级 UTC 时间                                      |
| `mapId`       | int    |   是 | 当前使用地图ID | 接收值                                                       |
| `mapVersion`  | int    |   是 | 当前地图版本号 | 与地图 JSON 中 version 字段保持一致                          |
| `blockId`     | int    |   否 | 当前区域编号   | Robot 地图 JSON 中对应 block 编号，例如 `1`                  |
| `cellId`      | int    |   否 | 当前单元编号   | Robot 地图 JSON 中对应 cell 编号，例如 `12`                  |
| `cellRow`     | int    |   否 | 单元所在行     | 当前 cell 在 block 网格中的行编号，例如 `0`                  |
| `cellCol`     | int    |   否 | 单元所在列     | 当前 cell 在 block 网格中的列编号，例如 `2`                  |
| `innerRow`    | int    |   否 | 内部网格行     | 当前机器人所在内部网格行，例如 `1`                           |
| `innerCol`    | int    |   否 | 内部网格列     | 当前机器人所在内部网格列，例如 `5`                           |
| `headingCode` | int    |   否 | 离散朝向编号   | `0~3`，与 Robot 端方向枚举保持一致                           |
| `heading`     | string |   否 | 朝向描述       | `block_u_positive`、`block_u_negative`、`block_v_positive`、`block_v_negative` |

#### headingCode 定义

| headingCode | heading            | 含义                     |
| ----------- | ------------------ | ------------------------ |
| `0`         | `block_u_positive` | 沿当前 block 局部 +U方向 |
| `1`         | `block_u_negative` | 沿当前 block 局部 -U方向 |
| `2`         | `block_v_positive` | 沿当前 block 局部 +V方向 |
| `3`         | `block_v_negative` | 沿当前 block 局部 -V方向 |

#### 4.规则

| 项目     | 规则                                                         |
| -------- | ------------------------------------------------------------ |
| 地图关联 | pose 中 mapId + mapVersion 必须与 APP 当前地图一致           |
| 轨迹保存 | APP 保存最近 10 秒 pose 数据绘制轨迹                         |
| 地图变化 | mapId 或 mapVersion 变化时重新加载地图                       |
| 定位失效 | Robot 无法定位时，blockId、cellId等位置字段可以为空，APP 不绘制机器人位置 |
| 定位恢复 | Robot 恢复定位后重新发送有效位置字段                         |

#### 5.完整示例

```json
{
  "version": "1.0",
  "deviceId": "crawler_00000001",
  "productType": "crawler",
  "timestamp": "2026-07-08T07:51:00.123Z",
  "mapId": 1,
  "mapVersion": 1,
  "blockId": 2,
  "cellId": 15,
  "cellRow": 1,
  "cellCol": 3,
  "innerRow": 2,
  "innerCol": 4,
  "headingCode": 0,
  "heading": "block_u_positive"
}
```

## 5.APP 接口处理流程

```text
选择设备
→ 建立 MQTT 连接并订阅设备 Topic
→ 接收 heartbeat 判断在线状态
→ 接收 status 展示设备状态
→ 接收 map 并通过 HTTP/HTTPS 下载地图
→ 接收 pose 更新机器人地图位置和轨迹
→ APP 下发 cmd 或 remote
→ cmd 通过 cmd_ack 展示执行结果
```

# 三、UI与接口数据连接

## 1.登录页

![image-20260711213053743](./%E7%AC%AC%E4%B8%80%E7%89%88APP%E9%9C%80%E6%B1%82%E5%88%86%E6%9E%90%E6%96%87%E6%A1%A3.assets/image-20260711213053743.png)



### 1.1 页面功能

登录页用于完成用户身份认证。用户输入账号和密码后，APP 调用 HTTP 登录接口；认证成功后保存登录凭证并进入设备列表页，认证失败时显示错误提示。

### 1.2 数据流

```text
用户输入账号、密码
→ APP 校验输入内容
→ 调用 HTTP 登录接口
→ 登录成功后保存 accessToken / refreshToken
→ 进入设备列表页
→ 登录失败时显示错误提示
```

### 1.3 UI 与数据对应关系

| UI 内容      | 数据来源或处理                                  |
| ------------ | ----------------------------------------------- |
| 用户名输入框 | 用户输入                                        |
| 密码输入框   | 用户输入，默认隐藏明文                          |
| 显示密码按钮 | APP 本地交互，不调用接口                        |
| 记住登录状态 | 使用 Android 安全存储保存 Token，不保存明文密码 |
| 登录按钮     | 调用 HTTP 登录接口                              |
| 错误提示     | 根据 HTTP 状态码、业务错误码和错误信息展示      |

### 1.4 实际应用规则

- 账号、密码为空时，不调用接口，直接提示用户补充。
- 登录请求应使用 HTTPS。
- 登录成功后保存 Token，不在本地明文保存密码。
- Token 失效时，APP 应返回登录页或使用 `refreshToken` 刷新。
- 登录按钮提交期间应防止重复点击。
- 网络异常、服务器异常和账号密码错误应分别提示。

## 2.设备列表页

![image-20260711213129433](./%E7%AC%AC%E4%B8%80%E7%89%88APP%E9%9C%80%E6%B1%82%E5%88%86%E6%9E%90%E6%96%87%E6%A1%A3.assets/image-20260711213129433.png)



### 2.1 页面功能

设备列表页展示当前登录账号有权限查看和控制的设备。设备基础信息和最近一次缓存状态由 HTTP 接口提供；用户选择设备后，APP 保存当前设备的 `deviceId`、`productType` 和设备名称，并进入工作台主页。

### 2.2 数据流

```text
进入设备列表页
→ APP 携带 Token 请求设备列表
→ HTTP 返回当前账号有权限访问的设备及最近状态
→ APP 展示设备卡片
→ 用户选择设备
→ APP 保存 deviceId、productType、设备名称
→ 进入工作台主页
```

### 2.3 UI 与数据对应关系

| UI 内容      | 数据来源或处理                                  |
| ------------ | ----------------------------------------------- |
| 设备名称     | HTTP 设备列表接口                               |
| 设备编号     | HTTP 返回的 `deviceId`                          |
| 设备类型     | HTTP 返回的 `productType`，APP 转换为中文名称   |
| 在线状态     | HTTP 返回的最近缓存状态                         |
| 工作状态     | HTTP 返回的最近缓存状态                         |
| 电量         | HTTP 返回的最近缓存状态                         |
| 设备图片     | 根据 `productType` 使用本地资源或 HTTP 图片地址 |
| 进入设备按钮 | 保存当前设备信息并进入工作台主页                |
| 不可操作状态 | 设备离线、无控制权限或设备状态异常时禁用        |

### 2.4 实际应用规则

- 设备列表页不依赖当前单台设备的 MQTT 订阅。
- HTTP 接口只返回当前用户有权限访问的设备。
- 进入设备后，实时状态由 MQTT 更新。
- 设备离线时仍可进入查看历史状态和地图，但控制按钮必须禁用。
- 设备列表为空时显示空列表状态，不显示模拟设备。
- 点击返回设备列表时，应停止当前设备的 MQTT 订阅并清除当前控制状态。

## 3.工作台主页

![ChatGPT Image 2026年7月16日 22_15_41](./%E7%AC%AC%E4%BA%8C%E7%89%88APP%E9%9C%80%E6%B1%82%E5%88%86%E6%9E%90%E6%96%87%E6%A1%A3%20.assets/ChatGPT%20Image%202026%E5%B9%B47%E6%9C%8816%E6%97%A5%2022_15_41.png)



### 3.1 页面功能

工作台主页用于综合展示当前设备的地图、在线状态、工作状态、电量、速度、当前位置及最近命令记录，并提供自动运行、普通停止、紧急停止和解除急停控制。

### 3.2 MQTT 订阅

用户进入工作台后，APP 根据当前设备的 `deviceId` 和 `productType` 建立 MQTT 连接，并订阅：

```text
device/{productType}/{deviceId}/heartbeat
device/{productType}/{deviceId}/status
device/{productType}/{deviceId}/cmd_ack
device/{productType}/{deviceId}/map
device/{productType}/{deviceId}/pose
```

### 3.3 UI 与接口对应关系

| UI 内容      | 对应接口或字段                                     |
| ------------ | -------------------------------------------------- |
| 在线状态     | `heartbeat.online`；超过 3000ms 未收到心跳显示离线 |
| 工作状态     | `status.workStatus`                                |
| 控制模式     | `status.controlMode`                               |
| 电量         | `status.batteryPercent`                            |
| 线速度       | `status.linearSpeedCms`                            |
| 角速度       | `status.angularSpeedRadps`                         |
| 设备状态     | `status.deviceStatus`                              |
| 运动状态     | `status.movementStatus`                            |
| 开始运行按钮 | 发布 `cmd=start`                                   |
| 停止运行按钮 | 发布 `cmd=stop`                                    |
| 紧急停止按钮 | 发布 `cmd=estop`                                   |
| 解除急停按钮 | 发布 `cmd=clear_estop`                             |
| 最近命令记录 | APP 根据已发送命令及对应 `cmd_ack` 结果生成        |
| 返回设备列表 | 页面跳转，不调用 MQTT 业务接口                     |

### 3.4 命令数据流

```text
用户点击控制按钮
→ APP 检查设备在线状态和按钮可用条件
→ 生成 cmdId
→ 发布 cmd 消息
→ 页面显示“发送中”
→ 5 秒内收到相同 cmdId 的 cmd_ack
→ 根据 ackStatus 显示成功或失败
→ 5 秒内未收到回执时显示超时
→ 超时后重新根据 status 确认设备实际状态
```

### 3.5 状态转换规则

| 命令          | 预期状态变化                                  |
| ------------- | --------------------------------------------- |
| `start`       | `workStatus=running`，`controlMode=auto`      |
| `stop`        | `workStatus=stopped`，允许进入手动遥控        |
| `estop`       | `workStatus=estopped`，禁止自动运行和手动遥控 |
| `clear_estop` | 解除急停锁存，设备恢复为可停止状态            |

### 3.6 实际应用规则

- 在线状态只由心跳和超时规则判断。
- 收到 `status` 只能更新设备数据，不能替代心跳判断。
- 命令超时仅表示 APP 未收到回执，不代表机器人一定未执行。
- 同一时间只允许处理一条需要回执的控制命令。
- 设备离线、MQTT 断开或设备故障时，除紧急停止外的控制按钮应禁用。
- 紧急停止按钮应保持醒目，并避免被普通页面弹窗遮挡。

## 4.地图页

![image-20260711213236278](./%E7%AC%AC%E4%B8%80%E7%89%88APP%E9%9C%80%E6%B1%82%E5%88%86%E6%9E%90%E6%96%87%E6%A1%A3.assets/image-20260711213236278.png)



### 4.1 页面功能

地图页用于显示当前设备对应的地图、机器人位置、机器人朝向以及最近 10 秒轨迹。

### 4.2 数据流

```text
APP 接收 map 消息
→ 获取 mapId、mapVersion、mapJsonUrl
→ 判断本地是否已有相同版本地图
→ 无缓存时通过 HTTP/HTTPS 下载地图 JSON
→ 校验 checksum
→ 下载成功后缓存并解析
→ 绘制地图
→ 根据 status 中的位置和朝向绘制机器人
→ 保存并绘制最近 10 秒轨迹
```

### 4.3 UI 与接口对应关系

| UI 内容        | 对应接口或字段                         |
| -------------- | -------------------------------------- |
| 地图内容       | `map.mapJsonUrl` 下载的 JSON 文件      |
| 地图编号       | `map.mapId`                            |
| 地图名称       | `map.mapName`                          |
| 地图版本       | `map.mapVersion`                       |
| 机器人位置     | `status.positionX`、`status.positionY` |
| 机器人朝向     | `status.headingDeg`                    |
| 当前区域       | `status.currentBlockId`                |
| 当前单元       | `status.currentCellId`                 |
| 最近 10 秒轨迹 | APP 根据连续位置数据生成               |
| 返回按钮       | 返回上一页                             |
| 缩放、居中     | APP 本地地图交互                       |

### 4.4 无地图时的显示规则

当出现以下任一情况时，地图绘制区域保持空白：

- 当前设备尚未收到 `map` 消息；
- 当前设备没有地图；
- `mapJsonUrl` 为空；
- 地图文件下载失败；
- 地图文件校验失败；
- 地图 JSON 解析失败。

地图区域不得显示示例地图、默认地图或模拟地图。

APP 内部应区分以下地图状态：

```text
NO_MAP       未收到地图
DOWNLOADING  正在下载
READY        地图可用
FAILED       下载、校验或解析失败
```

必要时仅在地图区域顶部显示简短状态文字，例如“暂无地图”或“地图加载失败”，但地图主体区域仍保持空白。

### 4.5 实际应用规则

- `map` Topic 只传地图文件信息，不传完整地图 JSON。
- 地图通知建议由 Cloud 统一发布。
- 同一 `mapId` 的 `mapVersion` 增加时，APP 重新下载并替换缓存。
- 提供 `checksum` 时必须校验。
- 地图下载应使用 HTTPS。
- 页面退出后继续保留本地地图缓存。
- 没有有效地图时，不绘制机器人位置和轨迹。
- 地图坐标系、比例尺、原点方向和角度方向必须由地图 JSON 规范统一定义。

## 5.手动控制页

![ChatGPT Image 2026年7月16日 22_44_07](./%E7%AC%AC%E4%BA%8C%E7%89%88APP%E9%9C%80%E6%B1%82%E5%88%86%E6%9E%90%E6%96%87%E6%A1%A3%20.assets/ChatGPT%20Image%202026%E5%B9%B47%E6%9C%8816%E6%97%A5%2022_44_07.png)

### 5.1 页面功能

手动控制页通过上下左右方向按钮控制机器人运动。APP根据按钮类型发送固定线速度或角速度remote消息，并实时展示机器人在线状态、工作状态、控制模式和当前速度，并有停止（cmd=“stop”）和急停（cmd=“estop”）按钮。

### 5.2 进入条件

进入手动控制页并启用手动控制前，必须同时满足：

```text
MQTT 已连接
+
最近 3 秒内收到 heartbeat
+
workStatus=stopped
+
未处于急停状态
+
deviceStatus=normal
```

若 Robot 端要求明确的手动模式，则还必须满足：

```text
controlMode=manual
```

### 5.3 UI 与接口对应关系

| UI 内容      | 对应接口或处理                                 |
| ------------ | ---------------------------------------------- |
| 在线状态     | `heartbeat.online`                             |
| 工作状态     | `status.workStatus`                            |
| 控制模式     | `status.controlMode`                           |
| 线速度       | `status.linearSpeedCms`                        |
| 角速度       | `status.angularSpeedRadps`                     |
| 普通停止按钮 | `remote.linearSpeedCms=0, angularSpeedRadps=0` |
| 紧急停止按钮 | 发布 `cmd=estop`                               |
| 前进按钮     | `remote.linearSpeedCms=20`                     |
| 后退按钮     | `remote.linearSpeedCms=-20`                    |
| 左转按钮     | `remote.angularSpeedRadps=-0.5`                |
| 右转按钮     | `remote.angularSpeedRadps=0.5`                 |

### 5.4 遥控数据流

```text
用户按住f方向按钮
→ APP判断控制条件
→ APP 记录按下时间
→ 前 0.5 秒不发送 remote
→ 持续按住超过 0.5 秒后按 20Hz 发送
→ 根据前进、后退、左转、右转给出线速度、角速度
→ 松开按钮
→ APP 发送一条线速度和角速度均为 0 的 remote
→ 停止周期发送
如果用户误触多按钮，发送线速度和角速度为0的remote，并前端弹窗提醒用户“请勿同时按多个方向按钮”。

为了用户友好性，用户按住按钮时，界面要有动态显示，防止用户无法判断是否正确发出控制命令。
```

### 5.5 安全规则

出现以下任一情况时，APP 必须立即发送一次零速度 `remote` 并停止周期发送：

- 用户松开按钮；
- APP 进入后台；
- 页面退出；
- MQTT 连接断开；
- 心跳超时；
- 设备进入自动运行；
- 设备进入急停状态；
- `deviceStatus` 变为 `warning` 或 `fault`；
- 当前设备发生切换。

### 5.6 实际应用规则

- `remote` 为高频消息，不返回 `cmd_ack`。
- 线速度范围为 `[-20, 20] cm/s`。
- 角速度范围为 `[-0.5, 0.5] rad/s`。
- Robot 超过 1000ms 未收到新 `remote` 消息时必须自动停车。
- 机器人端必须再次校验速度范围和当前状态，不能完全依赖 APP。

## 6.状态详情页

![image-20260711213334805](./%E7%AC%AC%E4%B8%80%E7%89%88APP%E9%9C%80%E6%B1%82%E5%88%86%E6%9E%90%E6%96%87%E6%A1%A3.assets/image-20260711213334805.png)



### 6.1 页面功能

状态详情页集中展示当前设备的基础信息和实时运行状态。

### 6.2 UI 与接口对应关系

| UI 内容      | 对应接口或来源                        |
| ------------ | ------------------------------------- |
| 设备名称     | HTTP 设备列表接口                     |
| 设备编号     | 当前选择的 `deviceId`                 |
| 设备类型     | 当前选择的 `productType`              |
| 地图版本     | `map.mapId`                           |
| 在线状态     | `heartbeat`                           |
| 工作状态     | `status.workStatus`                   |
| 控制模式     | `status.controlMode`                  |
| 电量         | `status.batteryPercent`               |
| 线速度       | `status.linearSpeedCms`               |
| 角速度       | `status.angularSpeedRadps`            |
| 设备状态     | `status.deviceStatus`                 |
| 运动状态     | `status.movementStatus`               |
| 最后在线时间 | APP 最近一次收到 heartbeat 的本地时间 |

### 6.3 实际应用规则

- 未收到 `status` 时，对应字段显示 `--`，不得使用模拟数据。
- 在线状态由 `heartbeat` 决定。
- `status.timestamp` 只表示状态消息生成时间。
- 状态超过一定时间未更新时，APP 应显示“数据可能已过期”。
- 字段值异常或超出范围时，APP 应记录日志并显示安全默认值。
- 设备状态为 `fault` 时，页面应突出显示故障状态。

## 7.弹窗提醒示例

![image-20260711213401696](./%E7%AC%AC%E4%B8%80%E7%89%88APP%E9%9C%80%E6%B1%82%E5%88%86%E6%9E%90%E6%96%87%E6%A1%A3.assets/image-20260711213401696.png)

### 7.1 页面功能

弹窗用于展示控制命令的发送中、成功、失败和超时状态。

### 7.2 触发条件

| 弹窗状态 | 触发条件                                |
| -------- | --------------------------------------- |
| 发送中   | APP 已发布 `cmd`，正在等待 `cmd_ack`    |
| 成功     | 收到相同 `cmdId` 且 `ackStatus=success` |
| 失败     | 收到相同 `cmdId` 且 `ackStatus=failed`  |
| 超时     | 5 秒内未收到相同 `cmdId` 的 `cmd_ack`   |

### 7.3 数据匹配规则

```text
APP 发送 cmd
→ 保存 cmdId
→ 接收 cmd_ack
→ 比较 cmd_ack.cmdId 与当前等待中的 cmdId
→ 一致时更新弹窗状态
→ 不一致时作为其他命令记录处理
```

### 7.4 实际应用规则

- 发送中状态期间，防止同一按钮重复提交。
- 成功和失败弹窗应显示对应命令名称。
- 失败弹窗优先显示 `message`，同时记录 `errorCode`。
- 超时后不自动判断命令失败，应重新读取 `status`。
- 紧急停止命令不得被普通弹窗流程阻塞。
- 弹窗关闭后，命令结果仍应保留在最近命令记录中。
- 网络断开时应立即结束发送中状态，并提示连接异常。

## 8. 页面间总体数据流

```text
用户登录
→ HTTP 获取 Token
→ HTTP 获取当前用户设备列表及最近缓存状态
→ 用户选择设备
→ APP 保存 deviceId、productType 和设备名称
→ APP 建立 MQTT 连接
→ 订阅当前设备 heartbeat、status、cmd_ack、map、pose
→ heartbeat 判断在线状态
→ status 更新设备运行状态
→ map 通知 APP 下载并缓存地图，主要需要获得mapJsonUrl，根据地图json在APP绘制地图进行可视化
→ APP 发布 cmd 或 remote
→ cmd 通过 cmdId 匹配 cmd_ack
→ remote 松开或异常时发送零速度
→ pose更新机器人位置
→ 返回设备列表时停止当前设备订阅和控制
```
