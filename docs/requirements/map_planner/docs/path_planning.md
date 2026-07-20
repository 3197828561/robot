# 清扫路径规划流程

本文描述 `map_planner` 如何基于静态地图生成清扫路径。地图格式本身见 [map_planner 地图表示方案](map_representation.md)。

核心原则：

1. 最终规划结果要落到离散格子上：`block_id + cell_row + cell_col + inner_row + inner_col`。
2. 机器人不是一个点，车体会占据多个内部格。
3. `grid[row][col] == 1` 只表示该小板存在，不表示机器人中心一定可进入。
4. 安全距离、缺板避让、边界避让最终都要转换成“中心格禁用规则”。也就是：膨胀最后仍然落实为“哪些内部格不能作为机器人中心格”。
5. “膨胀多少格”不写死。不同小板尺寸、不同 `inner_rows/inner_cols`、不同清扫方向、不同机器人尺寸都会导致格子尺寸和膨胀格数不同，必须运行时动态计算。
6. `lane_stride` / 换行格子数也不能写死。它由清扫宽度、内部格尺寸、重叠率和安全约束共同决定。

---

## 一、输入

### 1.1 地图输入

规划器从 `map_planner` 获取静态地图：

| 字段 | 含义 | 规划用途 |
|------|------|----------|
| `cell_model.inner_rows / inner_cols` | 每块小板内部划分多少行/列格子 | 定义离散定位和路径规划粒度 |
| `blocks[].block_frame` | block 局部坐标系，定义 `+block_u / +block_v` | 判断行列方向、清扫方向、桥端方向 |
| `blocks[].rows / cols / grid` | block 内小板行列和缺板位置 | 提取原始 lane，识别缺板 |
| `blocks[].cleanable` | block 是否参与清扫 | 过滤不清扫 block |
| `cells[].polygon` | 每块小板真实四边形轮廓 | 生成内部格坐标、计算实际尺寸、安全检查 |
| `cells[].row / col` | 小板在 block 内的行列 | 关联 `grid` 与几何 |
| `bridges[].endpoints` | 已确认 bridge 的上桥/下桥锚点 | 规划 block 间转场 |
| `bridges[].centerline / polygon` | 可选桥面几何参考 | 估算桥参考长度、辅助 RViz 显示；polygon 当前只做基本合法性检查，桥宽和桥面 footprint 采样属于后续安全校验扩展 |

地图坐标方向约定：

```text
cell_col  沿 +block_u 增加
cell_row  沿 +block_v 增加
inner_col 沿 +block_u 增加
inner_row 沿 +block_v 增加
```

`grid[row][col] == 1` 表示该位置存在小板；`grid[row][col] == 0` 表示缺板。

### 1.2 机器人和任务参数

这些参数不写入当前地图 schema，由 planner 配置或机器人型号配置提供：

| 参数 | 含义 |
|------|------|
| `robot_length_cm` | 机器人整机长度，包含前/后滚刷滚桶直径 |
| `front_roller_width_cm` | 前滚刷滚桶直径 / 前向伸出长度，已包含在 `robot_length_cm` 中，计算膨胀时扣除 |
| `rear_roller_width_cm` | 后滚刷滚桶直径 / 后向伸出长度，已包含在 `robot_length_cm` 中，计算膨胀时扣除 |
| `robot_width_cm` | 机器人车体宽度 |
| `safety_margin_cm` | 与边界、缺板、桥边、障碍保持的安全距离 |
| `cleaning_width_cm` | 滚刷长度 / 横向清扫覆盖宽度，不是滚桶直径，也不一定等于车体宽度 |
| `overlap_ratio` | 相邻清扫 lane 的重叠比例，例如 `0.1 ~ 0.3` |
| `enable_tail_coverage` | 是否在横向边界覆盖不足时尝试加入边界补扫 lane 和相邻 backup lane |
| `start_block_id/cell/inner/heading` | 固定机器人当前离散起点，来自人工初始化或视觉定位 |
| `target_block_ids` | 清扫目标 block 集合；为空时规划所有 cleanable block |
| `global_plan` | 是否允许跨 block 全局顺序搜索；为 false 时按目标顺序聚合覆盖 |
| `allowed_block_ids` | transit 可经过的 block 集合；为空时允许所有 cleanable block |
| `planning_search_effort` | 规划搜索强度：`fast` / `balanced` / `quality` / `exhaustive` |
| `debug_score_breakdown` | 是否在 `PlanningDebug.score_breakdown` 输出候选评分、各轴最优候选和失败原因 |

这些参数决定同一张地图对不同机器人是否可通行。例如大机器人可能需要膨胀 2 格，小机器人可能只需要膨胀 1 格。`MapServerNode` 中的默认值来自代码参数声明；launch 文件可以覆盖这些默认值，例如当前 launch 已把车体尺寸、安全距离和 `cleaning_width_cm` 暴露为可配置参数。

`planning_search_effort` 同时影响 lane 候选枚举、自由起点数量、全局候选保留数量和大图搜索规模：`fast` 最快且裁剪最多；`balanced` 是默认折中；`quality` 会枚举更多 stride / offset 和候选；`exhaustive` 会保留更多候选并允许更多自由起点搜索，耗时也最高。

---

## 二、总体规划流程

当前实现有两个规划入口：`GlobalCoveragePlanner` 负责 `/map_planner/plan_coverage_path`，`TransitPathPlanner` 负责 `/map_planner/plan_transit_path`。

### 2.1 清扫覆盖规划（固定起点，不固定终点）

输入：固定 `start_pose`（来自人工初始化或视觉定位）、`target_block_ids`（空 = 所有 cleanable block）、机器人参数。

```text
读取静态地图、固定 start_pose、target_block_ids 和机器人参数
  -> target_block_ids 为空时使用所有 cleanable block
  -> 为全图构建 CenterGrid
  -> 为目标 block 生成角点入口候选、自由起点候选和入口受限覆盖候选
  -> 为第一个 block 评估精确 transit 代价（TransitPathPlanner，从 start_pose 到候选 entry_pose）
  -> 为后续 block 评估转场代价（BFS 桥路径 + L 形空驶，过桥后从桥锚点空驶到候选 entry_pose）
  -> 逐轮贪心选择综合代价（覆盖完整性 + 转场距离 + 转弯数）最低的下一个 block
  -> global_plan=false 时按目标顺序聚合
  -> 第一个 clean waypoint 前只输出非清扫转场 waypoint
```

block 间转场：`make_local_bridge_graph_transit`，基于 BFS 找最短桥路径，block 内用 L 形空驶（源端严格 Free，目标端桥锚点首转弯允许 BlockedBoundary）。第一步从固定 start_pose 出发时使用 `TransitPathPlanner` 精确搜索。

### 2.2 点到点空驶规划（固定起点 + 固定终点）

输入：固定 `start_pose` 和 `goal_pose`、`allowed_block_ids`（空 = 所有 cleanable block）、机器人参数。

搜索空间为 `GridPose = CenterInnerCell + heading`。使用 Dijkstra / A* 搜索：

- **block 内移动**：只扩展正交相邻中心格（沿 `block_u` 或 `block_v`），不斜行
- **原地转向**：在同一中心格改变 heading，要求目标 heading 下该格 `FREE`
- **跨 block**：只能通过静态地图中的可用 bridge，上桥/下桥 anchor 由 bridge endpoint 推导
- **代价**：按 `(turns, bridges, distance)` 字典序比较，优先少转弯，其次少过桥，最后比路径长度
- 全程 `brush_on=false`，不输出 `TYPE_CLEAN`

`allowed_block_ids` 为空时允许使用所有 cleanable block 作为经过区域；`require_goal_heading=false` 时只要求到达终点中心格，在可达 heading 中选择转弯更少的。

最终输出的 waypoint 以离散格子索引、执行语义和机器人朝向为主，基本结构为：

```text
type
block_id
cell_row
cell_col
inner_row
inner_col
heading
brush_state
rotation_angle_deg
```

其中：

- `type` 表示 waypoint 执行语义，例如 `clean / deadhead / turn_in_place / approach_bridge / bridge_crossing / reinit_vision`；
- `block_id + cell_row + cell_col + inner_row + inner_col` 是 block 内 waypoint 的主表达，用于视觉定位、任务执行和断点续扫；
- `heading` 表示机器人到达该 waypoint 时的朝向；对于 `turn_in_place`，表示旋转前朝向；
- `brush_state` 表示刷盘开关状态，可由 `type` 默认推导，也可以在输出中显式给出；
- `rotation_angle_deg` 表示 `turn_in_place` waypoint 需要执行的旋转角度（deg），正 = 右转（FRD），负 = 左转，0 = 不旋转。普通移动 waypoint 的该字段应为 0。符号与 `/vision/heading_error` 一致；
- 对于 `bridge_crossing`，waypoint 通常不落在小板内部格上，可以只记录 `bridge_id`；过桥方向由前一个 `approach_bridge` 和后一个 `reinit_vision` / 目标 block 推导。

规划输出中的 `type` 用来区分 waypoint 的执行语义，当前包含：

