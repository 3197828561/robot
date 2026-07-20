# map_planner 面向 mission_planner 的接口设计

本文定义 `map_planner` 为支撑 `mission_planner` 执行清扫任务需要暴露的接口。本文是接口设计文档，不按当前代码实现状态组织内容；现有接口不满足本文约定时，应按本文进行新增或调整。

视觉定位相关接口不在本文重新设计，直接复用 [`vision_detect/API.md`](../vision_detect/API.md) 中已经定义的：

- `/mission_planner/vision_init`：`vgsolar_interfaces/msg/VisionInit`
- `/mission_planner/travel_context`：`vgsolar_interfaces/msg/TravelContext`
- `/vision/localization`：`vgsolar_interfaces/msg/VisionLocalization`
- `/vision/heading_error`：`vgsolar_interfaces/msg/HeadingError`
- `/vision/edge_observation`：`vgsolar_interfaces/msg/EdgeObservation`
- `/vision/status`：`vgsolar_interfaces/msg/VisionStatus`

## 1. 总体设计

`map_planner` 对 `mission_planner` 暴露地图和完整轨迹规划能力，不承担执行期动作完成判定。

```text
map_planner
  - 持有静态地图
  - 提供地图快照和地图索引查询
  - 根据任务目标、起点和机器人参数生成完整 PlannedPath
  - 在 PlannedPath 中给出 waypoint 类型、离散位置、heading、bridge_id 和 debug 信息
  - 不判断底盘动作是否完成
  - 不维护执行期当前位置
  - 不维护 mission 的 waypoint 完成状态

mission_planner
  - 请求 map_planner 生成完整 PlannedPath
  - 缓存当前任务轨迹和 waypoint index
  - 按 waypoint 顺序调度清扫、空驶、转向、过桥和视觉重初始化
  - 根据 waypoint 向 vision_detect 发布 vision_init / travel_context
  - 根据 vision、FCU、IMU 状态判断当前 waypoint 是否完成、失败或超时

vision_detect
  - 根据 mission_planner 发布的 vision_init 初始化离散位置
  - 根据 mission_planner 发布的 travel_context 解释格线和过线方向
  - 发布当前离散定位、航向误差、边界安全状态和视觉状态
```

`mission_planner` 的主流程：

```text
获取地图
  -> 请求完整规划轨迹
  -> 校验轨迹
  -> 初始化 vision
  -> 按 waypoint 顺序执行
  -> 执行过程中根据 vision/FCU/IMU 推进 waypoint index
  -> 必要时重新请求规划
```

## 2. 命名原则

`map_planner` 对外接口统一放在 `/map_planner/*` 命名空间下。

`map_planner` 对外暴露的规划和地图结果使用以下核心消息：

```text
map_planner/msg/PvMap
map_planner/msg/PlannedPath
map_planner/msg/PathWaypoint
map_planner/msg/PlanningDebug
```

## 3. 地图接口

### 3.1 地图快照 Service

```text
Service: /map_planner/get_map
Type:    map_planner/srv/GetMap
```

接口定义：

```text
---
bool success
string message
map_planner/msg/PvMap map
```

用途：

- 缓存 `blocks/cells/bridges/cell_model/frame`；
- 执行 `TYPE_BRIDGE_CROSSING` 时按 `bridge_id` 查找 bridge 几何和 endpoint；
- 校验当前任务使用的 `map_id` 和 `map_version`。

`mission_planner` 在规划前确认：

```text
success == true
map.map_id == task.map_id
map.version == task.map_version
```

### 3.2 地图重载 Service

```text
Service: /map_planner/reload_map
Type:    map_planner/srv/ReloadMap
```

接口定义：

```text
string map_file
---
bool success
string message
```

地图重载后，所有旧 `PlannedPath` 都失效。`mission_planner` 若触发地图重载，或再次调用 `/map_planner/get_map` 发现 `map.version` 变化，应停止当前自动任务或进入等待重规划状态。

## 4. 地图索引查询接口

地图索引查询用于调试、人工任务输入转换和外部系统只知道 `cell_id` 的场景。正常执行轨迹时，`mission_planner` 使用 `PlannedPath` 中已经给出的离散字段。

### 4.1 cell 行列转 cell_id

```text
Service: /map_planner/get_cell_id
Type:    map_planner/srv/GetCellId
```

接口定义：

```text
uint32 map_id
uint32 block_id
int32 cell_row
int32 cell_col
---
bool success
string message
uint32 cell_id
```

### 4.2 cell_id 转 cell 行列

