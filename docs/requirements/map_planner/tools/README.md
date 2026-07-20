# map_planner 工具

## 规划 service 测试工具 (`test_plan_path.py`)

统一入口，支持三种模式：CLI coverage、CLI transit、交互式 TUI。

依赖：`plan_tui_model.py`（数据模型、preset、多级索引）、`plan_tui.py`（curses TUI 界面）。

### 依赖的 service

| Service | 用途 |
|---------|------|
| `/map_planner/get_map` | 获取地图 meta 信息和 block/cell 结构 |
| `/map_planner/get_center_poses` | 查询中心格可通行状态（按 block/cell/heading 过滤） |
| `/map_planner/plan_coverage_path` | 清扫覆盖规划 |
| `/map_planner/plan_transit_path` | 点到点空驶规划 |

`/map_planner/get_center_poses` 是新增的只读查询 service（`GetCenterPoses.srv` + `CenterPoseStatus.msg`），TUI 用它拉取真实 `CenterGrid` 可通行判定，避免 Python 侧自己算。

### 参数 preset

CLI 和 TUI 都支持 `--preset` 选择预设参数。预设覆盖默认值，显式 CLI 参数优先。

| preset | 说明 |
|--------|------|
| `node-default` | 对齐 `MapServerNode` 代码默认值（`robot_length=120 safety=10 clean_width=55 overlap=0.2`） |
| `real-map-debug` | 真实地图调试常用参数（`f/r_roller=20 clean_width=80 overlap=0.1 debug_score=true`），TUI 默认 |
| `launch-default` | 对齐 `map_planner.launch.py` launch 参数（`robot_w=60 safety=0 clean_width=80 overlap=0.1 effort=quality`） |

### CLI — coverage 清扫覆盖

```bash
ros2 run map_planner test_plan_path.py coverage [选项]

# 示例：全图
ros2 run map_planner test_plan_path.py coverage \
  --preset real-map-debug \
  --start-block-id 8 --start-cell-row 2 --start-cell-col 4 \
  --start-inner-row 6 --start-inner-col 2 --start-heading 2 \
  --timeout 240

# 示例：指定 block
ros2 run map_planner test_plan_path.py coverage \
  --preset real-map-debug \
  --start-block-id 8 --start-cell-row 2 --start-cell-col 4 \
  --start-inner-row 6 --start-inner-col 2 --start-heading 2 \
  --blocks 1,10 --global-plan

# 边界补扫
ros2 run map_planner test_plan_path.py coverage \
  --preset real-map-debug \
  --start-block-id 8 --start-cell-row 2 --start-cell-col 4 \
  --start-inner-row 6 --start-inner-col 2 --start-heading 2 \
  --blocks ""
```

### CLI — transit 点到点空驶

```bash
ros2 run map_planner test_plan_path.py transit [选项]

# 示例
ros2 run map_planner test_plan_path.py transit \
  --preset real-map-debug \
  --start-block-id 8 --start-cell-row 2 --start-cell-col 4 \
  --start-inner-row 6 --start-inner-col 2 --start-heading 2 \
  --goal-block-id 87 --goal-cell-row 2 --goal-cell-col 1 \
  --goal-inner-row 6 --goal-inner-col 2 --goal-heading 2 \
  --require-goal-heading --timeout 180
```

### CLI 常用参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--preset` | `node-default` (CLI) / `real-map-debug` (TUI) | 预设参数集 |
| `--map-id` / `--map-version` | `0` | `0` 表示从 `/map_planner/get_map` 自动读取 |
| `--start-block-id` / `--start-cell-row` / `--start-cell-col` / `--start-inner-row` / `--start-inner-col` / `--start-heading` | block=1 cell=(0,0) inner=(1,1) heading=0 | 固定起点位姿 |
| `--blocks` | 空 | coverage: `target_block_ids`；transit: `allowed_block_ids`。空 = 所有 cleanable block |
| `--global-plan` / `--no-global-plan` | `true` | coverage 是否跨 block 全局规划 |
| `--goal-block-id` … `--goal-heading` | transit 必填 | 终点位姿 |
| `--require-goal-heading` | `false` | transit 是否强制终点 heading |
| `--robot-length` / `--robot-width` | `120.0` / `70.0` | 车体尺寸 cm |
| `--front-roller-width` / `--rear-roller-width` | `0.0` | 前/后滚刷滚桶直径 cm |
| `--safety-margin` | `10.0` | 安全距离 cm |
| `--cleaning-width` | `55.0` | coverage 清扫覆盖宽度 cm |
| `--overlap` | `0.2` | coverage 相邻 lane 重叠比例 |
| `--disable-tail-coverage` | `false` | coverage 禁用边界补扫 |
| `--search-effort` | `balanced` | `fast` / `balanced` / `quality` / `exhaustive` |
| `--debug-score` | `false` | 请求规划器输出评分 timing breakdown |
| `--timeout` | `30.0` | service 超时秒数 |
| `--scores` | `false` | 打印响应中的 `score_breakdown` |
| `--list-waypoints` | `false` | 打印完整 waypoint 列表 |

