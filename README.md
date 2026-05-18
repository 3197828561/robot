# 光伏机器人远程控制 APP（Demo）

包名：`com.robot.solar.demo`  
技术栈：Kotlin、MVVM、ViewBinding、Room、LiveData、ViewModel、Kotlin 协程、Eclipse Paho MQTT。

## 环境要求

- Android Studio **Ladybug / Koala** 或更新版本。
- **Gradle JDK**：若国内网络无法稳定下载 jbr-17，可在 `Settings → Gradle → Gradle JDK` 中直接选用本机已有的 **jbr-21（JetBrains Runtime 21）**；本工程 `app/build.gradle.kts` 已配置 **Java/Kotlin 21** 与 `jvmToolchain(21)`，与 Gradle JDK 21 一致，无需再下载 JetBrains 的 17。
- 工程已配置 **Gradle 发行包阿里云镜像**（`gradle/wrapper/gradle-wrapper.properties` → `mirrors.aliyun.com/gradle/distributions/v9.4.1/gradle-9.4.1-all.zip`；**勿**使用错误的 `.../maven/gradle/9.4.1/...` 路径）。`settings.gradle.kts` 已启用 **Foojay 工具链解析插件**，便于自动补齐缺失的 JDK 17（需网络可达 Foojay/GitHub，或配置代理）。
- 可选：将 Gradle 缓存放到纯英文路径，在系统环境变量中设置 `GRADLE_USER_HOME=D:\gradle_cache`（**不能**写在 `gradle-wrapper.properties` 里）。

## 编译与安装

1. 用 Android Studio 打开项目根目录 `project01`。
2. 等待 Gradle Sync 完成。
3. 菜单 **Build → Make Project**，或对模块 `app` 执行 **Run**（选择 API 26+ 真机或模拟器）。
4. 生成 APK：`./gradlew :app:assembleDebug`（Windows：`gradlew.bat :app:assembleDebug`）。

## 运行流程

1. **启动**：未开启自动登录或未保存有效凭据时进入登录页。
2. **登录**：使用在 `LoginRepository` 中配置的账号密码；可勾选「记住密码」「自动登录」。
3. **主页**：连接 MQTT 测试 Broker；订阅状态主题、发布控制指令 JSON；操作写入设备操作日志。
4. **日志页**：从主页「日志信息」进入列表，时间倒序，支持下拉刷新。

## MQTT 测试（可选）

在电脑安装 Mosquitto 客户端后，可模拟设备上报状态（JSON）：

```bash
mosquitto_pub -h broker.hivemq.com -p 1883 -t solarbot/robot/status -m "{\"online\":true,\"battery\":82}"
```

应用收到后，主页设备与电量展示会更新（有远端电量字段时优先采用）。

## 控制指令 JSON 格式

发布主题：`solarbot/robot/cmd`

```json
{"cmd":"forward","deviceId":"solar_demo","ts":1715000000000}
```

`cmd` 取值示例：`forward`、`backward`、`turn_left`、`turn_right`、`stop`、`emergency_stop`。

## 架构说明（简述）

- **分层**：UI（Activity + ViewBinding）→ ViewModel → Repository → Room / MQTT 工具，便于替换真实机器人网关。
- **可靠性**：MQTT 断线后延迟重连；网络恢复触发补连；按钮 500ms 防抖；明文 MQTT 已开启 `usesCleartextTraffic`。
- **可追溯**：所有关键行为写入 Room，类型分为登录 / 设备 / 系统，便于审计与扩展报表。