```text
Service: /map_planner/get_cell_index
Type:    map_planner/srv/GetCellIndex
```

接口定义：

```text
uint32 map_id
uint32 cell_id
---
bool success
string message
uint32 block_id
int32 cell_row
int32 cell_col
```

## 5. 任务规划接口

### 5.1 规划接口目标

`mission_planner` 需要两类规划接口：

1. 清扫覆盖规划：固定机器人当前起点，不固定终点；可限制清扫 block 集合，由规划器决定从哪个 block 开始清扫。当前位置到开始清扫 block 之间只生成空驶和过桥 waypoint，不生成清扫 waypoint。
2. 点到点空驶规划：固定起点和终点；全程不清扫、不做蛇形覆盖，只在可通行中心格和 bridge 上找一条最短可执行路径。

两类接口都返回 `map_planner/msg/PlannedPath`，由 `mission_planner` 使用同一套 waypoint 执行流程调度。

### 5.2 PlanCoveragePath Service

```text
Service: /map_planner/plan_coverage_path
Type:    map_planner/srv/PlanCoveragePath
```

用于清扫任务。请求固定机器人当前起点和清扫 block 范围；规划器根据起点、bridge 可达性、转场代价和覆盖代价决定从哪个 block 开始清扫。起点到第一个清扫 block 的路径只用于转场，输出 `TYPE_DEADHEAD / TYPE_TURN_IN_PLACE / TYPE_APPROACH_BRIDGE / TYPE_BRIDGE_CROSSING / TYPE_REINIT_VISION`，不输出 `TYPE_CLEAN`。

request：

```text
uint32 map_id
uint32 map_version

# 固定起点。通常来自人工初始化或 /vision/localization。
uint32 start_block_id
int32 start_cell_row
int32 start_cell_col
int32 start_inner_row
int32 start_inner_col
uint8 start_heading

# 清扫范围。为空表示规划所有 cleanable block。
uint32[] target_block_ids
bool global_plan

# 机器人和清扫参数。
float64 robot_length_cm
float64 front_roller_width_cm
float64 rear_roller_width_cm
float64 robot_width_cm
float64 safety_margin_cm
float64 cleaning_width_cm
float64 overlap_ratio
bool enable_tail_coverage

# 搜索和诊断。
string planning_search_effort
bool debug_score_breakdown
```

response：

```text
bool success
string message
map_planner/msg/PlannedPath path
```

字段语义：

| 字段 | 说明 |
|------|------|
| `map_id/map_version` | 规划所基于的地图；version 不匹配时拒绝规划 |
| `start_*` | 机器人当前离散位姿，必须合法且可通行 |
| `target_block_ids` | 清扫 block 集合；为空时规划所有 cleanable block |
| `global_plan` | 是否允许跨 block bridge 全局规划；为 `false` 时按 block 独立覆盖并聚合 |
| `debug_score_breakdown` | 是否输出规划候选评分和失败原因 |

请求约束：

- `target_block_ids` 为空时，规划所有 cleanable block；
- `target_block_ids` 非空时，只规划指定 block 集合；
- 开始清扫的 block 由规划器在最终清扫 block 集合中选择；
- `start_*` 到第一个清扫 block 的转场路径不参与清扫覆盖统计；
- `global_plan=true` 时，规划器可以使用任务范围内的 bridge 做跨 block 转场；
- `global_plan=false` 时，不做跨 block 全局顺序搜索，只输出逐 block 覆盖聚合轨迹。

规划结果要求：

- `success=true` 时 `path.waypoints` 必须非空；
- `path.map_id/path.map_version` 必须与请求一致；
- 起点到第一个清扫 block 的转场段不得包含 `TYPE_CLEAN`；
- 第一个 `TYPE_CLEAN` waypoint 所在 block 即为规划器选择的开始清扫 block；
- `path.debug.coverage_complete=false` 时，在 `message/debug` 中说明未完整覆盖原因；
- 跨 block 规划失败时，在 `path.debug.unreachable_segments` 或 `path.debug.unusable_bridges` 中输出诊断；
- `path.waypoints[]` 必须按 mission 执行顺序排列。

### 5.3 PlanTransitPath Service

```text
Service: /map_planner/plan_transit_path
Type:    map_planner/srv/PlanTransitPath
```

用于点到点空驶。请求固定起点和终点，全程不清扫，不生成蛇形覆盖路径。规划器只在可通行中心格、正交相邻中心格和 bridge 转场上搜索最短路径；block 内移动不能斜行。

request：