| `type` | 含义 | `brush_state` | 是否必须落在内部格 |
|--------|------|---------------|--------------------|
| `clean` | 清扫 waypoint，刷盘开启，沿 lane 覆盖有板区域 | `on` | 是 |
| `deadhead` | block 内非清扫空驶 waypoint，用于 segment 连接、换 lane、桥后接入、已清扫 block 中转等 | `off` | 是 |
| `turn_in_place` | 原地转向 waypoint，用于改变 heading，不发生平移 | `off` | 是 |
| `approach_bridge` | 上桥前的接近 waypoint，从 block 内可通行中心格对齐桥入口方向 / 视觉巡线初始方向 | `off` | 是 |
| `bridge_crossing` | 过桥执行语义，切换到视觉巡线，由视觉模块跟随桥面实际中心线 | `off` | 不一定，只需关联 `bridge_id` |
| `reinit_vision` | 跨桥后视觉重新初始化 / 定位确认点 | `off` | 是 |

其中 `clean / deadhead / turn_in_place / approach_bridge / reinit_vision` 都落在某个可通行中心格上；`bridge_crossing` 表示进入过桥巡线状态，不一定有 `cell_row/cell_col/inner_row/inner_col`，并关联到 `bridge_id`。

内部格几何点和桥中心线几何点可以作为 planner 内部派生数据，用于生成可通行中心格、计算距离、检查 footprint 和 RViz 调试显示；但它们不是 block 内导航状态的主表达。block 内导航、视觉定位和断点续扫仍以离散格子索引为准。

---

## 三、生成内部格坐标

本节要解决的问题是：**静态地图只存了每块小板的整体四边形 `cells[].polygon`，但规划和视觉定位要使用 `inner_row / inner_col` 内部格子。**

所以 planner 必须先把每块小板从“一个四边形”转换成“内部格子坐标表”。这个过程为：根据小板四边形和 `inner_rows / inner_cols`，算出每个内部格的角点、中心点和实际尺寸。

计算结果至少包括：

| 计算数据 | 用途 |
|----------|------|
| 每个内部格的四个角点 | 判断格子边界、显示内部格线、做几何碰撞检查 |
| 每个内部格的中心点 | 生成机器人中心 waypoint |
| 每个内部格的 `u/v` 实际尺寸 | 动态计算膨胀多少格、换行多少格 |
| `inner_row/inner_col -> pv_map 坐标` 的转换 | 把离散规划结果转成导航几何点 |
| `pv_map 坐标 -> inner_row/inner_col` 的近似反查 | 后续定位、调试、断点恢复时使用 |

如果没有这一步，后续无法回答这些问题：

- `inner_row=1, inner_col=3` 的实际地图坐标在哪里？
- 当前小板的一个内部格实际是多少厘米？
- 机器人宽度加安全距离后会占几个内部格？
- 靠边多少个内部格不能作为机器人中心？
- `lane_stride=2` 到底对应多少厘米，会不会漏扫？
- bridge endpoint 的 `edge + inner_row/inner_col` 对应小板边上的哪个真实点？

因此，内部格坐标表是后续格子膨胀、可通行中心格、换行格子数和 bridge endpoint 安全检查的基础。

### 3.1 cell polygon 约定

每个小板 `cells[].polygon` 使用四点顺序：

```text
p00, p10, p11, p01
```

含义：

```text
p00 = u_min, v_min
p10 = u_max, v_min
p11 = u_max, v_max
p01 = u_min, v_max
```

示意：

```text
        +v / inner_row
        ↑
  p01 --+-- p11
   |         |
   |  cell   |
   |         |
  p00 --+-- p10  → +u / inner_col
```

### 3.2 内部格切分

内部格切分的输入是：

```text
cell polygon + cell_model.inner_rows + cell_model.inner_cols
```

输出是一张该 cell 内部的格子几何表：

```text
inner_grid[inner_row][inner_col] = {
  corner00,
  corner10,
  corner11,
  corner01,
  center,
  inner_u_size_cm,
  inner_v_size_cm
}
```

对每个 cell：

1. 沿 `u` 方向按 `inner_cols` 切分；
2. 沿 `v` 方向按 `inner_rows` 切分；
3. 用双线性插值得到每个内部格的四个角点；
4. 内部格中心点由四角或插值中心得到；
5. 计算内部格在 `u/v` 方向上的实际尺寸。

一个简单例子：

```text
cell polygon 约为 240cm × 120cm
inner_cols = 6
inner_rows = 3
```

则每个内部格大约是：

```text
u 方向: 240 / 6 = 40cm
v 方向: 120 / 3 = 40cm
```

如果 planner 输出：

```text
block_id=1, cell_row=0, cell_col=0, inner_row=1, inner_col=3
```

那么有了内部格坐标表后，就能把这个离散格子转换成真实地图坐标，例如近似在该小板内：

```text
u = (3 + 0.5) * 40 = 140cm
v = (1 + 0.5) * 40 = 60cm
```

也就是说，这一步把“格子编号”变成了“真实几何点”。

设归一化坐标：

```text
u_ratio = inner_col / inner_cols
v_ratio = inner_row / inner_rows
```

四边形内某个点可按双线性插值近似：

```text
point(u_ratio, v_ratio)
  = (1-u)(1-v) * p00
  + u(1-v)     * p10
  + uv         * p11
  + (1-u)v     * p01
```

每个内部格 `(inner_row, inner_col)` 的四个角点是：

```text
u0 = inner_col / inner_cols
u1 = (inner_col + 1) / inner_cols
v0 = inner_row / inner_rows
v1 = (inner_row + 1) / inner_rows

corner00 = point(u0, v0)
corner10 = point(u1, v0)
corner11 = point(u1, v1)
corner01 = point(u0, v1)
center   = point((u0 + u1) / 2, (v0 + v1) / 2)
```

### 3.3 动态格子尺寸

每个内部格在 `u/v` 方向上的尺寸可以近似为：

```text
inner_u_size_cm = average(length(corner10 - corner00), length(corner11 - corner01))
inner_v_size_cm = average(length(corner01 - corner00), length(corner11 - corner10))
```

不要假设所有小板尺寸完全一样。即使 `inner_rows/inner_cols` 相同，不同 cell 的内部格实际宽高也可能因为 CAD 几何、旋转、绘图误差或规格差异略有不同。

因此后面计算膨胀格数和 `lane_stride` 时，都使用当前 cell 或当前 block 的实际内部格尺寸。

### 3.4 block 级代表格子尺寸

为了减少噪声，当前实现为每个 block 统计代表尺寸：

```text
block_inner_u_size_cm = median(all cell inner_u_size_cm in block)
block_inner_v_size_cm = median(all cell inner_v_size_cm in block)
```

当前实现使用 block 级中位数计算 stride 和膨胀格数；具体 cell polygon 的连续几何精校验属于后续安全校验扩展。

---

## 四、把安全距离转换成 heading 相关的膨胀格数

### 4.1 为什么膨胀必须带 heading

规划最终输出的是内部格 waypoint，所以安全规则也要能落到内部格上。

对每个候选中心格，要回答的不是单纯：

```text
机器人中心放在这个 inner cell 时，是否安全？
```

而是：

```text
机器人中心放在这个 inner cell，且机器人处于某个 heading 时，是否安全？
```

因为机器人 footprint 是有长宽方向的。同一个中心格在 `heading = block_u` 时可能安全，在 `heading = block_v` 时可能因为车体长度方向变化而贴边或压到缺板。

因此可通行性应表达为：

```text
is_traversable(center_cell, heading)
```

而不是：

```text
is_traversable(center_cell)
```

当前 block 内清扫和空驶平移枚举的 heading 为：

```text
block_u_positive
block_u_negative
block_v_positive
block_v_negative
```

当前实现只使用这四个方向，它们覆盖机器人在 block 内沿 `block_u / block_v` 清扫、空驶、上桥接近和下桥离开时的可通行性检查。原地转向也以目标轴向 heading 的中心格状态为准；非轴向姿态和连续旋转过程的 footprint 采样属于后续扩展。

如果机器人 footprint 前后对称，`block_u_positive / block_u_negative` 的占用可以共用一张图，`block_v_positive / block_v_negative` 也可以共用一张图；如果后续存在前后不对称、刷盘偏置或传感器外扩，则需要保留四个方向分别检查。
如果某个中心格会导致机器人车体：

- 越出小板边界；
- 压到缺板洞；
- 贴近 block 外边缘；
- 压到桥边或障碍；

则这个中心格会被标记为不可通行。

从离散角度看，这等价于：

```text
边界 / 缺板 / 障碍 先按机器人 footprint + safety_margin 膨胀
膨胀范围内的内部格不能作为机器人中心格
```

### 4.2 膨胀格数不能写死，也不能脱离 heading

不能写成：

```text
边界固定避开 1 格
缺板固定膨胀 2 格
```

原因：

- 小板尺寸可能是 `110cm × 240cm`，也可能是别的规格；
- `inner_rows/inner_cols` 可能从 `3 × 6` 改成其他配置；
- 同一小板中 `u` 和 `v` 方向格子尺寸不同；
- 不同 block 可能旋转，但方向仍要投影到 `block_u/block_v`；
- 机器人沿 `block_u` 走和沿 `block_v` 走时，长宽投影不同；
- 不同机器人尺寸和 `safety_margin_cm` 不同。

所以膨胀格数必须在已知候选 heading 后动态计算，或者在规划前为每个可能 heading 预计算一张中心格状态图。

