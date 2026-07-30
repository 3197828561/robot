# 四页面测试入口

本目录把工作台拆成四个独立测试单元：

这里的 PowerShell 脚本用于静态 UI/代码绑定审计和可选的 ADB 页面检查，不负责模拟 Robot
业务反馈。需要人工操作 APP 并观察 `cmd/remote → cmd_ack/status/map/pose` 完整闭环时，
请运行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\robot-sim\mqtt-robot-sim.ps1 -Mode interactive
```

逐功能步骤见 `tools/robot-sim/FOUR_PAGE_MANUAL_TEST.md`。

| 页面 | 脚本 | 数据流/修改指南 |
|---|---|---|
| 主页 | `1home/test-home.ps1` | `1home/home.md` |
| 地图 | `2map/test-map.ps1` | `2map/map.md` |
| 手动控制 | `3manual/test-manual-control.ps1` | `3manual/manual-control.md` |
| 状态详情 | `4status/test-status.ps1` | `4status/status.md` |

公共 ADB、控件定位、静态绑定检查和报告逻辑在 `PageTest.Common.ps1`。它不是第五个页面脚本。

## 测试层次

1. `-StaticOnly`：检查 phone/tablet 布局是否仍声明全部控件，以及每个按钮/字段是否仍绑定到预期代码源。
2. 默认运行：通过 `local.properties` 解析 ADB，打开 APP、切换页面、检查当前 UI 层级，执行无机器人副作用的按钮，并保存 XML、截图和结果。
3. `-AllowRobotCommands`：仅主页和手动控制脚本支持。显式允许发送任务/模式/速度消息；必须使用 robot-sim 或隔离测试场地。
4. 文档人工用例：覆盖脚本无法从 Android accessibility 层可靠断言的画布视觉效果、后端最终状态和危险动作。

## 前置条件

- `local.properties` 已配置 `adb.path` 或 `sdk.dir`，且不会被提交。
- APP 已安装。
- 运行测试前已登录并选择设备，启动 APP 后能进入四页面工作台。
- MQTT 联调时 APP 选择的 deviceId/productType 与 `tools/robot-sim` 相同。

## 常用命令

```powershell
# 一次执行四个静态审计
$scripts = @(
  "1home\test-home.ps1",
  "2map\test-map.ps1",
  "3manual\test-manual-control.ps1",
  "4status\test-status.ps1"
)
foreach ($script in $scripts) {
  powershell -ExecutionPolicy Bypass -File "tools\page-tests\$script" -StaticOnly
}

# 指定多个设备中的一个
powershell -ExecutionPolicy Bypass -File tools\page-tests\2map\test-map.ps1 -Serial emulator-5554

# 自定义证据目录
powershell -ExecutionPolicy Bypass -File tools\page-tests\4status\test-status.ps1 -OutputDir ".codex-artifacts\acceptance\status"
```

默认报告位置：

```text
.codex-artifacts/page-tests/{时间}-{页面}/
  results.json
  results.md
  *.xml
  *.png
```

该目录已通过本地 `.git/info/exclude` 忽略，不提交截图、UI dump 或可能包含设备信息的运行日志。

## 结果含义

- `PASS`：本次检查有直接证据通过。
- `FAIL`：控件/绑定/运行字段缺失或行为不符合断言，脚本退出码为 1。
- `SKIP`：前置状态未满足或危险动作未授权；不等于通过，必须按对应页面文档补测。
- `INFO`：环境和证据位置。

发布测试结论时必须同时报告 FAIL 和 SKIP，不能只写“脚本执行成功”。

