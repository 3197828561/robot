# 第三版任务接口 APP 侧验收报告

验收日期：2026-07-29  
分支：`codex/mission-command-v3-sync`  
APP 发布标识：`versionName=1.2.0`、`versionCode=3`  
任务接口能力：`mission_command_v2`  
协议依据：`docs/requirements/v3_app_mission_command_migration.md`

## 1. APP 侧结论

第三版要求的九种任务命令、完整 coverage START 参数、ACK/任务状态分离、
目标 missionId、失败重试、手自动遥控状态机、任务状态字段展示和安全状态优先级，
均已完成代码、单元测试和本地 MQTT Robot 模拟闭环验证。

本报告只能证明 APP 与 MQTT Robot 模拟器之间的接口行为。第三版协议要求的真实
Robot MQTT→`cloud_comm`→`/mission/command`→mission_planner ROS 证据仍需硬件组
联合验收；取得该证据前，不得把本报告当作 Robot 真机接口通过证明。

## 2. 第三版清单逐项证据

| 要求 | APP 证据 | 结果 |
| --- | --- | --- |
| 九种命令有独立 UI/业务映射 | 首页：start/stop/pause/resume/replan/estop/clear_estop；手动页：manual/auto；`MainActivity.bindControls()` 与 `MainViewModel.SUPPORTED_COMMANDS` | 通过 |
| START 完整 coverage 参数 | 配置弹窗收集 map、当前位置/六字段起点、目标 block、全局规划；本地抓包含完整 `taskKind/coverage` | 通过 |
| uint32 mapId/mapVersion | `Long` 全链路，发送前校验 `0..4294967295`；上界单测 | 通过 |
| 目标任务命令使用最新 missionId | stop/pause/resume/replan 从最新 `MissionState.missionId` 构造唯一 `targetMissionId`；本地闭环四种命令均验证 | 通过 |
| 重试复用 cmdId 和完整 payload | `PreparedCommand` 原对象重发；失败场景两次 MQTT 原始 payload 逐字节相同，cmdId/timestamp 相同 | 通过 |
| 迟到 ACK 正确收敛 | 本地超时后注入成功 ACK：关联原命令、保留参数、关闭重试 | 通过 |
| ACK 成功不标记任务完成 | START ACK 后状态页显示“启动请求已受理，等待任务状态”；最终状态只由 status 驱动 | 通过 |
| 安全状态优先 | `MissionStatusDisplay` 与测试覆盖 estop、clearing_estop、low_battery、fault | 通过 |
| MANUAL ACK + status 后才遥控 | `manualCommandAccepted` 与 `operationalMode=manual`、`safetyState=normal` 同时满足才开放方向键 | 通过 |
| 约 20Hz 遥控和主动零速 | 500ms 长按门槛，50ms 周期；松开、失焦、后台、离页、离线、安全异常均停止并主动归零 | 通过 |
| APP 可配置遥控速度 | UI 仅设置非负大小：线速度 `0..50 cm/s`、角速度 `0..0.5 rad/s`；方向键绑定正负号；慢速/标准/高速预设及按设备持久化已验证 | 通过 |
| AUTO 不恢复旧任务 | 退出手动页先零速，再发送空参数 `auto`，只等待模式变为 auto | 通过 |
| CLEAR_ESTOP 等待 normal | ACK 后保持解除等待态，收到 `safetyState=normal` 才结束 | 通过 |
| 服务错误明确提示 | 全部文档 errorCode 有中文说明并保留原始 code；服务不可用/超时/拒绝有单测和失败 ACK UI 验证 | 通过 |
| 全部 status 新字段展示 | missionId、taskKind、runState、operationalMode、safetyState、phase、activeAction、waypointIndex/count、四个错误字段均在状态页/手动页展示 | 通过 |
| 重连不得使用旧 missionId | 心跳超时清空会话运行态；仅 heartbeat 恢复时任务按钮保持禁用，收到新 status 后才按新状态开放 | 通过 |
| 新旧 Robot 发布绑定 | APP 标识 `1.2.0 (3)` 与 `mission_command_v2` 已嵌入 BuildConfig 并显示；后端暂无设备能力字段，发布时仍需固件白名单 | APP 侧完成，发布联审待办 |

## 3. 本地运行验证

隔离环境：

```text
Android Emulator -> 10.0.2.2:1884
Mosquitto -> 127.0.0.1:1884（仅回环监听）
Robot simulator -> tools/robot-sim/mqtt-robot-sim.ps1
```

已验证的交互：

```text
START -> success ACK -> running status + missionId
PAUSE -> paused
RESUME -> running
REPLAN -> running
STOP -> canceled
ESTOP -> estop
CLEAR_ESTOP -> clearing_estop -> normal
MANUAL -> manual status -> 20Hz remote -> release zero
leave remote -> zero -> AUTO -> auto status
```

断线恢复验证：

```text
running + missionId
  -> 停止 Robot 心跳
  -> missionId/status/pose 清空，任务与遥控按钮禁用
  -> 只恢复 heartbeat
  -> 在线，但 START/目标任务/手动按钮仍不开放
  -> 收到新 idle/auto/normal status
  -> 只按新会话状态恢复按钮
```

迟到 ACK 与重试验证：

```text
START -> 本地 ACK 超时
  -> failed ACK(MISSION_SERVICE_UNAVAILABLE)
  -> UI 显示中文原因和原始 errorCode
  -> 点击重试
  -> 两次原始 MQTT payload 逐字节相同

START -> 本地 ACK 超时
  -> late success ACK
  -> 原命令更新为“已受理”
  -> 重试按钮关闭
  -> 任务状态仍等待 status，不显示完成
```

## 4. 自动化门禁

最终正式 MQTT 配置下执行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\run-gradle.ps1 `
  testDebugUnitTest lintDebug assembleDebug
```

验收标准：

- 单元测试：23，失败 0，错误 0；
- Android Lint：Error 0；
- Debug APK：构建成功；
- `git diff --check`：通过。

## 5. 合并前仍需的真实 Robot 证据

- 固定 Robot image/firmware 版本，并确认能力为 `mission_command_v2`；
- 逐项保存 APP 下行 `cmd/remote` 原始 MQTT 抓包；
- 保存 Robot 上行 `cmd_ack/status` 原始 MQTT 抓包；
- 保存 `cloud_comm` 将 JSON 转换到 `/mission/command` 的日志；
- 保存 mission_planner 对应 Service 请求/响应和 ROS 状态证据；
- 核对真机 MANUAL、20Hz remote、零速、AUTO、ESTOP 的执行效果；
- Robot 与 APP 负责人在联合验收记录上确认后，再推送分支并创建 PR。