### 4.3 带安全余量的机器人尺寸

先从整机长度中扣除允许伸出光伏板的前/后滚刷滚桶直径，得到用于通过性判断的底盘长度：

```text
chassis_length_cm = max(0, robot_length_cm - front_roller_width_cm - rear_roller_width_cm)
```

再计算安全 footprint：

```text
safe_width_cm  = robot_width_cm     + 2 * safety_margin_cm
safe_length_cm = chassis_length_cm  + 2 * safety_margin_cm
```

如果只需要判断中心格离边界多远，则使用半尺寸：

```text
safe_half_width_cm  = robot_width_cm    / 2 + safety_margin_cm
safe_half_length_cm = chassis_length_cm / 2 + safety_margin_cm
```

前/后滚刷滚桶部分可以超出光伏板边界，因此不参与边界膨胀和缺板膨胀；但它们仍包含在 `robot_length_cm` 中，用于表达整机物理长度。

### 4.4 清扫方向下的 along / cross

规划一条 lane 时，机器人有一个前进方向：

```text
along_axis = 机器人前进方向
cross_axis = 与前进方向垂直的横向
```

如果 `sweep_axis = block_u`：

```text
along_axis = block_u 方向，对应 cell_col 和 inner_col 的变化
cross_axis = block_v 方向，对应 cell_row 和 inner_row 的变化
```

如果 `sweep_axis = block_v`：

```text
along_axis = block_v 方向，对应 cell_row 和 inner_row 的变化
cross_axis = block_u 方向，对应 cell_col 和 inner_col 的变化
```

后续公式里会用到两个由清扫方向派生出来的格子尺寸：

| 名称 | 含义 | `sweep_axis = block_u` 时 | `sweep_axis = block_v` 时 |
|------|------|---------------------------|---------------------------|
| `inner_cell_along_size_cm` | 沿机器人前进方向的内部格尺寸 | `inner_u_size_cm` | `inner_v_size_cm` |
| `inner_cell_cross_size_cm` | 沿机器人横向宽度方向的内部格尺寸 | `inner_v_size_cm` | `inner_u_size_cm` |

也就是说，`inner_cell_along_size_cm` 和 `inner_cell_cross_size_cm` 不是地图里的新字段，而是 planner 根据当前 `sweep_axis` 从前面派生出的 `inner_u_size_cm / inner_v_size_cm` 中选出来的运行时变量。

### 4.5 footprint 占用格数

机器人 footprint 在当前 heading 下大约覆盖多少内部格：

```text
footprint_along_cells = ceil(safe_length_cm / inner_cell_along_size_cm)
footprint_cross_cells = ceil(safe_width_cm  / inner_cell_cross_size_cm)
```

这表示车体加安全余量可能覆盖的总格数。

### 4.6 中心格膨胀格数

判断某个中心格在当前 heading 下是否离边界足够远时，用半尺寸计算：

```text
inflate_along_cells = max(0, ceil(safe_half_length_cm / inner_cell_along_size_cm) - 1)
inflate_cross_cells = max(0, ceil(safe_half_width_cm  / inner_cell_cross_size_cm) - 1)
```

含义：

- `- 1` 表示当前中心格自身已经位于一个内部格中心，离散膨胀只额外禁用中心格外侧需要避让的格数；
- 距离边界小于 `inflate_cross_cells` 的横向中心格可能贴边；
- 距离缺板洞小于对应膨胀格数的中心格可能压到缺板；
- 桥边本身不在中心格图中生成独立状态，桥端附近如果落入 block 边界膨胀区，会表现为 `BLOCKED_BOUNDARY`，再由 bridge context 决定是否允许有限例外。

### 4.7 离散膨胀和连续几何的关系

当前实现使用离散膨胀作为快速规划图：

```text
某些中心格先标记为 BLOCKED
```

连续几何校验作为后续安全校验扩展：

```text
把机器人矩形 footprint 放到候选 pose 上
检查它是否与 cell polygon、缺板 polygon、bridge polygon、障碍 polygon 相交或越界
```

也就是说：

```text
离散膨胀用于生成候选和搜索
连续几何用于最终安全确认
```

---

## 五、生成可通行中心格图

### 5.1 中心格定义

路径中的一个中心格可以表示为：

```text
center_inner_cell = (block_id, cell_row, cell_col, inner_row, inner_col)
```

机器人中心放在该格中心点，机器人 footprint 覆盖周围若干内部格。

### 5.2 中心格状态

规划器内部可以为每个 block 生成一张中心格状态图：

```text
center_grid[block_id][heading][cell_row][cell_col][inner_row][inner_col]
```

或者不显式保存整张图，而是提供查询函数：

```text
is_traversable(center_cell, heading)
```

其中 `heading` 表示机器人车体当前朝向。当前实现只生成 `block_u_positive / block_u_negative / block_v_positive / block_v_negative` 四种轴向 heading 的中心格状态；block 内清扫、普通 deadhead 和桥端接近/离开都落到这四种 heading 上检查。

中心格状态：

| 状态 | 含义 |
|------|------|
| `FREE` | 可作为机器人中心格 |
| `BLOCKED_MISSING_CELL` | 当前 cell 是缺板 |
| `BLOCKED_BOUNDARY` | 靠近小板/block 外边界，footprint 会越界或贴边 |
| `BLOCKED_MISSING_INFLATION` | 靠近缺板洞，footprint 会压到缺板 |
| `BLOCKED_OBSTACLE` | 当前 block 不参与清扫，或后续障碍/禁行区膨胀导致不可用 |
| `UNKNOWN` | 数据不足或尚未计算 |

`BLOCKED_BRIDGE_EDGE` 目前只是预留状态。当前中心格图生成流程不会单独把桥端附近格子标成 `BLOCKED_BRIDGE_EDGE`；桥端是否可用由 bridge 转场流程在桥上下文中检查。桥边中心格如果只是因为 block 边界膨胀被标为 `BLOCKED_BOUNDARY`，可以在上桥、下桥和离桥初始连接阶段使用有限例外；普通 clean / deadhead 仍只接受 `FREE`。

### 5.3 可通行中心格生成流程

```text
for each block:
  if block.cleanable == false:
    skip or mark all blocked

  derive block-level inner cell size statistics

  for each candidate heading used inside this block:
    compute heading-dependent footprint projection and inflation cells

    for each cell position (cell_row, cell_col):
      if grid[cell_row][cell_col] == 0:
        mark all inner cells as BLOCKED_MISSING_CELL for this heading
        continue

      for each inner cell (inner_row, inner_col):
        center = inner cell center
        compute global inner row / col inside this block

        if center is inside boundary inflation band for this heading:
          mark BLOCKED_BOUNDARY for this heading
        elif center is inside missing-cell inflation area:
          mark BLOCKED_MISSING_INFLATION for this heading
        else:
          mark FREE for this heading
```

当前实现使用 block 级内部格中位尺寸计算 heading 相关的 `u/v` 膨胀格数，并在离散中心格图上完成边界和缺板膨胀过滤；桥端可用性和桥边界例外不在这一步写入中心格状态，而是在 bridge 转场规划中结合 endpoint、approach heading、bridge edge center 和 staging pose 单独判断。

单 block 规划时，`SnakeCoveragePlanner` 只调用 `CenterGridBuilder::build_block(...)` 构建当前 block 的中心格，避免为每个单板请求重复生成全图中心格。全局规划时，`GlobalCoveragePlanner` 调用 `CenterGridBuilder::build(...)` 一次性构建全图中心格，并在所有 block 候选、bridge 转场和全局搜索中复用。

注意：同一个中心格在不同 heading 下可能状态不同。例如机器人沿 `block_u` 方向放置时可行，沿 `block_v` 方向放置时可能因为长宽投影不同而不可行。因此中心格图必须和 heading 相关；不能在不知道 heading 的阶段生成唯一一张全局 `FREE/BLOCKED` 图。

### 5.4 边界约束：`BLOCKED_BOUNDARY` 的来源

边界约束不是独立于中心格图之外的规划流程，而是生成可通行中心格图时的一类阻塞原因。

主逻辑仍然是：

```text
对每个候选中心格：
  把机器人 footprint 放到该中心格上
  如果 footprint 越出小板、block 或桥面可通行边界
    标记为 BLOCKED_BOUNDARY
```

也就是说，理论上只要对每个候选中心格做完整 footprint 几何检查，就可以自然得到哪些格子靠边不可用。

文档中提到“边界膨胀”，只是为了说明一种离散快速近似：

```text
边界膨胀 = footprint 边界碰撞检查的离散近似
```

例如机器人半宽加安全距离是 `45cm`，内部格在横向上约 `20cm`，则靠近边界约：

```text
ceil(45 / 20) = 3 格
```

以内的中心格大概率不能作为机器人中心格。

#### 5.4.1 按 heading 计算边界膨胀格数

边界膨胀不能只看“清扫方向”本身。更准确地说：

```text
清扫方向决定机器人 heading
机器人 heading 决定车体长宽分别投影到 block_u / block_v 的哪个方向
边界约束要根据这个投影，分别检查 u 方向边界和 v 方向边界
```

如果 `sweep_axis = block_u`，机器人长度沿 `block_u`，宽度沿 `block_v`：