```text
uint32 map_id
uint32 map_version

# 固定起点。
uint32 start_block_id
int32 start_cell_row
int32 start_cell_col
int32 start_inner_row
int32 start_inner_col
uint8 start_heading

# 固定终点。
uint32 goal_block_id
int32 goal_cell_row
int32 goal_cell_col
int32 goal_inner_row
int32 goal_inner_col
uint8 goal_heading
bool require_goal_heading

# 搜索范围。为空表示允许使用全图 cleanable block 和必要 bridge。
uint32[] allowed_block_ids

# 机器人通行参数。
float64 robot_length_cm
float64 front_roller_width_cm
float64 rear_roller_width_cm
float64 robot_width_cm
float64 safety_margin_cm

# 搜索和诊断。
string planning_search_effort
bool debug_score_breakdown
```

response：

```text
bool success
string message
map_planner/msg/PlannedPath path
```

字段语义：

| 字段 | 说明 |
|------|------|
| `start_*` | 空驶路径起点，必须是可通行中心格 |
| `goal_*` | 空驶路径终点，必须是可通行中心格 |
| `require_goal_heading` | 为 `true` 时终点必须满足 `goal_heading`；为 `false` 时只要求到达终点中心格 |
| `allowed_block_ids` | 限制空驶可经过的 block；为空时允许使用全图 cleanable block |
| `planning_search_effort` | 搜索强度和候选裁剪策略 |
| `debug_score_breakdown` | 是否输出搜索诊断 |

路径约束：

- 全程 `brush_on=false`；
- 不输出 `TYPE_CLEAN`；
- 不生成蛇形覆盖 lane；
- block 内只能沿 `block_u` 或 `block_v` 正交方向移动，不能斜行；
- 跨 block 只能通过静态地图中的可用 bridge；
- 需要改变 heading 时输出 `TYPE_TURN_IN_PLACE`；
- block 内空驶输出 `TYPE_DEADHEAD`；
- 上桥、过桥、下桥重定位分别输出 `TYPE_APPROACH_BRIDGE / TYPE_BRIDGE_CROSSING / TYPE_REINIT_VISION`。

规划结果要求：

- `success=true` 时 `path.waypoints` 必须非空；
- `path.map_id/path.map_version` 必须与请求一致；
- 第一个有效位置 waypoint 对应起点；
- 最后一个有效位置 waypoint 对应终点；
- 若 `require_goal_heading=true`，最后一个有效位置 waypoint 的 heading 必须等于 `goal_heading`；
- `path.debug.coverage_complete` 对空驶路径没有清扫覆盖含义，可以固定为 `true` 或在 debug 中说明为 transit path；
- 无法到达终点时返回 `success=false`，并在 `message` 和 debug 字段中输出不可达原因。

## 6. PlannedPath 执行契约

`PlannedPath` 是 `mission_planner` 执行任务的主数据结构。

```text
std_msgs/Header header
uint32 map_id
uint32 map_version
PathWaypoint[] waypoints
PlanningDebug debug
```

`mission_planner` 接受轨迹前需要校验：

- `success == true`；
- `path.map_id` 与当前任务地图一致；
- `path.map_version` 与最近一次 `/map_planner/get_map` 返回的 `map.version` 一致；
- `path.waypoints` 非空；
- `path.debug.coverage_complete` 符合任务策略；
- `path.debug.invalid_reasons / unreachable_segments / unusable_bridges` 为空或属于任务允许的退化情况。

`coverage_complete=false` 不等于 service 调用失败。它表示规划器返回了可执行候选，但存在未完整覆盖的中心格、不可达 lane 或其他规划退化。是否允许执行由 mission 任务策略决定。

## 7. PathWaypoint 字段和执行语义

`PathWaypoint` 字段：

```text
uint8 type
bool brush_on

uint32 block_id
int32 cell_row
int32 cell_col
int32 inner_row
int32 inner_col

uint8 heading
uint32 bridge_id
int32 rotation_angle_deg
```

`rotation_angle_deg` 表示 `TYPE_TURN_IN_PLACE` waypoint 需要执行的有符号旋转角度（deg）。正 = 右转（顺时针），负 = 左转（逆时针），0 = 不旋转。普通移动 waypoint 的 `rotation_angle_deg` 应为 0。`TYPE_TURN_IN_PLACE.heading` 表示旋转前 heading，执行完成后的 heading 可由 `heading + rotation_angle_deg` 或后续 waypoint 的 `heading` 推导。符号定义与 `/vision/heading_error` 中 `heading_error_rad` 的符号一致（FRD 约定）。

heading 枚举：

