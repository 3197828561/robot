# APK 局域网下载步骤

这个方法用于手机和电脑在同一个 WiFi/局域网下，通过手机浏览器下载 APK，不需要 USB 连接。

## 1. 文件位置

APK 下载工具现在放在：

```text
tools/apk-download
```

目录里应包含：

```text
robot-app-debug.apk
serve-apk.ps1
serve-apk.md
```

## 2. 启动下载服务

在项目根目录打开 PowerShell，运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\apk-download\serve-apk.ps1
```

脚本会输出一个或多个地址，例如：

```text
http://192.168.1.23:8080/robot-app-debug.apk
```

保持这个 PowerShell 窗口不要关闭。

## 3. 手机下载 APK

手机连接和电脑相同的 WiFi。

打开手机浏览器，输入脚本输出的地址：

```text
http://电脑IP:8080/robot-app-debug.apk
```

下载完成后，在手机文件管理器中打开 APK 安装。

## 4. 如果 8080 端口被占用

换一个端口启动：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\apk-download\serve-apk.ps1 -Port 8090
```

手机访问：

```text
http://电脑IP:8090/robot-app-debug.apk
```

## 5. 更新 APK

如果重新构建了最新 APK，把构建产物复制到下载目录：

```powershell
Copy-Item app\build\outputs\apk\debug\app-debug.apk tools\apk-download\robot-app-debug.apk -Force
```

然后重新运行下载脚本。

## 6. 安装失败排查

如果提示不允许安装：

```text
设置 -> 安全/隐私 -> 安装未知应用 -> 允许当前浏览器或文件管理器安装
```

如果提示“未安装应用”：

```text
先卸载手机上已有的 com.robot.solar，再重新安装
```

如果提示“解析包失败”：

```text
检查 APK 是否下载完整，或确认手机 Android 版本 >= 8.0
```

如果手机打不开下载地址：

```text
1. 确认手机和电脑在同一个 WiFi
2. 尝试脚本输出的另一个 IP 地址
3. 临时关闭电脑防火墙或允许 PowerShell 的网络访问
4. 换端口重新启动脚本
```