```text
u 方向边界，也就是靠近 u_min / u_max 的位置：
  boundary_inflate_u_cells = max(0, ceil(safe_half_length_cm / inner_u_size_cm) - 1)

v 方向边界，也就是靠近 v_min / v_max 的位置：
  boundary_inflate_v_cells = max(0, ceil(safe_half_width_cm / inner_v_size_cm) - 1)
```

如果 `sweep_axis = block_v`，机器人长度沿 `block_v`，宽度沿 `block_u`：

```text
u 方向边界，也就是靠近 u_min / u_max 的位置：
  boundary_inflate_u_cells = max(0, ceil(safe_half_width_cm / inner_u_size_cm) - 1)

v 方向边界，也就是靠近 v_min / v_max 的位置：
  boundary_inflate_v_cells = max(0, ceil(safe_half_length_cm / inner_v_size_cm) - 1)
```

其中：

- `boundary_inflate_u_cells` 表示沿 `inner_col` 方向，靠近 `u_min/u_max` 边界的多少个中心格不可用；
- `boundary_inflate_v_cells` 表示沿 `inner_row` 方向，靠近 `v_min/v_max` 边界的多少个中心格不可用。

#### 5.4.2 边界约束示例

```text
cell_model.inner_rows = 3
boundary_inflate_v_cells = 1
```

则 `v` 方向上：

```text
inner_row = 0  靠近 v_min 边，不可作为中心格
inner_row = 1  可能可用
inner_row = 2  靠近 v_max 边，不可作为中心格
```

如果动态计算得到 `boundary_inflate_v_cells = 2`，而总共只有 3 行内部格，则该 heading 下可能没有可用中心格。

当前离散实现使用上述格子膨胀快速过滤中心格；连续 footprint 几何校验属于后续安全校验扩展，用于避免因为 cell polygon 不规则、边界倾斜或桥面较窄导致误判。

### 5.5 缺板约束：`BLOCKED_MISSING_CELL` 和 `BLOCKED_MISSING_INFLATION` 的来源

缺板约束也不是独立于中心格图之外的规划流程，而是生成可通行中心格图时的另一类阻塞原因。

缺板需要分成两层处理：

| 阻塞原因 | 含义 |
|----------|------|
| `BLOCKED_MISSING_CELL` | 当前中心格所在的 cell 本身就是缺板，即 `grid[row][col] == 0` |
| `BLOCKED_MISSING_INFLATION` | 当前中心格所在 cell 存在，但机器人 footprint 会压到旁边缺板区域 |

主逻辑仍然是：

```text
对每个候选中心格：
  如果它属于 grid == 0 的 cell
    标记为 BLOCKED_MISSING_CELL
  否则把机器人 footprint 放到该中心格上
  如果 footprint 覆盖到缺板区域
    标记为 BLOCKED_MISSING_INFLATION
```

文档中提到“缺板膨胀”，只是为了说明一种离散快速近似：

```text
缺板膨胀 = footprint 与缺板区域碰撞检查的离散近似
```

它要表达的是：缺板不只影响自己这一块；缺板旁边的有板 cell，也可能因为机器人 footprint 会悬空到缺板洞上方而变成不可用。

#### 5.5.1 离散缺板膨胀实现

离散实现可以分两步：

1. 先把每个缺板 cell 的所有内部格标记为 `BLOCKED_MISSING_CELL`；
2. 再以缺板内部格为源，按 `inflate_along_cells` 和 `inflate_cross_cells` 向周边内部格扩张，把受影响格标记为 `BLOCKED_MISSING_INFLATION`。

这里的 `inflate_along_cells` 和 `inflate_cross_cells` 来自第 4.6 节，是根据当前 heading、机器人半尺寸、安全余量和内部格实际尺寸动态计算出来的。

#### 5.5.2 缺板约束示例

```text
原始 grid: [1, 1, 0, 1]
```

只看 `grid` 时，连续有板区间是：

```text
[0..1] 和 [3..3]
```

但考虑 footprint 后：

```text
cell 1 靠近 cell 2 的若干 inner_col 可能被禁用
cell 3 靠近 cell 2 的若干 inner_col 可能被禁用
```

最终可通行 segment 可能变成：

```text
cell 0 全部可用
cell 1 只剩远离缺板的一部分内部格可用
cell 3 可能只剩远离缺板的一部分内部格可用，甚至完全不可用
```

因此，缺板处理不能只理解成“不生成缺板 waypoint”。更准确地说，缺板区域会通过 footprint 约束影响周围有板 cell 的中心格可通行性。

### 5.6 多原因标记

一个中心格可能同时因为多个原因不可用，例如既靠近边界又靠近缺板。内部原因集合可表示为：

```text
blocked_reasons = [BOUNDARY, MISSING_INFLATION]
```

这样 RViz 和日志能解释为什么某段无法规划。

---

## 六、动态计算换行格子数 lane_stride

### 6.1 lane_stride 是什么

`lane_stride` 是相邻清扫 lane 的中心线间隔，最终表达为内部格数量。

如果沿 `block_u` 清扫：

```text
lane 内沿 inner_col 前进
换行沿 inner_row 移动
lane_stride 作用在 inner_row 上
```

如果沿 `block_v` 清扫：

```text
lane 内沿 inner_row 前进
换行沿 inner_col 移动
lane_stride 作用在 inner_col 上
```

### 6.2 按清扫宽度计算 stride 初值

清扫覆盖用 `cleaning_width_cm`，不是 `robot_width_cm`，也不是前/后滚刷滚桶直径。这里的 `cleaning_width_cm` 表示滚刷长度 / 横向清扫覆盖宽度。

```text
effective_cleaning_width_cm = cleaning_width_cm * (1 - overlap_ratio)

lane_stride_cells = floor(effective_cleaning_width_cm
                          / inner_cell_cross_axis_size_cm)

lane_stride_cells = max(1, lane_stride_cells)
```

其中 `inner_cell_cross_axis_size_cm` 应动态取值：

- 可以取当前 block 的横向内部格中位数；
- 对几何差异明显的 block，可以分 lane 或分 cell 重新计算；
- 最终还需要覆盖检查验证没有漏扫。

### 6.3 stride 不能只看清扫宽度

按清扫宽度算出的 stride 只是初值，还要检查：

1. 换行后的 lane 中心格是否存在 `FREE`；
2. 换行路径是否能从上一条 lane 安全到达下一条 lane；
3. 相邻 lane 的清扫覆盖是否有空隙；
4. 最后一段剩余未覆盖宽度是否需要补扫；
5. stride 是否导致机器人中心贴边；
6. stride 是否跨过缺板膨胀区；
7. 当前 block 的内部格数量是否足够支持这个 stride。

当前实现会同时枚举两种 `sweep_axis`，并根据 `planning_search_effort` 选择 stride / offset 组合：`quality / exhaustive` 会从初始 `lane_stride` 降到 1 枚举更多组合，`fast / balanced` 只取代表性 stride 和 offset。每个组合会尝试生成一个 lane candidate；只有该组合提取到至少一个可清扫 segment 时，才会加入候选列表。对已选中的非边界 lane，如果没有 `FREE` 中心格，会记录到 `unreachable_segments`，该 lane candidate 的 `coverage_complete` 会变为 false；边界 lane（`cross == 0` 或 `cross == cross_total - 1`）没有 `FREE` 中心格时会跳过，不作为 fatal unreachable。最终覆盖是否完整由后续 `covered_clean_center_count / total_clean_center_count` 判断。

### 6.4 lane 起点偏移

即使 `lane_stride` 确定，也不一定从横向第 0 个内部格开始。

例如横向有 6 个内部格，`lane_stride = 2`，可能的 lane 中心序列有：

```text
offset 0: 0, 2, 4
offset 1: 1, 3, 5
```

但如果边界膨胀导致 `0` 和 `5` 不可用，则实际更可能选择：

```text
1, 3
```

所以候选枚举时应包含：

```text
lane_offset in [0, lane_stride - 1]
```

并用覆盖完整性检查决定哪个 offset 更好。

### 6.5 stride 示例

假设某方向横向内部格尺寸约为 `20 cm`：

```text
cleaning_width_cm = 55
overlap_ratio = 0.2
```

则：

```text
effective_cleaning_width = 55 * (1 - 0.2) = 44 cm
lane_stride = floor(44 / 20) = 2 格
```

因此换行初值是 2 格。但如果跨 2 格后的中心格被边界膨胀或缺板膨胀禁用，就不能直接执行，需要换 offset、降级 stride 或换清扫方向。

### 6.6 最后一条 lane 的补扫

按固定 `lane_stride` 排列 lane 时，不能只生成等间隔 lane 后就结束。因为横向区域的边界附近可能只剩一小段未覆盖宽度。

这段剩余宽度即使小于一次完整的 `lane_stride`，也不能默认忽略。当前实现不是简单追加最后一个横向索引，而是根据 `cleaning_width_cm` 计算清扫横向覆盖半径，在左右 / 上下两个边界附近分别寻找一条能覆盖边界的可用 lane，并为该边界 lane 额外加入一条相邻 backup lane。

示例：

```text
横向共有 7 个可清扫内部格
lane_stride = 3
lane_offset = 1
```

按固定间隔得到：

```text
lane centers: 1, 4
```

如果只扫这两条，靠近末尾的区域可能还有剩余覆盖不足：