| 值 | 含义 |
|----|------|
| `HEADING_BLOCK_U_POSITIVE=0` | 沿当前 block 的 `+block_u` 方向 |
| `HEADING_BLOCK_U_NEGATIVE=1` | 沿当前 block 的 `-block_u` 方向 |
| `HEADING_BLOCK_V_POSITIVE=2` | 沿当前 block 的 `+block_v` 方向 |
| `HEADING_BLOCK_V_NEGATIVE=3` | 沿当前 block 的 `-block_v` 方向 |

waypoint 类型：

| 类型 | 值 | 执行语义 | `brush_on` | 离散格字段 |
|------|----|----------|------------|------------|
| `TYPE_CLEAN` | `0` | 清扫路径点，按 `heading` 沿 lane 执行覆盖 | `true` | 有效 |
| `TYPE_DEADHEAD` | `1` | 板内空驶、segment 连接、已扫区域中转或桥后接入 | `false` | 有效 |
| `TYPE_TURN_IN_PLACE` | `2` | 从当前 `heading` 原地转向 `rotation_angle_deg` | `false` | 有效 |
| `TYPE_APPROACH_BRIDGE` | `3` | 上桥前的对齐/接近点 | `false` | 有效 |
| `TYPE_BRIDGE_CROSSING` | `4` | 进入过桥语义，按 `bridge_id` 关联静态 bridge | `false` | 可无效，`bridge_id` 有效 |
| `TYPE_REINIT_VISION` | `5` | 过桥后视觉重新初始化/定位确认点 | `false` | 有效 |

`TYPE_BRIDGE_CROSSING` 不生成桥上密集 waypoint。过桥执行依赖 `bridge_id`、静态地图里的 bridge 几何、前一个 `TYPE_APPROACH_BRIDGE` 和后一个 `TYPE_REINIT_VISION`。

## 8. PathWaypoint 到视觉接口的映射

本文不重新定义视觉上下文。`mission_planner` 只使用 `vision_detect/API.md` 中已经定义的两个输入 topic。

### 8.1 VisionInit

```text
Topic: /mission_planner/vision_init
Type:  vgsolar_interfaces/msg/VisionInit
```

消息字段：

```text
std_msgs/Header header
int32 map_id
int32 block_id
int32 cell_row
int32 cell_col
int32 inner_row
int32 inner_col
```

`mission_planner` 在以下场景发布：

- 任务开始前，根据起点 waypoint 或人工/视觉给定起点初始化视觉位置；
- 人工校正或恢复任务时，重置视觉位置；
- 跨 bridge 后，根据 `TYPE_REINIT_VISION` waypoint 重新初始化视觉位置。

从 `TYPE_REINIT_VISION` waypoint 生成 `VisionInit` 时，字段直接映射：

| `VisionInit` 字段 | 来源 |
|-------------------|------|
| `map_id` | `PlannedPath.map_id` |
| `block_id` | `PathWaypoint.block_id` |
| `cell_row` | `PathWaypoint.cell_row` |
| `cell_col` | `PathWaypoint.cell_col` |
| `inner_row` | `PathWaypoint.inner_row` |
| `inner_col` | `PathWaypoint.inner_col` |

`TYPE_BRIDGE_CROSSING` 自身不用于初始化格子定位；通过 bridge 到达目标 block 后，以后续 `TYPE_REINIT_VISION` 为准。

### 8.2 TravelContext

```text
Topic: /mission_planner/travel_context
Type:  vgsolar_interfaces/msg/TravelContext
```

消息字段：

```text
std_msgs/Header header
int32 map_id
string travel_axis
int32 travel_sign
```

`mission_planner` 在当前任务段行进方向变化时发布或刷新 `/mission_planner/travel_context`。`vision_detect` 根据该上下文解释格线方向、过线事件和 `/vision/heading_error`。

`PathWaypoint.heading` 到 `TravelContext` 的映射：

| `PathWaypoint.heading` | `travel_axis` | `travel_sign` |
|------------------------|---------------|---------------|
| `HEADING_BLOCK_U_POSITIVE` | `block_u` | `+1` |
| `HEADING_BLOCK_U_NEGATIVE` | `block_u` | `-1` |
| `HEADING_BLOCK_V_POSITIVE` | `block_v` | `+1` |
| `HEADING_BLOCK_V_NEGATIVE` | `block_v` | `-1` |