### TUI — 交互式界面

```bash
ros2 run map_planner test_plan_path.py tui [--preset real-map-debug]
```

启动后自动从 `/map_planner` 节点拉取地图和中心格数据，进入四栏 curses 界面：

```text
┌ Blocks ───┬── B8 cells ────┬── Inner 12x6 ──┬── Params ────┐
│ > [x] B8  │  · · # · · ·   │  · · · · · ·   │robot_len: 120.0│
│   [ ] B10 │  · · · · · ·   │  · · · S > >   │front_roll: 20.0│
│   ...     │  · # @ · · ·   │  · · @ · · ·   │ ...            │
├───────────┴────────────────┴────────────────┴──────────────┤
│ start: B8(2,4)(6,2) h=2 | goal: none | sel: [1,10]        │
│ result summary ...                                         │
│ q=quit r=reload ...                                        │
└────────────────────────────────────────────────────────────┘
```

#### 三层选点

- **Block 列表**（最左）— 显示 block ID、Free pose 数、选中状态
- **Cell 概览**（中左）— 当前 block 的外层 cell 矩阵，`.` 可通行、`#` 无可用点、`S`/`G` 起点/终点
- **Inner Grid**（中右）— 当前 cell 的小格子 + 当前 heading 状态，精确选点

#### 参数编辑

切换到 Params 栏（Tab），`e` 或 `Enter` 进入编辑：

| 参数类型 | 编辑方式 |
|---------|---------|
| float/int | 底部编辑栏输入数值，Enter 确认，Esc 取消 |
| choice（search_effort） | `←` `→` 在 `fast`/`balanced`/`quality`/`exhaustive` 间切换 |
| bool | 直接翻转，无需进入编辑 |

参数区分模式：coverage 含 `clean_width`/`overlap`/`tail_coverage`，transit 含 `goal_heading`。修改车体尺寸参数会自动重新拉取 center poses。

#### 完整快捷键

| 键 | 功能 |
|----|------|
| `q` | 退出 |
| `r` | 重新加载地图和 center poses |
| `c` / `t` | 切换 coverage / transit 模式 |
| `Tab` | 切换焦点 pane（block → cell → inner → params） |
| `↑↓←→` | 当前 pane 内移动 |
| `PgUp` / `PgDn` | 快速翻页 |
| `Space` | 选中/取消当前 block |
| `s` | 设当前 inner pose 为起点（非 Free 会拒绝） |
| `g` | 设当前 inner pose 为终点（仅 transit） |
| `h` | 循环切换 heading（0→1→2→3→0） |
| `0` `1` `2` `3` | 直接选择 heading |
| `Enter` | 发 service（在 Params pane 时进入参数编辑） |
| `e` | 编辑当前参数（需在 Params pane） |
| `d` | 切换 score_breakdown 显示（联动 `debug_score` 参数） |
| `w` | 切换 waypoint 列表显示 |
| `p` | 循环切换参数 preset |
| `n` / `N` | 跳到下一个/上一个 Free pose |

---

## 自动架桥 YAML 后处理工具 (`auto_bridge_yaml.py`)

给没有桥的 `map_planner` YAML 自动补充 `bridges[]`。不是全量遍历所有端口对，而是先把 cleanable block 的外边界合并为边界段，再通过空间 bucket 只比较附近且朝向相对的边界段，最后选择少量桥连接 cleanable block 图。

基本流程：

```bash
# 先 dry-run 查看候选数量、连通性和是否发生空间剪枝
python3 tools/auto_bridge_yaml.py input_no_bridges.yaml output_with_bridges.yaml \
  --dry-run --verbose

# 确认合理后写出 YAML
python3 tools/auto_bridge_yaml.py input_no_bridges.yaml output_with_bridges.yaml \
  --verbose
```