```text
cross index: 0 1 2 3 4 5 6
                  ^     ^
                lane  lane
```

此时 planner 会检查边界剩余区域。如果 `cross index = 6` 附近仍有未覆盖的有板区域，并且存在可通行中心格，就补一条 lane：

```text
lane centers: 1, 4, 6
```

补扫 lane 不一定满足完整 `lane_stride` 间隔，它的作用是保证覆盖完整性。当前实现中，是否补扫由 `enable_tail_coverage` 控制：开启后会根据清扫覆盖半径在两个边界附近寻找可用边界 lane，并尝试加入相邻 backup lane；这些 lane 后续仍按普通 lane 提取 segment。如果补扫的是最外侧边界 lane 且整条 lane 没有 `FREE` 中心格，当前实现会跳过它，不把边界安全膨胀导致的不可达 lane 直接记为 fatal unreachable。

### 6.7 换 lane 轨迹约束

`lane_stride` 只表示两条 lane 的中心线间隔，不代表机器人一定能从上一条 lane 直接到达下一条 lane。

当前机器人在 block 上的运动能力按下面约束处理：

```text
机器人不能斜向穿行
机器人在 block 上平移时，只能沿 block_u 或 block_v 方向移动
机器人不按连续圆弧转弯
机器人可以在某个 FREE 中心格上原地转向到目标 heading
原地转向角度不限制为 90 度，但转向前后的 footprint 必须安全
```

因此，换 lane 必须显式生成一段 `deadhead` 连接轨迹，而不是只把 lane index 加上 `lane_stride`。由于 lane 方向和横向移动方向本身相差 90 度，常见换 lane 动作看起来会包含两次 90 度原地转向；但这不是底盘转向能力的限制，而是因为 block 内允许的平移方向只有 `block_u` 和 `block_v`。

沿 `block_u` 清扫时，一种典型换 lane 轨迹是：

```text
                 +block_v
                    ↑
                    │
lane 1   ← ← ← ← ←  ●────────────── ③ 原地转向后，沿 -block_u 清扫
                    ↑
                    │ ② 沿 cross_axis 横向移动 lane_stride 格
                    ↑
lane 0   → → → → →  ●────────────── ① lane 末端原地转向到 +block_v
                    │
                    └────────────────────────→ +block_u
```

这里两个 `●` 表示换 lane 轨迹中的转向中心格。机器人先在 lane 0 末端原地转向到 `+block_v`，沿 `+block_v` 移动到 lane 1，再原地转向，对齐 lane 1 的反向清扫方向。

如果从另一侧换 lane，也可能沿 `-block_v` 横向移动：

```text
                 +block_v
                    ↑
                    │
lane 0   → → → → →  ●────────────── ① lane 末端原地转向到 -block_v
                    ↓
                    │ ② 沿 cross_axis 横向移动 lane_stride 格
                    ↓
lane 1   ← ← ← ← ←  ●────────────── ③ 原地转向后，沿 -block_u 清扫
                    │
                    └────────────────────────→ +block_u
```

这里两个 `●` 同样表示转向中心格。机器人在 lane 0 末端原地转向到 `-block_v`，沿 `-block_v` 移动到 lane 1，再原地转向，对齐 lane 1 的反向清扫方向。

所以 lane 间连接按下面流程检查：

```text
上一条 lane 末端中心格
  -> 原地转向到 cross_axis 方向
  -> 沿 cross_axis 移动若干 FREE 中心格
  -> 再原地转向到下一条 lane 的清扫 heading
  -> 进入下一条 lane
```

这段 `deadhead` 轨迹中的每一步都必须按当前执行 heading 查询对应的可通行中心格，而不是复用清扫 heading 的中心格图：

- lane 内清扫时，使用沿 lane 方向的 heading；
- 横向换 lane 时，使用沿 cross_axis 方向的 heading；
- 原地转向时，当前实现检查转向目标 heading 下该中心格是否 `FREE`；如果后续引入非轴向 heading 或更严格的转向安全要求，再增加连续 footprint 几何检查或角度采样检查。

具体要求包括：

- 转向目标 heading 下，转向中心格必须可通行；
- 横向移动经过的每个中心格在横移 heading 下都是 `FREE`；
- 横向移动方向只能沿 `block_u` 或 `block_v`，不能斜穿；
- 最终 heading 必须和下一条 lane 的清扫方向一致。

如果这段连接轨迹无法生成，即使下一条 lane 本身存在，也不能认为两条 lane 可直接连接。当前实现会在两个 L 形正交连接拐角中选择可行且转弯更少、waypoint 更少的方案；如果两个 L 形方案都不可行，则记录连接失败或把对应 segment 标记为不可达，不引入 BFS / A* 式绕行。

---

## 七、提取 lane 和 lane segment

### 7.1 按 `grid` 提取连续有板区间

先只看 `grid`，把连续存在小板的位置提取出来。这里还不考虑机器人 footprint、边界安全距离和 heading，只是得到“哪里有板”的拓扑区间。

当 `sweep_axis = block_u`：

```text
每个 cell_row 是一条 lane
沿 cell_col 扫描
连续 `grid[row][col] == 1` 的位置形成一个有板区间
```

示例：

```text
row 0: [1, 1, 1]    -> col 0..2
row 1: [1, 1, 0]    -> col 0..1
row 2: [1, 1, 0, 1] -> col 0..1 和 col 3..3
```

当 `sweep_axis = block_v`：

```text
每个 cell_col 是一条 lane
沿 cell_row 扫描
连续 `grid[row][col] == 1` 的位置形成一个有板区间
```

### 7.2 从有板区间到可通行 segment

按 `grid` 得到的有板区间不能直接拿来生成路径，还要叠加当前 heading 下的可通行中心格图。

流程：

```text
for each lane cross index selected by stride and offset:
  scan all along-axis inner indices in the current snake direction
  append consecutive centers whose state is FREE under this lane heading
  when a blocked center is encountered, close the current segment
  output all non-empty traversable segments
```

结果可能是：

- 有板区间完整保留；
- 有板区间两端因为贴边被裁掉；
- 有板区间中间因为缺板膨胀被切断；
- 有板区间全部不可通行。

### 7.3 segment 的离散表达

一个可通行 segment 可以表达为：

```yaml
axis: block_u
lane_index: 1
lane_offset_inner: 1
start:
  cell_row: 1
  cell_col: 0
  inner_row: 1
  inner_col: 0
end:
  cell_row: 1
  cell_col: 4
  inner_row: 1
  inner_col: 5
cells:
  - [1, 0]
  - [1, 1]
  - [1, 2]
  - [1, 3]
  - [1, 4]
```

这里的 `lane_offset_inner` 表示该 lane 在横向内部格上的中心位置。

### 7.4 生成清扫 waypoint

清扫 waypoint 只从可通行 segment 生成：

```yaml
block_id: 1
cell_row: 1
cell_col: 3
inner_row: 1
inner_col: 4
heading: block_u_positive
type: clean
brush_state: on
```

每个 clean waypoint 必须满足：

- 对应 cell 的 `grid == 1`；
- 对应内部格状态是 `FREE`；
- 机器人 footprint 安全；
- 清扫覆盖范围落在有板区域；
- 不贴边、不压缺板、不跨障碍。

### 7.5 连接 waypoint

不同 segment 之间可能需要连接 waypoint：

```yaml
type: deadhead
brush_state: off
```

连接 waypoint 也必须落在可通行中心格上。不能为了连接两段清扫路径而跨越缺板洞或边界禁区。

如果无法安全连接，输出不可达原因，不静默跳过有板区域。

---

## 八、生成 block 覆盖候选

对每个 cleanable block，规划器应生成多个覆盖候选，而不是固定一种蛇形。候选既要表达 block 内清扫形态，也要表达它能否作为全局路径中的入口和出口。

当前规划中有两类候选：

| 候选类型 | 用途 | 特点 |
|----------|------|------|
| 自由起点候选 | 单 block 规划、全局候选补充 | 不指定入口，从候选自身的较优起点开始清扫 |
| 入口受限候选 | 跨桥后进入目标 block、全局搜索入口候选 | 指定 entry pose，要求从入口接入后仍尽量保持蛇形覆盖 |

### 8.1 候选参数

候选由以下参数决定：

| 参数 | 取值 | 说明 |
|------|------|------|
| `sweep_axis` | `block_u` / `block_v` | 主清扫方向 |
| `lane_stride` | 动态计算值或降级值 | 换行格子数 |
| `lane_offset` | `0 .. lane_stride-1` | lane 起点偏移 |
| `entry_pose` | 无 / 某个可通行中心格姿态 | 是否要求从指定入口接入 |
| `order_direction` | 正序 / 反序 | 沿蛇形候选的正向或反向执行 |

当前实现中，lane 顺序主要由提取出的 segment 顺序、正反向执行和连接可行性共同决定；不再把 `lane_order`、`first_direction` 当作独立静态字段写入地图。

### 8.2 候选生成流程