`TYPE_CLEAN`、`TYPE_DEADHEAD`、`TYPE_APPROACH_BRIDGE` 和 `TYPE_REINIT_VISION` 都有有效 heading 时，可以按上表更新 `TravelContext`。`TYPE_TURN_IN_PLACE` 的 `heading` 是转向前 heading；执行完成后，mission 使用 `rotation_angle_deg` 推导转向后的目标 heading 并刷新 `TravelContext`。`TYPE_BRIDGE_CROSSING` 阶段不依赖普通 block 内格线过线更新，是否刷新 `TravelContext` 由过桥执行策略决定。

## 9. mission_planner 执行流程

### 9.1 任务启动和规划

```text
mission_planner 收到 start / task goal
  -> 调用 /map_planner/get_map
  -> 校验 map_id / map_version
  -> 清扫任务调用 /map_planner/plan_coverage_path
  -> 点到点空驶调用 /map_planner/plan_transit_path
  -> 检查 success、message、debug 和 waypoints
  -> 缓存 PlannedPath
  -> 初始化 waypoint index = 0
  -> 根据起点发布 /mission_planner/vision_init
  -> 根据当前行进 waypoint 的 heading 发布 /mission_planner/travel_context
```

### 9.2 执行主循环

```text
读取当前 waypoint
  -> 根据 waypoint.type 调度执行
  -> 必要时发布 /mission_planner/vision_init 或 /mission_planner/travel_context
  -> mission_planner 向 rover_fcu_bridge 发送运动/转弯/系统控制
  -> vision_detect 发布 /vision/localization、/vision/heading_error、/vision/edge_observation
  -> mission_planner 根据 vision + FCU + IMU 判定当前段完成/失败/超时
  -> 完成后推进 waypoint index
```

### 9.3 地图版本变化

如果执行过程中地图被 reload，`mission_planner` 需要认为当前轨迹失效：

```text
再次调用 /map_planner/get_map 后发现：
当前 path.map_id != map.map_id
或 path.map_version != map.version
  -> 停止当前自动任务或进入等待重规划状态
  -> 丢弃旧 PlannedPath
  -> 重新请求规划
```

## 10. 不同 waypoint 的执行方式

### 10.1 CLEAN / DEADHEAD

`TYPE_CLEAN` 和 `TYPE_DEADHEAD` 都是 block 内移动。区别是 `TYPE_CLEAN` 刷盘开启，`TYPE_DEADHEAD` 刷盘关闭。

mission 根据当前 waypoint 的 `heading` 发布或刷新 `/mission_planner/travel_context`。vision 根据 `TravelContext` 更新内部格子索引并发布：

- `/vision/localization`：当前 `block_id/cell_row/cell_col/inner_row/inner_col`；
- `/vision/heading_error`：相对期望行驶方向的航向误差；
- `/vision/edge_observation`：边界可见性、距离和安全等级。

mission 根据这些视觉输出、FCU 状态和超时保护推进 waypoint。

### 10.2 TURN_IN_PLACE

`TYPE_TURN_IN_PLACE` 是原地转向。转弯完成判断由 mission 根据 FCU 转弯状态和 IMU yaw 完成，vision 可在转弯后确认新的格线方向。

转弯完成后，mission 根据 `rotation_angle_deg` 推导转向后的目标 heading，并刷新 `/mission_planner/travel_context`。

### 10.3 APPROACH_BRIDGE

`TYPE_APPROACH_BRIDGE` 是上桥前接近点。该阶段仍处于 block 内定位语义，mission 按该 waypoint 的 heading 保持 `/mission_planner/travel_context` 有效，并根据 `/vision/localization` 和 `/vision/edge_observation` 判断是否到达桥入口附近。

### 10.4 BRIDGE_CROSSING

`TYPE_BRIDGE_CROSSING` 表示进入过桥执行语义。该 waypoint 主要携带 `bridge_id`，不表示桥上所有导航点。

当前视觉接口文档没有定义专门的 bridge context topic；因此本接口文档不新增 bridge 专用上下文字段。mission 执行过桥时使用缓存的 `PvMap.bridges[]` 和 `PathWaypoint.bridge_id` 决定过桥目标，并根据 `vision_detect` 现有输出和 FCU/IMU 状态做安全控制。到达目标 block 后，mission 使用后续 `TYPE_REINIT_VISION` waypoint 发布 `/mission_planner/vision_init`。

### 10.5 REINIT_VISION

`TYPE_REINIT_VISION` 是跨桥后视觉重新初始化点。mission 使用该 waypoint 的离散位置发布 `/mission_planner/vision_init`，并根据该 waypoint 的 `heading` 或后续行进 waypoint 的 `heading` 发布 `/mission_planner/travel_context`。

vision 初始化成功并恢复正常定位后，mission 才继续执行后续 block 内 waypoint。