常用参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--max-bridge-length-cm` | `600.0` | 允许生成的最大桥长 |
| `--spatial-bucket-cm` | 同最大桥长 | 空间索引 bucket 大小 |
| `--max-neighbor-segments` | `12` | 每个边界段最多比较的附近边界段数量 |
| `--max-bridges-per-block-pair` | `1` | 每对 block 进入连通图选择的候选桥数量 |
| `--bridge-width-cm` | `80.0` | 输出桥的固定宽度 |
| `--bridge-density` | `sparse` | `sparse` / `balanced` / `dense` |
| `--balanced-extra-score-ratio` | `1.15` | balanced 额外桥评分阈值 |
| `--dense-score-ratio` | `0.0` | dense 额外桥评分阈值 |
| `--precision` | `3` | 输出坐标小数位数 |
| `--allow-partial` | `false` | 无法连通所有 block 时仍写出已有桥 |
| `--overwrite-existing` | `false` | 替换已有非空 `bridges[]` |
| `--dry-run` | `false` | 只打印统计，不写 YAML |
| `--verbose` | `false` | 打印候选生成和连通性统计 |

---

## DXF/DWG 光伏地图转换工具 (`dxf_to_pv_map.py`)

将 CAD 施工图中的光伏板几何自动转换为 `map_planner` 可加载的标准 YAML 地图。

支持直接输入 DWG 或 DXF 文件。DWG 文件会自动调用 ODA FileConverter 转为 DXF 后再处理。核心流程：

1. 从 DXF 图层中提取光伏板闭合多段线
2. 自动检测面板长/短边尺寸（或通过 `--panel-short` / `--panel-long` 指定，单位由 `--unit-scale` 决定）
3. 按面板朝向聚类（`--angle-tolerance-deg`），确定 block 的 U/V 轴方向
4. 将邻近且共线的面板分组为 block，补齐 cell 网格
5. 生成标准 YAML（含 block、cell、frame、origin）

```bash
# 基本用法
python3 tools/dxf_to_pv_map.py input.dxf output.yaml

# DWG 文件（需安装 ODA FileConverter）
python3 tools/dxf_to_pv_map.py input.dwg output.yaml

# 手动指定面板尺寸 + dry-run
python3 tools/dxf_to_pv_map.py input.dxf output.yaml \
  --panel-short 2200 --panel-long 11000 \
  --dry-run --verbose
```

常用参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `input_dxf` | 必填 | 输入 DXF 或 DWG 文件路径 |
| `output_yaml` | 必填 | 输出 YAML 文件路径 |
| `--layer` | 自动检测 | 指定 DXF 图层名，省略时自动检测含闭合多段线的图层 |
| `--map-id` | `1` | 输出地图 ID |
| `--version` | `1` | 输出地图版本号 |
| `--inner-rows` | `3` | cell 内部行数 |
| `--inner-cols` | `6` | cell 内部列数 |
| `--panel-short` | 自动检测 | 面板短边尺寸，单位由 `--unit-scale` 决定 |
| `--panel-long` | 自动检测 | 面板长边尺寸，单位由 `--unit-scale` 决定 |
| `--size-tolerance` | `0.08` | 面板尺寸相对容差 |
| `--unit-scale` | `mm` | DXF 坐标单位：`mm` / `cm` / `m`。输出始终为 cm |
| `--origin` | `axis` | 原点策略：`min` / `cad` / `manual` / `axis`（自动检测轴线交点） |
| `--origin-x` / `--origin-y` | `0.0` | manual 模式下的原点坐标 cm |
| `--latitude` / `--longitude` | `0.0` | 地图原点 GPS 坐标 |
| `--yaw` | `0.0` | 地图坐标系偏航角 |
| `--u-axis` | `long` | block U 轴用面板长边 `long` 或短边 `short` |
| `--angle-tolerance-deg` | `5.0` | 面板朝向聚类容差（度） |
| `--max-adjacent-gap-cm` | `20.0` | 同列相邻面板最大间隙 cm |
| `--same-line-tolerance-cm` | `10.0` | 面板共线判定容差 cm |
| `--min-block-cells` | `1` | 丢弃 cell 数少于该值的 block |
| `--precision` | `3` | YAML 浮点精度 |
| `--dry-run` | `false` | 只打印统计，不写文件 |
| `--verbose` | `false` | 打印详细统计 |