```text
for sweep_axis in [block_u, block_v]:
  统计当前 block 的内部格尺寸
  根据 cleaning_width 和 overlap_ratio 计算初始 lane_stride

  根据 planning_search_effort 选择 stride / offset 枚举范围：
    quality / exhaustive 枚举从初始 stride 到 1 的更多组合
    fast / balanced 只取初始值、降级值、1 和代表 offset
  for lane_stride in selected_strides:
    for lane_offset in selected_offsets:
      使用当前清扫 heading 的 FREE 中心格提取 lane segment
      记录该 lane candidate 的 segment、不可达 lane 和基础覆盖信息

      始终生成正序 ordered snake 候选
      始终生成反序 ordered snake 候选
      如果存在 entry_pose:
        两个 ordered snake 候选都先从 entry_pose 接入
        不再生成按局部最短连接贪心扩展的碎片候选
      如果不存在 entry_pose:
        额外允许从候选内部较优 segment 开始，作为自由起点的兜底候选

      为 clean / deadhead / turn 生成 waypoint
      检查相邻 waypoint 是否保持正交、相邻或同点转向
      统计 clean 点数量、连接 segment 数、跳变和不可达原因
      计算候选代价
      保存候选
```

这里的“正序 / 反序”不是为了增加随机性，而是为了处理全局入口位置不同的情况。为控制大场景耗时，当前实现还会根据 `planning_search_effort` 裁剪候选：`fast / balanced` 会按 sweep axis、stride、offset、覆盖完整性、候选代价和 entry pose 距离选择代表性 lane candidate；`quality / exhaustive` 则保留更完整的 stride / offset 枚举。全局规划时每个 block 也会按搜索强度限制 entry constrained、free start 和最终 block coverage candidate 的保留数量。

- 如果入口靠近蛇形起点，正序候选更容易接入；
- 如果入口靠近蛇形终点，反序候选更容易接入；
- 两者都保留给全局层比较。

### 8.3 蛇形顺序和碎片化控制

蛇形路线的基本目标是：

```text
长 clean segment 优先
相邻 lane 尽量首尾相接
必要时使用 deadhead 换 lane
尽量减少点到点频繁换 lane
```

理想形态仍然是：

```text
lane 0: 正向清扫
lane 1: 反向清扫
lane 2: 正向清扫
lane 3: 反向清扫
...
```

但实际 segment 可能因为边界膨胀、缺板膨胀或 heading 限制被切断，因此不是每条 lane 都只有一个 segment。规划器需要在两类目标之间平衡：

1. 尽量连接更多 clean segment，避免漏扫；
2. 不为了连接少量碎片而频繁换 lane、产生大量转弯。

入口受限候选尤其要避免从桥边某个短 segment 开始贪心扩展。否则全局层可能因为它接桥代价低而选择低覆盖局部候选，导致某个较大的 block 在全局结果中只显示一条 clean 线。

因此，入口受限候选优先使用正序 / 反序 ordered snake；自由起点候选才允许使用更灵活的 segment 起点作为兜底。

### 8.4 覆盖完整性检查

每个候选都要检查：

- clean waypoint 是否覆盖了该 lane candidate 中的可清扫中心格；
- 相邻 lane 是否因为 stride 太大出现漏扫；
- 有板区域是否因为没有可通行中心格而不可覆盖；
- segment 间连接是否安全；
- 是否存在 waypoint 落在 `BLOCKED_*` 格子上；
- 是否存在同 block 内非相邻跳变；
- 是否满足机器人原地转向时的 footprint 安全约束。

如果无法完整覆盖，应显式输出：

```text
coverage_complete = false
unreachable_segments = [...]
invalid_reasons = [...]
```

需要注意：

- `success` 表示是否生成了可执行路径；
- `coverage_complete` 表示覆盖是否完整；
- 两者不能简单等同。

当前候选内部判定 `coverage_complete` 时同时要求：原始 lane candidate 自身覆盖完整、没有 skipped segment、已覆盖 clean center 数达到该候选池的参考 clean center 总数、waypoint 连续性检查没有跳变，并且没有新增连接失败原因。也就是说，单纯 `coverage_ratio` 高还不够；如果仍有未连接 segment、非相邻跳变或非边界 lane 不可达，候选仍视为覆盖不完整。

覆盖统计采用离散中心格覆盖，不是连续面积积分。实现上会把 clean waypoint 按清扫宽度换算成横向覆盖半径 `cross_radius_cells`，再用 `(block_id, along_index, cross_index)` 作为 key 放入集合，因此同一个离散中心格被多次扫到不会重复计数。`covered_clean_center_count / total_clean_center_count` 表示参考中心格中有多少唯一中心格被覆盖。

### 8.5 候选排序和代价

候选排序不能只看路径短或转移少，否则容易选到“很容易接入但只覆盖一小段”的候选。

候选比较分两层：先在同一个 block 内生成 connected candidate 并排序，再把它们转换成全局可比较的 block coverage candidate。

connected candidate 内部排序顺序是：

1. 连续性错误少的候选优先；
2. 覆盖完整的候选优先；
3. skipped segment 少的候选优先；
4. clean 覆盖点更多的候选优先；
5. 覆盖比例更高的候选优先；
6. 转弯少的候选优先；
7. deadhead / transition waypoint 少的候选优先；
8. 已连接 segment 数更多的候选优先；
9. 当两者覆盖比例都接近完整时，优先保留 stride 更大的候选，避免过密 lane；
10. 最后再比较总代价。

转换为 block coverage candidate 后，全局候选池排序会重新按：连续性错误、覆盖完整性、覆盖比例、clean 覆盖点数量、转弯数、transition waypoint 数和总代价比较。这里不再单独比较 skipped segment 和 connected segment 数，因为它们已经体现在候选内部筛选、覆盖完整性、unreachable segments 和总代价中。

代价表达为：

```text
total_cost =
  continuity_error_penalty
  + missed_clean_center_penalty
  + coverage_ratio_penalty
  + turn_penalty
  + skipped_segment_penalty
  + unreachable_penalty
  + transition_waypoint_penalty
  + segment_penalty
  - covered_clean_center_bonus
```

其中 missed clean center 不一定来自静态地图全区域，而是来自当前候选集合中可生成的最大清扫中心参考量。这样可以避免某个只包含少量 clean 点的短候选因为自身比例高而被误认为覆盖充分。

当前代码中各项权重大致为：连续性错误 `10000`，漏覆盖中心格 `1000`，覆盖比例缺口 `5000 * (1 - coverage_ratio)`，转弯 `100`，skipped segment `1000`，unreachable lane `1000`，transition waypoint `1`，segment 数 `1`，每个已覆盖中心格给 `0.01` 的轻量 bonus。开启 `debug_score_breakdown` 后，这些分项会以 `penalties{...}` 的形式输出到 `PlanningDebug.score_breakdown`。

当前候选排序目标是：覆盖尽量完整、不可达最少、转弯少、空驶短，并保持蛇形形态。

---

## 九、bridge 转场规划

### 9.1 静态 bridge endpoint

静态 bridge endpoint 示例：

```yaml
block_id: 1
cell_row: 0
cell_col: 2
edge: u_max
inner_row: 1
inner_col: 5
```

它表示桥端绑定到某个实际存在的小板，并使用该小板指定边上的内部格段作为定位锚点。

`edge` 含义：

| edge | 含义 |
|------|------|
| `u_min` | cell 的 `-block_u` 侧边 |
| `u_max` | cell 的 `+block_u` 侧边 |
| `v_min` | cell 的 `-block_v` 侧边 |
| `v_max` | cell 的 `+block_v` 侧边 |

### 9.2 endpoint 不是机器人中心

endpoint anchor 在小板边上，它不是机器人中心点。

规划器需要把静态 endpoint 转换成安全的上桥/下桥中心格：

```text
static endpoint
  -> edge anchor point
  -> approach direction
  -> safe approach center cell
  -> safe departure center cell
```

其中 `safe approach center cell` 在当前实现中分两层处理：

- endpoint 对应 block 和 cell 必须存在，且该 cell 不是缺板；
- endpoint edge 会被投影成桥边中心格 `bridge_edge_center`；
- 桥边中心格必须是 `FREE`，或只是因为边界膨胀被标成 `BLOCKED_BOUNDARY`；
- 沿桥端内侧继续搜索第一个普通 `FREE` 中心格作为 staging pose；
- 桥 centerline 必须能提供非零参考长度；如果配置了 bridge polygon，目前只检查 polygon 点数合法，桥面宽度和连续 footprint 采样属于后续安全校验扩展。

### 9.3 桥端 anchor 点推导

由 endpoint 推导边缘 anchor：

```text
u_min: col_ratio = 0.0, row_ratio = (inner_row + 0.5) / inner_rows
u_max: col_ratio = 1.0, row_ratio = (inner_row + 0.5) / inner_rows
v_min: row_ratio = 0.0, col_ratio = (inner_col + 0.5) / inner_cols
v_max: row_ratio = 1.0, col_ratio = (inner_col + 0.5) / inner_cols
```

该点只用于几何参考和视觉锚点。机器人中心位于边内侧或桥面中心线上满足安全距离的位置。

### 9.4 桥端流程

```text
读取 bridge endpoint
  -> 确认 endpoint 指向 grid == 1 的 cell
  -> 由 cell polygon + edge + inner_row/inner_col 推导 edge_anchor_point
  -> 根据 edge 推导桥端 approach heading
  -> 推导桥边中心格 bridge_edge_center
  -> 检查桥边中心格是否可用于 bridge context
  -> 沿桥端内侧寻找普通可通行 staging pose
  -> 估算桥参考长度和方向
  -> 通过后允许使用该 bridge
```

