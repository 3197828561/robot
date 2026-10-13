# 2026-10-13 本机环境配置解耦

## 范围

- 保留 `local.properties` 和模板，继续承载标准 `sdk.dir` 以及 App 的 API/MQTT 本地构建配置；
- 从中移除 JDK、ADB、Emulator 和 Mosquitto 独立路径；
- JDK 改用 `JAVA_HOME`，Android SDK 同时支持 `sdk.dir`、`ANDROID_HOME` 和
  `ANDROID_SDK_ROOT`；
- 当 `local.properties` 缺少 API/MQTT 键时，从同级
  `import-message/app/config/app-runtime.env` 补充；
- 增加 `app.login.email/password` 作为测试登录账号的本地记录字段，值不进入 Gradle
  `BuildConfig`，不编译进 APK；
- Robot 模拟器从 `import-message/robot-handoff/robot-device-secrets.json` 读取联调配置；
- Mosquitto 通过 `MOSQUITTO_DIR`、PATH 或 `-MosquittoDir` 定位。

## 迁移

当前电脑原有 JDK、Android SDK 和 Mosquitto 目录已迁移到 Windows 当前用户环境变量；
`local.properties` 仍保留 Android 标准 `sdk.dir`，但不再保存 `java.home`、ADB、Emulator
或 Mosquitto 的独立路径。

## 验证

- 8 个 PowerShell 工具语法检查通过；
- 固定 JKS 与明文签名配置验证通过；
- Robot JSON 配置和 Mosquitto 发布/订阅客户端定位通过；
- 恢复 `local.properties` 后执行 Android 单元测试、Lint 和 Debug 构建，结果为
  `BUILD SUCCESSFUL`（57 tasks）；
- 生成的 Debug `BuildConfig` 中 API、MQTT主机、用户名、密码、设备类型和设备ID均已与
  `local.properties` 逐项比对一致；
- Cloud 与阿里云服务器不受影响。