桥边中心格可能落在普通边界膨胀区内。普通 clean / deadhead 不能使用这类中心格，但 bridge approach / departure 允许使用有限的边界例外：

- 只允许 `FREE` 或 `BLOCKED_BOUNDARY` 的桥边中心格进入 bridge context；
- `BLOCKED_MISSING_CELL`、`BLOCKED_MISSING_INFLATION`、`BLOCKED_OBSTACLE` 等非边界原因不能放行；
- 桥边例外只用于上桥、下桥和离桥初始连接，不扩散到普通 block 内清扫。

如果找不到桥边可用位置或内侧 staging pose，即使静态地图导入成功，该 bridge 对当前机器人也不可用。

### 9.5 跨桥轨迹

跨桥可以拆成三段理解：

```text
source block 内部
  1) 从当前清扫出口空驶到 source safe_approach_center_cell
  2) 在 safe_approach_center_cell 原地转向，对齐桥入口方向

bridge 上
  3) 切换到视觉巡线模式，跟随桥面实际中心线从 source 端行驶到 target 端

target block 内部
  4) 到达 target safe_departure_center_cell 附近
  5) 重新确认目标 block 内的离散定位
```

也就是说：

- `safe_approach_center_cell` 是 source block 内部的一个可通行中心格，用于安全接近桥入口；
- `safe_departure_center_cell` 是 target block 内部的一个可通行中心格，用于跨桥结束后重新进入 block 内路径；
- 地图中的 `bridges[].centerline` 是桥中心线的静态几何参考，当前主要用于估算桥参考长度、RViz 显示和执行端初始方向参考；如果未提供 centerline，则用两端 anchor 点距离估算参考长度；
- 实际过桥时不需要 planner 在桥上生成密集几何 waypoint，而是由视觉模块识别桥面中心线并巡线通过；
- endpoint anchor 在小板边上，只用于把静态 bridge 和小板边缘对齐，不直接作为机器人中心 waypoint。

示意：

```text
source block                         bridge                          target block

  FREE center cell                                                     FREE center cell
  safe_approach_center_cell                                            safe_departure_center_cell
          ●                                                                 ●
          │                                                                 │
          │ approach_bridge                                                 │ reinit_vision
          ▼                                                                 ▲
   source endpoint anchor ═══════ 视觉巡线：桥面实际中心线 ═══════ target endpoint anchor
                              bridge_crossing
```

因此，`bridge_crossing` waypoint 是一个执行语义，而不是一串桥上坐标点。输出只表达“使用哪座桥”：

```yaml
- type: bridge_crossing
  brush_state: off
  bridge_id: 1
```

过桥方向不需要作为独立字段写入 waypoint。执行端可以根据前一个 `approach_bridge` 所在 block，以及后一个 `reinit_vision` / 目标 block，判断从 bridge 的哪个 endpoint 进入、从哪个 endpoint 离开。

例如：

```yaml
- type: approach_bridge
  block_id: 1
  bridge_id: 1

- type: bridge_crossing
  bridge_id: 1

- type: reinit_vision
  block_id: 2
```

如果 `bridge_id=1` 的两个 endpoints 分别属于 `block_id=1` 和 `block_id=2`，则执行端可推导本次过桥方向为 `block 1 -> block 2`。反向过桥时，前后的 block 顺序相反即可，不需要额外的 `crossing_direction` 字段。

执行端根据 `bridge_id` 找到静态 bridge 信息，并切换到视觉巡线：

```text
进入 bridge_crossing
  -> 关闭刷盘
  -> 根据前后 waypoint 推导进入端和离开端
  -> 用 bridges[].centerline 作为初始方向参考
  -> 视觉检测桥面实际中心线
  -> 跟随中心线行驶直到到达对侧 endpoint 附近
  -> 切换到 reinit_vision，确认 target block 内离散定位
```

当前跨桥可用性检查重点是：

- bridge 必须启用，且恰好连接两个不同 block；
- 两端 endpoint 指向的 block 和 cell 必须存在，cell 不能是缺板；
- 每端桥边中心格只能是 `FREE` 或 `BLOCKED_BOUNDARY`，不能被缺板、缺板膨胀或障碍原因阻塞；
- 每端需要能在桥端内侧找到普通 `FREE` staging pose，或在不使用边界例外时直接使用桥边中心格；
- bridge 需要有非零参考长度，优先来自 centerline，缺省时来自两端 anchor 距离；
- bridge polygon 当前只做点数合法性检查；桥面宽度和沿桥 footprint 采样属于后续安全校验扩展。实际执行仍由视觉巡线控制，不沿静态 centerline 生成导航 waypoint。

### 9.6 下桥后接入目标 block

机器人跨桥进入目标 block 后，需要先完成视觉重新初始化，然后从桥后 staging pose 接入目标 block 内的候选路径。

当前实现不强制“先去几何角点再清扫”，而是把下桥后的可通行入口作为 entry pose，交给全局候选比较：

```text
跨桥到达 target block
  -> reinit vision / 确认当前离散定位
  -> 从 bridge departure pose 空驶到候选 entry pose
  -> 从该 entry pose 接入目标 block 的覆盖候选
```

候选 entry pose 可以来自两类来源：

1. 靠近 block 四角方向的可通行中心格，用于生成入口受限蛇形候选；
2. 自由起点覆盖候选自身的入口，用于补充覆盖质量更好的候选。

全局规划会同时比较这些候选，而不是固定只使用桥端最近点或固定只使用某个角点。

桥后离桥连接有一个特殊约束：起点可能位于桥边界附近，因此离桥初始转向允许使用 bridge context 的边界例外；但一旦进入普通 block 内部路径，后续 deadhead 和 clean 仍必须满足普通中心格可通行规则。

这样可以避免两类问题：

- 如果只选择桥边最近的一条 lane，可能导致目标 block 覆盖过低；
- 如果完全按普通 deadhead 约束处理桥边转向，可能导致完整覆盖候选无法从桥边接入。

### 9.7 内部缺板洞边

某个 cell 的相邻位置是 `grid == 0` 时，要区分：

- block 外边界：可以作为桥端候选；
- 内部缺板洞：默认不能作为桥端候选。

示例：

```text
grid:
[1, 1, 1]
[1, 0, 1]
[1, 1, 1]
```

中心 `(1,1)` 是内部缺板洞。周围小板面向这个洞的边不会自动生成 bridge endpoint；如果确实需要从这里接桥，通过静态地图中的人工 bridge 明确表达，并在 planner 中继续做 footprint、桥宽和接近方向校验。

---

## 十、跨 block 全局规划

跨 block 规划应建立在 block 覆盖候选、bridge 可用性、候选 entry/exit pose 和转场代价之上。

当前全局规划采用“候选组合搜索”的方式，而不是先把每个 block 独立规划完再简单串接。原因是：某个 block 选择哪个入口、哪个出口、哪种蛇形方向，会直接影响后续过桥是否方便。

跨 block 转场的执行策略为：

```text
当前 block 覆盖候选出口
  -> 空驶到 source bridge approach pose
  -> approach_bridge
  -> bridge_crossing 视觉巡线
  -> target block reinit_vision / departure pose
  -> 空驶到目标 block 候选 entry pose
  -> 执行目标 block 覆盖候选
```

全局 planner 需要在多个选择之间做代价搜索，例如：

- 下一个清扫哪个 block；
- 是否需要经过已清扫 block 中转；
- 使用哪一座 bridge 转场；
- 当前 block 选择哪个覆盖候选和出口；
- 目标 block 选择入口受限候选还是自由起点候选；
- 目标 block 选择哪一种蛇形方向和 lane offset；
- 跨桥后从 departure pose 到目标 entry pose 是否可达。

bridge 只负责 block 间转场；目标 block 内是否从角点、桥边附近或自由起点开始，由候选质量和全局代价共同决定。全局层必须同时比较过桥便利性和清扫覆盖质量，不能只选择最容易接桥的一小段局部轨迹。

### 10.1 全局搜索图

跨 block 规划建成一个搜索图：

```text
node = 当前已经清扫的 block 集合
     + 当前所在 block
     + 当前 pose / exit pose
     + 当前 block 使用的 coverage_candidate
     + 当前访问模式 clean / transit

edge = 从当前状态经 bridge 转移到下一个 block 的某个候选入口
```

当前实现使用贪心搜索模型：每轮从当前位置出发，对剩下未清扫 block 评估所有候选的转场代价和覆盖质量，选最优进入下一轮。已清扫 block 可作为 transit 中转。

转场 edge 表示一次完整跨 block 转移：

```text
当前 pose
  -> source bridge approach pose（block 内 L 形空驶）
  -> bridge_crossing(bridge_id)
  -> target bridge departure pose
  -> 目标 block coverage_candidate entry pose（block 内 L 形空驶，桥锚点首转弯允许 BlockedBoundary）
```

### 10.2 候选生成

全局搜索前，应先生成这些候选：

```text
for each block:
  生成每个角附近的少量可通行 entry pose
  对每个 entry pose 生成若干入口受限覆盖候选
  同时生成若干自由起点覆盖候选
  合并候选池，按连续性、覆盖质量、转弯和代价排序
  只保留排序靠前的有限数量候选供全局搜索使用

for each static bridge:
  验证 bridge 两端是否可用于当前机器人
  推导 source / target 两侧的 approach、edge、departure 信息
  记录 bridge reference length 和可用性

for each search state:
  如果目标 block 未清扫:
    尝试连接到目标 block 的各个 coverage candidate
  如果目标 block 已清扫:
    尝试连接到该 block 的 transit pose，只生成空驶中转
```

这些候选不一定都可用。只要某段连接轨迹、原地转向、bridge crossing 或候选入口不可行，对应 edge 就会被标记为不可用，而不是只按几何距离强行连接。

全局候选池必须同时保留覆盖质量较好的候选和桥接便利的候选。不能只因为入口受限候选存在，就完全丢弃自由起点候选；也不能只保留覆盖完整候选而导致全局桥接路径消失。

### 10.3 代价设计

全局 edge 代价可以由多部分组成：

```text
edge_cost =
  source_exit_to_bridge_cost
  + bridge_crossing_cost
  + bridge_departure_to_target_entry_cost
  + target_candidate_cost
  + transit_penalty_or_cost
```

其中：

| 代价项 | 含义 |
|--------|------|
| `source_exit_to_bridge_cost` | 当前 pose 到 source bridge approach pose 的空驶距离和转向代价 |
| `bridge_crossing_cost` | 跨桥视觉巡线动作代价，可用 bridge 参考长度估算 |
| `bridge_departure_to_target_entry_cost` | 从 target bridge departure pose 到目标候选 entry pose 的空驶和转向代价 |
| `target_candidate_cost` | 目标 block 覆盖候选内部的清扫、空驶、转弯、漏覆盖和不可达代价 |
| `transit_penalty_or_cost` | 已清扫 block 中转时的空驶代价和轻量惩罚 |

候选内部代价表达为：

```text
candidate_cost =
  continuity_error_weight * continuity_error_count
  + missed_clean_weight * missed_clean_center_count
  + coverage_ratio_weight * (1 - coverage_ratio)
  + skipped_segment_weight * skipped_segment_count
  + turn_weight * transition_turn_count
  + deadhead_weight * transition_waypoint_count
  + route_complexity_cost
```

代价设计的关键不是只让路径最短，而是避免下面这种错误选择：

```text
桥后入口很容易连接
  但只覆盖一小段 clean segment
  总路径短
  -> 被误选为全局最优
```

因此全局代价必须让覆盖质量进入比较：

- 覆盖更多 clean center 的候选优先；
- 漏覆盖和 skipped segment 要有明显惩罚；
- 覆盖相近时，再选择转弯少、deadhead 少的候选；
- bridge 接入方便不能压过覆盖严重不足。

block 内平移方向仍然受限，只能沿 `block_u` 或 `block_v`；跨桥上桥/下桥时，桥边阶段可以使用 bridge context 的边界例外，但进入普通 block 内部路径后仍恢复普通可通行规则。

### 10.4 搜索算法

当前全局搜索统一使用贪心（greedy）策略：每轮从当前位置出发，对所有未清扫 block 的候选评估转场代价（第一步使用精确 transit，后续步使用 BFS 桥路径 + L 形空驶），选综合代价（覆盖完整性 × 权重 + 转场距离 + 转弯数）最低的 block 及其候选。已清扫 block 可作为 transit 中转。

搜索结束条件为所有目标 block 都已清扫。每个 block 的 `coverage_complete` 仍根据实际选中候选单独判断。

### 10.5 输出结果

搜索完成后，全局路线输出：

```text
block_order
visit_order(clean / transit)
selected_bridge_per_transition
selected_coverage_candidate_per_block
ordered waypoints
planning_debug.total_cost
planning_debug.coverage_complete
unusable_bridges
unreachable_segments
invalid_reasons
```

如果无法找到覆盖所有 cleanable block 的可执行路径，明确输出失败原因，例如：

```text
coverage_complete = false
unusable_bridges = [...]
unreachable_segments = [...]
invalid_reasons = [...]
```

跨 bridge 执行流程：

```text
CLEANING_BLOCK
  -> APPROACH_BRIDGE
  -> BRIDGE_CROSSING_VISION_LINE_FOLLOWING
  -> ARRIVE_TARGET_BLOCK
  -> REINIT_VISION
  -> DEADHEAD_TO_TARGET_ENTRY
  -> CLEANING_BLOCK_FROM_SELECTED_CANDIDATE
```

如果目标 block 是 transit：

```text
CURRENT_BLOCK
  -> APPROACH_BRIDGE
  -> BRIDGE_CROSSING_VISION_LINE_FOLLOWING
  -> ARRIVE_ALREADY_CLEANED_BLOCK
  -> DEADHEAD_TO_NEXT_BRIDGE_OR_TRANSIT_POSE
```

视觉重新初始化必须使用目标 block 上实际存在的小板内部格：

```text
block_id + cell_row + cell_col + inner_row + inner_col
```

并且该位置通过当前机器人 footprint 安全校验。

---

## 十一、最终输出

### 11.1 waypoint `type` 取值

`type` 表示该 waypoint 的执行语义。当前支持：

| `type` | 含义 | `brush_state` | 位置表达 |
|--------|------|---------------|----------|
| `clean` | 清扫点，刷盘开启，沿 lane 覆盖有板区域 | `on` | 必须有离散格子索引 |
| `deadhead` | 非清扫空驶点，用于换 lane、连接 segment、桥后接入、transit 中转等 | `off` | 必须有离散格子索引 |
| `turn_in_place` | 原地转向点，`heading` 为转向前朝向，`rotation_angle_deg` 为旋转角 | `off` | 必须有离散格子索引 |
| `approach_bridge` | 上桥前接近点，用于在 block 内对齐桥入口方向 / 视觉巡线初始方向 | `off` | 必须有离散格子索引 |
| `bridge_crossing` | 过桥执行语义，切换到视觉巡线，由视觉模块跟随桥面实际中心线 | `off` | 关联 `bridge_id` |
| `reinit_vision` | 跨桥后视觉重新初始化或定位确认点 | `off` | 必须有离散格子索引 |

说明：

- `clean` 才会打开清扫机构；
- `deadhead` 只表示空驶，不表示可以忽略安全检查；
- `turn_in_place` 所在中心格也必须是 `FREE`，且转向前后的 footprint 安全；
- `bridge_crossing` 表示过桥巡线状态，可能没有 `cell_row/cell_col/inner_row/inner_col`，但应有 `bridge_id`；过桥方向由前后 waypoint 的 block 关系推导；
- 后续执行状态机若将 `approach_bridge`、`reinit_vision` 作为状态而不是 waypoint type，调试输出中仍保留同等语义。

### 11.2 输出示例

最终规划输出包含 ordered waypoints：

```yaml
- type: clean
  brush_state: on
  block_id: 1
  cell_row: 0
  cell_col: 3
  inner_row: 1
  inner_col: 4
  heading: block_u_positive
  rotation_angle_deg: 0

- type: turn_in_place
  brush_state: off
  block_id: 1
  cell_row: 0
  cell_col: 3
  inner_row: 1
  inner_col: 4
  heading: block_u_positive
  rotation_angle_deg: -90

- type: deadhead
  brush_state: off
  block_id: 1
  cell_row: 1
  cell_col: 3
  inner_row: 1
  inner_col: 4
  heading: block_v_positive
  rotation_angle_deg: 0
```

并包含调试信息：

```yaml
planning_debug:
  selected_sweep_axis: block_u
  selected_lane_stride: 2
  lane_offset: 1
  coverage_complete: false
  total_cost: 1234.5
  score_breakdown:
    - selected: axis=block_u stride=2 offset=1 cost=1234.500 complete=true covered=120/120 ratio=1.000 ...
    - best axis block_u: axis=block_u ...
    - best axis block_v: axis=block_v ...
  unusable_bridges: []
  unreachable_segments: []
  invalid_reasons:
    - global cover block order: 1,2,4,5
    - global visit order: 1(clean),2(clean),4(clean),5(clean)
    - global bridge order: 1,2,4
```

这些输出用于执行、RViz 显示和调试，不写入静态地图。

### 11.3 RViz 显示和人工检查

RViz 显示应区分不同执行语义：

- `clean` 显示清扫轨迹；
- `deadhead / turn_in_place / approach_bridge / reinit_vision` 显示非清扫转移轨迹；
- `bridge_crossing` 单独显示桥面通过语义；
- waypoint 序号按最终全局执行顺序连续。

检查全局规划质量时，应以最终全局 waypoints 为准，而不是只看单 block 规划结果。单 block 候选覆盖正常，并不代表全局搜索最终选中的候选也覆盖正常。

如果某个 block 在 RViz 中只显示一条 clean 线，检查：

```text
该 block 在最终全局 waypoints 中的 clean waypoint 数量
该 block clean waypoint 覆盖的 inner row / inner col 范围
clean waypoint 是否大多相邻连续
是否被全局搜索选中了低覆盖但接桥方便的候选
```

同时也要检查转弯和空驶数量。如果出现大量“一点一个 lane”或点与点之间频繁换 lane，说明候选排序过度偏向连接碎片，应提高蛇形连续性和少转弯的优先级。
