#!/usr/bin/env python3
"""Curses-based TUI for interactive map_planner service testing.

Four-pane layout:
- Left:       block list (select target/allowed blocks)
- Mid-left:   cell overview for current block (aggregated free/blocked)
- Mid-right:  inner grid for current cell (per-heading status)
- Right:      planning parameters (editable)
- Bottom:     status bar + result summary

Usage:
    ros2 run map_planner test_plan_path.py tui
"""

from __future__ import annotations

import curses
import re
import sys
from collections import Counter
from typing import Optional

import rclpy
from rcl_interfaces.srv import GetParameters, ListParameters
from rclpy.executors import SingleThreadedExecutor
from rclpy.node import Node

from map_planner.srv import GetCenterPoses, GetMap, PlanCoveragePath, PlanTransitPath
from plan_tui_model import (
    BlockSummary,
    HEADING_NAMES,
    MapSnapshot,
    PlannerConfig,
    PoseSelection,
    PRESETS,
    STATUS_NAMES,
    WAYPOINT_TYPE_NAMES,
)


# ── parameter field definitions ──────────────────────────────────────────
# Each entry: (key, label, type, choices_or_step)
# type: "float", "int", "bool", "choice"
# – common params shown in both coverage and transit mode
#   (key, label, type, extra)
_COMMON_PARAMS = [
    ("robot_length_cm",       "robot_len",    "float", 1.0),
    ("front_roller_width_cm", "front_roll",   "float", 1.0),
    ("rear_roller_width_cm",  "rear_roll",    "float", 1.0),
    ("robot_width_cm",        "robot_w",      "float", 1.0),
    ("safety_margin_cm",      "safety_margin", "float", 1.0),
    ("planning_search_effort", "search_effort", "choice",
     ["fast", "balanced", "quality", "exhaustive"]),
    ("debug_score_breakdown", "debug_score",  "bool", None),
]
# – coverage-only params
_COVERAGE_PARAMS = [
    ("cleaning_width_cm",     "clean_width",  "float", 1.0),
    ("overlap_ratio",         "overlap",      "float", 0.01),
    ("enable_tail_coverage",  "tail_coverage", "bool", None),
]
# – transit-only params
_TRANSIT_PARAMS = [
    ("require_goal_heading",  "goal_heading", "bool", None),
]

# param keys that trigger center-pose reload when changed
_POSE_SENSITIVE_KEYS = {
    "robot_length_cm", "front_roller_width_cm", "rear_roller_width_cm",
    "robot_width_cm", "safety_margin_cm",
}

_ALL_PARAM_KEYS = (
    [f[0] for f in _COMMON_PARAMS] +
    [f[0] for f in _COVERAGE_PARAMS] +
    [f[0] for f in _TRANSIT_PARAMS]
)


def _make_config_from_fields(fields: dict) -> PlannerConfig:
    c = PlannerConfig()
    for key, *_ in _COMMON_PARAMS + _COVERAGE_PARAMS:
        if key in fields:
            setattr(c, key, fields[key])
    return c


def _fields_from_config(config: PlannerConfig) -> dict:
    result = {}
    for key, *_ in _COMMON_PARAMS + _COVERAGE_PARAMS + _TRANSIT_PARAMS:
        try:
            result[key] = getattr(config, key)
        except AttributeError:
            result[key] = False  # require_goal_heading default
    return result


def _param_value_extract(pv) -> object:
    """Extract Python value from a rcl_interfaces.msg.ParameterValue."""
    if pv.type == 1:   # BOOL
        return pv.bool_value
    elif pv.type == 2:  # INTEGER
        return pv.integer_value
    elif pv.type == 3:  # DOUBLE
        return pv.double_value
    elif pv.type == 4:  # STRING
        return pv.string_value
    return None


class PlannerTUI:
    def __init__(self, stdscr):
        self.stdscr = stdscr
        self.mode = "coverage"          # "coverage" or "transit"
        self._param_fields = _COMMON_PARAMS + _COVERAGE_PARAMS
        self.preset_name = "real-map-debug"

        self.map_snapshot = MapSnapshot()
        self.pose_poses = []

        self.blocks: dict[int, BlockSummary] = {}
        self.block_ids: list[int] = []
        self.selected_blocks: set[int] = set()

        self.current_block_idx = 0
        self.block_scroll = 0     # first visible block row
        self.current_cell_row = 0
        self.current_cell_col = 0
        self.current_inner_row = 0
        self.current_inner_col = 0
        self.current_heading = 2    # BlockVPositive

        self.start_pose = PoseSelection()
        self.goal_pose = PoseSelection()

        self.active_pane = "block"   # block / cell / inner / params
        self.result_text = ["", "No result yet. Press Enter to plan."]
        self.status_msg = ""

        # ── params editing state ──
        self._field_values: dict[str, object] = {}   # key -> current value
        self._param_cursor = 0
        self._param_scroll = 0   # first visible param row

        # inline edit state
        self._editing = False       # True while typing a value
        self._edit_type = ""        # "float", "int", "choice"
        self._edit_buffer = ""
        self._edit_choices: list[str] = []
        self._edit_choice_idx = 0

        self.show_score = False
        self.show_waypoints = False

        self.node: Optional[Node] = None
        self.executor = None
        self.running = True

        curses.curs_set(0)
        self.stdscr.nodelay(False)
        self.stdscr.keypad(True)

    # ── config / param helpers ───────────────────────────────────────────

    @property
    def config(self) -> PlannerConfig:
        return _make_config_from_fields(self._field_values)

    @property
    def _active_params(self) -> list:
        """Return the parameter field list for the current mode."""
        if self.mode == "coverage":
            return _COMMON_PARAMS + _COVERAGE_PARAMS
        else:
            return _COMMON_PARAMS + _TRANSIT_PARAMS

    def _switch_mode(self, mode: str):
        if self.mode == mode:
            return
        self.mode = mode
        self._param_fields = self._active_params
        self._param_cursor = 0
        self._param_scroll = 0
        self._editing = False
        self._edit_buffer = ""

    # ── ROS helpers ──────────────────────────────────────────────────────

    def init_ros(self):
        rclpy.init(args=[])
        self.node = Node("plan_tui")
        self.executor = SingleThreadedExecutor()
        self.executor.add_node(self.node)
        self.get_map_client = self.node.create_client(
            GetMap, "/map_planner/get_map")
        self.get_poses_client = self.node.create_client(
            GetCenterPoses, "/map_planner/get_center_poses")
        self.coverage_client = self.node.create_client(
            PlanCoveragePath, "/map_planner/plan_coverage_path")
        self.transit_client = self.node.create_client(
            PlanTransitPath, "/map_planner/plan_transit_path")

    def _call_sync(self, client, request, timeout=30.0):
        if not client.wait_for_service(timeout_sec=min(timeout, 5.0)):
            raise RuntimeError(f"service {client.srv_name} not available")
        future = client.call_async(request)
        self.executor.spin_until_future_complete(future, timeout_sec=timeout)
        if not future.done():
            raise TimeoutError("service call timed out")
        return future.result()

    # ── fetch node defaults ──────────────────────────────────────────────

    def _fetch_node_params(self) -> dict:
        """Try to read current parameter values from the /map_planner node.

        Returns a dict of {config_key: value} on success, empty dict on failure.
        """
        try:
            params_client = self.node.create_client(
                GetParameters, "/map_planner/get_parameters")

            if not params_client.wait_for_service(timeout_sec=3.0):
                return {}

            # Values returned in same order as requested names
            get_req = GetParameters.Request()
            get_req.names = list(_ALL_PARAM_KEYS)
            get_fut = params_client.call_async(get_req)
            self.executor.spin_until_future_complete(get_fut, timeout_sec=3.0)
            if not get_fut.done():
                return {}

            resp = get_fut.result()
            result: dict = {}
            for name, pv in zip(_ALL_PARAM_KEYS, resp.values):
                val = _param_value_extract(pv)
                if val is not None:
                    result[name] = val  # name == config key (f[0])
            return result
        except Exception:
            return {}

    # ── data loading ─────────────────────────────────────────────────────

    def reload_data(self):
        self.status_msg = "Loading map..."
        self._draw_all()
        try:
            get_map = GetMap.Request()
            map_resp = self._call_sync(self.get_map_client, get_map)
            if not map_resp.success:
                self.status_msg = f"get_map failed: {map_resp.message}"
                return

            m = map_resp.map
            self.map_snapshot.map_id = m.map_id
            self.map_snapshot.map_version = m.version
            self.map_snapshot.inner_rows = m.inner_rows
            self.map_snapshot.inner_cols = m.inner_cols

            self.blocks.clear()
            self.block_ids.clear()
            bridge_block_counts: Counter[int] = Counter()
            for bridge in m.bridges:
                try:
                    bridge_block_counts[int(bridge.source)] += 1
                except (ValueError, TypeError):
                    pass
                for ep in bridge.endpoints:
                    if ep.block_id:
                        bridge_block_counts[ep.block_id] += 1

            for block in m.blocks:
                if not block.cleanable:
                    continue
                bs = BlockSummary(
                    block_id=block.block_id,
                    selected=block.block_id in self.selected_blocks,
                    rows=block.rows,
                    cols=block.cols,
                    cleanable=block.cleanable,
                    bridge_count=bridge_block_counts.get(block.block_id, 0),
                )
                self.blocks[block.block_id] = bs
                self.map_snapshot.blocks[block.block_id] = bs
            self.block_ids = sorted(self.blocks.keys())

            self._reload_center_poses(m.map_id, m.version)
        except Exception as e:
            self.status_msg = f"Error: {e}"

    def _reload_center_poses(self, map_id: int, map_version: int):
        self.status_msg = "Loading center poses..."
        self._draw_all()
        cfg = self.config
        poses_req = GetCenterPoses.Request()
        poses_req.map_id = map_id
        poses_req.map_version = map_version
        poses_req.block_ids = []
        poses_req.headings = [0, 1, 2, 3]
        poses_req.free_only = True
        poses_req.robot_length_cm = cfg.robot_length_cm
        poses_req.front_roller_width_cm = cfg.front_roller_width_cm
        poses_req.rear_roller_width_cm = cfg.rear_roller_width_cm
        poses_req.robot_width_cm = cfg.robot_width_cm
        poses_req.safety_margin_cm = cfg.safety_margin_cm

        poses_resp = self._call_sync(self.get_poses_client, poses_req, timeout=120.0)
        if not poses_resp.success:
            self.status_msg = f"get_center_poses failed: {poses_resp.message}"
            return

        self.pose_poses = poses_resp.poses
        self.map_snapshot.build_index(poses_resp.poses)
        self.status_msg = (
            f"Loaded map {map_id} v{map_version}: "
            f"{len(self.block_ids)} blocks, {len(poses_resp.poses)} free poses"
        )

    # ── planning ─────────────────────────────────────────────────────────

    def send_plan(self):
        if not self.node:
            return
        if not self.start_pose.is_free():
            self.result_text = [
                "", "ERROR: start pose is not Free. Select a Free pose first."]
            return
        if self.mode == "transit" and not self.goal_pose.is_free():
            self.result_text = [
                "", "ERROR: goal pose is not Free. Select a Free pose first."]
            return

        self.status_msg = "Planning... (may take a while)"
        self._draw_all()

        try:
            if self.mode == "coverage":
                req = PlanCoveragePath.Request()
                self._fill_coverage(req)
                resp = self._call_sync(self.coverage_client, req, timeout=360.0)
            else:
                req = PlanTransitPath.Request()
                self._fill_transit(req)
                resp = self._call_sync(self.transit_client, req, timeout=180.0)
            self._format_result(resp)
        except Exception as e:
            self.result_text = ["", f"ERROR: {e}"]
        self.status_msg = ""

    def _fill_common(self, req):
        m = self.map_snapshot
        req.map_id = m.map_id
        req.map_version = m.map_version
        s = self.start_pose
        req.start_block_id = s.block_id
        req.start_cell_row = s.cell_row
        req.start_cell_col = s.cell_col
        req.start_inner_row = s.inner_row
        req.start_inner_col = s.inner_col
        req.start_heading = s.heading
        c = self.config
        req.robot_length_cm = c.robot_length_cm
        req.front_roller_width_cm = c.front_roller_width_cm
        req.rear_roller_width_cm = c.rear_roller_width_cm
        req.robot_width_cm = c.robot_width_cm
        req.safety_margin_cm = c.safety_margin_cm
        req.planning_search_effort = c.planning_search_effort
        req.debug_score_breakdown = c.debug_score_breakdown

    def _fill_coverage(self, req):
        self._fill_common(req)
        req.target_block_ids = sorted(self.selected_blocks)
        req.global_plan = True
        c = self.config
        req.cleaning_width_cm = c.cleaning_width_cm
        req.overlap_ratio = c.overlap_ratio
        req.enable_tail_coverage = c.enable_tail_coverage

    def _fill_transit(self, req):
        self._fill_common(req)
        g = self.goal_pose
        req.goal_block_id = g.block_id
        req.goal_cell_row = g.cell_row
        req.goal_cell_col = g.cell_col
        req.goal_inner_row = g.inner_row
        req.goal_inner_col = g.inner_col
        req.goal_heading = g.heading
        req.require_goal_heading = bool(
            self._field_values.get("require_goal_heading", False))
        req.allowed_block_ids = sorted(self.selected_blocks)

    def _format_result(self, resp):
        lines = [""]
        lines.append(f"success: {resp.success}")
        lines.append(f"message: {resp.message}")
        if not resp.success:
            self.result_text = lines
            return

        path = resp.path
        debug = path.debug
        lines.append(f"map: id={path.map_id} v={path.map_version}")
        lines.append(f"waypoints: {len(path.waypoints)}")
        lines.append(f"coverage_complete: {debug.coverage_complete}")
        lines.append(f"total_cost: {debug.total_cost:.3f}")

        type_counts: Counter[str] = Counter()
        block_clean_counts: Counter[int] = Counter()
        first_clean = None
        for i, wp in enumerate(path.waypoints):
            type_counts[WAYPOINT_TYPE_NAMES.get(wp.type, str(wp.type))] += 1
            if wp.type == 0:
                block_clean_counts[wp.block_id] += 1
                if first_clean is None:
                    first_clean = i

        if type_counts:
            parts = [f"{n}={c}" for n, c in sorted(type_counts.items())]
            lines.append(f"types: {', '.join(parts)}")
        if first_clean is not None:
            lines.append(f"first_clean_index: {first_clean}")
        if block_clean_counts:
            parts = [f"B{bid}={cnt}" for bid, cnt in sorted(block_clean_counts.items())]
            lines.append(f"clean_by_block: {', '.join(parts)}")

        for fn in ("unusable_bridges", "unreachable_segments", "invalid_reasons"):
            items = getattr(debug, fn, [])
            if items:
                lines.append(f"{fn}:")
                for item in items[:20]:
                    lines.append(f"  - {item}")
        if self.show_score and debug.score_breakdown:
            lines.append("score_breakdown:")
            for item in debug.score_breakdown[:20]:
                lines.append(f"  - {item}")
        if self.show_waypoints:
            lines.append(f"--- waypoints ({len(path.waypoints)}) ---")
            wp_limit = 200
            for i, wp in enumerate(path.waypoints[:wp_limit]):
                hdg = {0: "U+", 1: "U-", 2: "V+", 3: "V-"}.get(wp.heading, "?")
                tp = WAYPOINT_TYPE_NAMES.get(wp.type, str(wp.type))
                lines.append(
                    f"#{i} {tp} B{wp.block_id}({wp.cell_row},{wp.cell_col})"
                    f"({wp.inner_row},{wp.inner_col}) {hdg}")
            if len(path.waypoints) > wp_limit:
                lines.append(f"... ({len(path.waypoints) - wp_limit} more)")
        self.result_text = lines

    # ══════════════════════════════════════════════════════════════════════
    # Drawing ──────────────────────────────────────────────────────────────
    # ══════════════════════════════════════════════════════════════════════

    def _draw_all(self):
        self.stdscr.erase()
        max_y, max_x = self.stdscr.getmaxyx()

        # layout: Blocks | Cells | Inner | Params
        # params needs ~20 cols for "  label: value"
        params_w = max(20, min(26, max_x // 5))
        inner_w = max(10, min(14, max_x // 8))
        block_w = max(14, min(22, (max_x - params_w - inner_w) // 4))
        cell_w = max_x - block_w - inner_w - params_w
        if cell_w < 16:
            # shrink params/inner to keep cells usable
            params_w = max(16, params_w - 4)
            inner_w = max(8, inner_w - 2)
            block_w = max(12, (max_x - params_w - inner_w) // 3)
            cell_w = max_x - block_w - inner_w - params_w

        pane_h = max_y - 6

        self._draw_blocks(0, 0, block_w, pane_h)
        self._draw_cells(0, block_w, cell_w, pane_h)
        self._draw_inner(0, block_w + cell_w, inner_w, pane_h)
        self._draw_params(0, block_w + cell_w + inner_w, params_w, pane_h)

        if self._editing:
            self._draw_edit_bar(max_y - 2, 0, max_x)
        else:
            self._draw_status_bar(max_y - 6, 0, max_x)
            self._draw_result(max_y - 4, 0, max_x, 4)
        self._draw_help(max_y - 1, max_x)
        self.stdscr.refresh()

    # ── blocks pane ──

    def _draw_blocks(self, y, x, w, h):
        win = self.stdscr.derwin(h, w, y, x) if h > 0 and w > 0 else None
        if not win:
            return
        active = self.active_pane == "block"
        title = "[ Blocks ]" if active else "Blocks"
        title_attr = curses.A_REVERSE if active else 0
        win.border(0)
        win.addstr(0, 2, title[:w - 2], title_attr)
        mode_str = f"m:{self.mode[0]}"
        win.addstr(0, w - len(mode_str) - 2, mode_str)

        visible = h - 2          # rows available for block entries
        if visible <= 0:
            return
        # clamp scroll so cursor is visible
        if self.current_block_idx < self.block_scroll:
            self.block_scroll = self.current_block_idx
        elif self.current_block_idx >= self.block_scroll + visible:
            self.block_scroll = self.current_block_idx - visible + 1
        n = len(self.block_ids)
        self.block_scroll = max(0, min(self.block_scroll, max(0, n - visible)))

        for vi in range(visible):
            i = self.block_scroll + vi
            if i >= n:
                break
            bid = self.block_ids[i]
            bs = self.blocks[bid]
            mark = "[x]" if bs.selected else "[ ]"
            if i == self.current_block_idx:
                line = f"> {mark} B{bid:<4} f={bs.free_count}"
            else:
                line = f"  {mark} B{bid:<4} f={bs.free_count}"
            attr = curses.A_REVERSE if i == self.current_block_idx else 0
            win.addstr(1 + vi, 1, line.ljust(w - 2)[:w - 2], attr)

    # ── cells pane ──

    def _draw_cells(self, y, x, w, h):
        win = self.stdscr.derwin(h, w, y, x) if h > 0 and w > 0 else None
        if not win:
            return
        active = self.active_pane == "cell"
        title_attr = curses.A_REVERSE if active else 0
        win.border(0)
        if self.block_ids:
            bid = self.block_ids[self.current_block_idx]
            bs = self.blocks[bid]
            title = f"[ B{bid} cells ]" if active else f"B{bid} cells"
            win.addstr(0, 2, title[:w - 2], title_attr)
            dim_str = f"{bs.rows}x{bs.cols}"
            win.addstr(0, w - len(dim_str) - 2, dim_str)

            cols_per_cell = max(2, (w - 4) // max(1, bs.cols))
            for cr in range(min(bs.rows, h - 2)):
                for cc in range(min(bs.cols, (w - 2) // cols_per_cell)):
                    if (self.start_pose.block_id == bid and
                        self.start_pose.cell_row == cr and
                            self.start_pose.cell_col == cc):
                        ch = "S"
                    elif (self.mode == "transit" and
                          self.goal_pose.block_id == bid and
                          self.goal_pose.cell_row == cr and
                          self.goal_pose.cell_col == cc):
                        ch = "G"
                    elif self.map_snapshot.has_free_at_cell(bid, cr, cc):
                        ch = "·"
                    else:
                        ch = "#"
                    attr = curses.A_REVERSE if (
                        active and cr == self.current_cell_row and
                        cc == self.current_cell_col) else 0
                    cx = 1 + cc * cols_per_cell
                    try:
                        win.addstr(
                            1 + cr, cx,
                            ch.ljust(cols_per_cell)[:w - cx - 1], attr)
                    except curses.error:
                        pass
        else:
            win.addstr(0, 2, "No blocks loaded", 0)

    # ── inner pane ──

    def _draw_inner(self, y, x, w, h):
        win = self.stdscr.derwin(h, w, y, x) if h > 0 and w > 0 else None
        if not win:
            return
        active = self.active_pane == "inner"
        title_attr = curses.A_REVERSE if active else 0
        win.border(0)
        ir = self.map_snapshot.inner_rows
        ic = self.map_snapshot.inner_cols
        title = f"[ Inner {ir}x{ic} ]" if active else f"Inner {ir}x{ic}"
        win.addstr(0, 2, title[:w - 2], title_attr)
        hdg_map = {0: "U+", 1: "U-", 2: "V+", 3: "V-"}
        hdg = hdg_map.get(self.current_heading, "?")
        if w > len(hdg) + 3:
            win.addstr(0, w - len(hdg) - 2, hdg)

        if self.block_ids and ir > 0 and ic > 0:
            bid = self.block_ids[self.current_block_idx]
            cpi = max(2, (w - 4) // max(1, ic))
            for r in range(min(ir, h - 2)):
                for c in range(min(ic, (w - 2) // cpi)):
                    is_free = self.map_snapshot.is_free(
                        bid, self.current_cell_row, self.current_cell_col,
                        r, c, self.current_heading)
                    if (self.start_pose.block_id == bid and
                        self.start_pose.inner_row == r and
                        self.start_pose.inner_col == c and
                            self.start_pose.heading == self.current_heading):
                        ch = "S"
                    elif (self.mode == "transit" and
                          self.goal_pose.block_id == bid and
                          self.goal_pose.inner_row == r and
                          self.goal_pose.inner_col == c and
                          self.goal_pose.heading == self.current_heading):
                        ch = "G"
                    elif is_free:
                        ch = "·"
                    else:
                        ch = "#"
                    attr = curses.A_REVERSE if (
                        active and r == self.current_inner_row and
                        c == self.current_inner_col) else 0
                    cx = 1 + c * cpi
                    try:
                        win.addstr(
                            1 + r, cx,
                            ch.ljust(cpi)[:w - cx - 1], attr)
                    except curses.error:
                        pass

    # ── params pane ──

    def _draw_params(self, y, x, w, h):
        win = self.stdscr.derwin(h, w, y, x) if h > 0 and w > 0 else None
        if not win:
            return
        active = self.active_pane == "params"
        title = "[ Params ]" if active else "Params"
        title_attr = curses.A_REVERSE if active else 0
        win.border(0)
        win.addstr(0, 2, title[:w - 2], title_attr)

        nf = len(self._param_fields)
        if nf == 0:
            try:
                win.addstr(1, 2, "(no params)")
            except curses.error:
                pass
            return
        visible = h - 2          # rows for param entries
        if visible <= 0:
            return
        if self._param_cursor < self._param_scroll:
            self._param_scroll = self._param_cursor
        elif self._param_cursor >= self._param_scroll + visible:
            self._param_scroll = self._param_cursor - visible + 1
        self._param_scroll = max(0, min(self._param_scroll, max(0, nf - visible)))

        for vi in range(visible):
            i = self._param_scroll + vi
            if i >= nf:
                break
            key, label, typ, extra = self._param_fields[i]
            val = self._field_values.get(key, "")
            if isinstance(val, float):
                val_str = f"{val:.1f}"
            elif isinstance(val, bool):
                val_str = "ON" if val else "OFF"
            else:
                val_str = str(val)

            display = f"  {label}: {val_str}"
            if self._editing and i == self._param_cursor:
                display = f"> {label}: {self._edit_buffer}"
            attr = curses.A_REVERSE if (active and i == self._param_cursor) else 0
            try:
                win.addstr(1 + vi, 1, display[:w - 2].ljust(w - 2), attr)
            except curses.error:
                pass

        # preset hint at bottom of pane
        pfx = f"p:{self.preset_name}"
        try:
            win.addstr(h - 1, 1, pfx[:w - 2])
        except curses.error:
            pass

    # ── status / result / help ──

    def _draw_status_bar(self, y, x, w):
        if w <= 0:
            return
        if self.status_msg:
            self.stdscr.addstr(y, 0, self.status_msg[:w - 1], curses.A_REVERSE)

        parts = []
        if self.start_pose.block_id:
            s = self.start_pose
            parts.append(
                f"start: B{s.block_id}({s.cell_row},{s.cell_col})"
                f"({s.inner_row},{s.inner_col}) h={s.heading}")
        else:
            parts.append("start: none")
        if self.mode == "transit":
            if self.goal_pose.block_id:
                g = self.goal_pose
                parts.append(
                    f"goal: B{g.block_id}({g.cell_row},{g.cell_col})"
                    f"({g.inner_row},{g.inner_col}) h={g.heading}")
            else:
                parts.append("goal: none")
        sel = sorted(self.selected_blocks) if self.selected_blocks else "all"
        parts.append(f"sel: {sel}")
        self.stdscr.addstr(y + 1, 0, " | ".join(parts)[:w - 1])

    def _draw_result(self, y, _x, w, h):
        for i in range(h):
            try:
                self.stdscr.addstr(y + i, 0, " " * (w - 1))
            except curses.error:
                pass
        for i, line in enumerate(self.result_text[:h]):
            try:
                self.stdscr.addstr(y + i, 0, line[:w - 1])
            except curses.error:
                pass

    def _draw_help(self, y, max_x):
        txt = (
            "q=quit r=reload c=coverage t=transit Tab=pane Spc=sel s=start g=goal "
            "h=next_hdg 0-3=pick_hdg Enter=plan e=edit d=score w=wpts p=preset"
        )
        self.stdscr.addstr(y, 0, txt[:max_x - 1], curses.A_REVERSE)

    def _draw_edit_bar(self, y, x, w):
        """Draw a dedicated edit bar at the bottom when editing a param."""
        key, label, _typ, _extra = self._param_fields[self._param_cursor]
        if self._edit_type == "choice":
            # show label and all options with current one highlighted
            prompt = f"  {label}: "
            for idx, ch in enumerate(self._edit_choices):
                if idx == self._edit_choice_idx:
                    prompt += f"[{ch}] "
                else:
                    prompt += f" {ch}  "
            self.stdscr.addstr(y, x, prompt[:w - 1], curses.A_REVERSE)
            self.stdscr.addstr(y + 1, x, "  <- -> to pick option, Enter to confirm, Esc to cancel", 0)
        else:
            prompt = f"  {label}: {self._edit_buffer}_"
            self.stdscr.addstr(y, x, prompt[:w - 1], curses.A_REVERSE)
            self.stdscr.addstr(y + 1, x, "  type value, Enter to confirm, Esc to cancel", 0)

    # ══════════════════════════════════════════════════════════════════════
    # Navigation helpers
    # ══════════════════════════════════════════════════════════════════════

    def _current_block(self) -> int:
        if self.block_ids:
            return self.block_ids[self.current_block_idx]
        return 0

    def _current_block_summary(self) -> Optional[BlockSummary]:
        return self.blocks.get(self._current_block())

    def _set_start_from_current(self):
        bid = self._current_block()
        cr, cc = self.current_cell_row, self.current_cell_col
        ir, ic = self.current_inner_row, self.current_inner_col
        h = self.current_heading
        status = self.map_snapshot.get_status(bid, cr, cc, ir, ic, h)
        if status != 0:
            self.status_msg = (
                f"Pose not Free: {STATUS_NAMES.get(status, str(status))}")
            return
        self.start_pose = PoseSelection(bid, cr, cc, ir, ic, h, status)
        self.status_msg = f"start: B{bid}({cr},{cc})({ir},{ic}) h={h}"

    def _set_goal_from_current(self):
        bid = self._current_block()
        cr, cc = self.current_cell_row, self.current_cell_col
        ir, ic = self.current_inner_row, self.current_inner_col
        h = self.current_heading
        status = self.map_snapshot.get_status(bid, cr, cc, ir, ic, h)
        if status != 0:
            self.status_msg = (
                f"Pose not Free: {STATUS_NAMES.get(status, str(status))}")
            return
        self.goal_pose = PoseSelection(bid, cr, cc, ir, ic, h, status)
        self.status_msg = f"goal: B{bid}({cr},{cc})({ir},{ic}) h={h}"

    # ── param editing helpers ──

    def _param_current_key(self) -> str:
        return self._param_fields[self._param_cursor][0]

    def _start_edit(self):
        if not self._field_values:
            return
        key = self._param_current_key()
        val = self._field_values.get(key, "")
        _, _, typ, extra = self._param_fields[self._param_cursor]
        if typ == "bool":
            # toggle immediately
            self._field_values[key] = not val
            self._maybe_reload_poses(key)
            if key == "debug_score_breakdown":
                self.show_score = bool(self._field_values.get(key, False))
            return
        self._editing = True
        self._edit_type = typ
        self._param_cursor_at_start = self._param_cursor  # keep cursor on this param
        if typ == "choice":
            self._edit_choices = list(extra)
            cur = str(val)
            self._edit_choice_idx = self._edit_choices.index(cur) if cur in self._edit_choices else 0
            self._edit_buffer = self._edit_choices[self._edit_choice_idx]
        elif isinstance(val, float):
            self._edit_buffer = ("%.1f" % val) if typ == "float" else str(val)
        else:
            self._edit_buffer = str(val)

    def _commit_edit(self):
        if not self._editing:
            return
        key = self._param_current_key()
        _, _, typ, _extra = self._param_fields[self._param_cursor]
        try:
            if typ == "float":
                val = float(self._edit_buffer)
                if val < 0:
                    raise ValueError
                self._field_values[key] = val
            elif typ == "int":
                val = int(self._edit_buffer)
                if val < 0:
                    raise ValueError
                self._field_values[key] = val
            elif typ == "choice":
                self._field_values[key] = self._edit_buffer
            self._maybe_reload_poses(key)
            if key == "debug_score_breakdown":
                self.show_score = bool(self._field_values.get(key, False))
        except (ValueError, TypeError):
            self.status_msg = "invalid value: " + self._edit_buffer
        self._editing = False
        self._edit_buffer = ""
        self._edit_type = ""

    def _cancel_edit(self):
        self._editing = False
        self._edit_buffer = ""
        self._edit_type = ""

    def _maybe_reload_poses(self, changed_key: str):
        """If a pose-sensitive param changed, reload center poses."""
        if changed_key in _POSE_SENSITIVE_KEYS:
            m = self.map_snapshot
            if m.map_id:
                self._reload_center_poses(m.map_id, m.map_version)

    # ══════════════════════════════════════════════════════════════════════
    # Main loop
    # ══════════════════════════════════════════════════════════════════════

    def run(self):
        self.init_ros()

        # show params immediately with preset values
        preset = PRESETS.get(self.preset_name, PRESETS["real-map-debug"])
        self._field_values = _fields_from_config(preset)
        self._draw_all()

        # try to fetch node params (may take a few seconds)
        node_params = self._fetch_node_params()
        if node_params:
            self._field_values.update(node_params)
            self.status_msg = "Params loaded from /map_planner node"
        else:
            self.status_msg = f"Using preset: {self.preset_name}"

        self.reload_data()
        while self.running:
            self._draw_all()
            key = self.stdscr.getch()
            self._handle_key(key)

    def _handle_key(self, key):
        # ── global keys (work regardless of editing state) ──
        if self._editing:
            self._handle_edit_key(key)
            return

        bs = self._current_block_summary()
        ir = self.map_snapshot.inner_rows
        ic = self.map_snapshot.inner_cols

        if key == ord("q"):
            self.running = False
        elif key == ord("r"):
            self.reload_data()
        elif key == ord("c"):
            self._switch_mode("coverage")
        elif key == ord("t"):
            self._switch_mode("transit")
        elif key == ord("d"):
            self.show_score = not self.show_score
            self._field_values["debug_score_breakdown"] = self.show_score
            self.status_msg = "debug_score: " + ("ON" if self.show_score else "OFF")
        elif key == ord("w"):
            self.show_waypoints = not self.show_waypoints
            self.status_msg = "waypoint_list: " + ("ON" if self.show_waypoints else "OFF")
        elif key == ord("p"):
            names = list(PRESETS.keys())
            idx = names.index(self.preset_name) if self.preset_name in names else 0
            self.preset_name = names[(idx + 1) % len(names)]
            preset = PRESETS[self.preset_name]
            self._field_values = _fields_from_config(preset)
            self.status_msg = f"preset: {self.preset_name}"
            m = self.map_snapshot
            if m.map_id:
                self._reload_center_poses(m.map_id, m.map_version)

        elif key == 9:  # Tab
            panes = ["block", "cell", "inner", "params"]
            idx = panes.index(self.active_pane)
            self.active_pane = panes[(idx + 1) % len(panes)]

        elif key == ord(" "):
            if self.active_pane == "block" and self.block_ids:
                bid = self.block_ids[self.current_block_idx]
                if bid in self.selected_blocks:
                    self.selected_blocks.discard(bid)
                    self.blocks[bid].selected = False
                else:
                    self.selected_blocks.add(bid)
                    self.blocks[bid].selected = True

        elif key in (ord("h"), ord("0"), ord("1"), ord("2"), ord("3")):
            if key == ord("h"):
                self.current_heading = (self.current_heading + 1) % 4
            else:
                self.current_heading = int(chr(key))
            hdg_names = {0: "U+", 1: "U-", 2: "V+", 3: "V-"}
            self.status_msg = f"heading: {hdg_names.get(self.current_heading, '?')} ({self.current_heading})"

        elif key == ord("s"):
            self._set_start_from_current()
        elif key == ord("g"):
            self._set_goal_from_current()

        elif key == ord("e"):
            if self.active_pane == "params" and self._field_values:
                self._start_edit()
            else:
                self.status_msg = "press Tab to switch to Params pane first"

        elif key in (curses.KEY_ENTER, 10, 13):
            if self.active_pane == "params":
                self._start_edit()
            else:
                self.send_plan()

        elif key == ord("n"):
            self._jump_next_free_cell()
        elif key == ord("N"):
            self._jump_prev_free_cell()

        # ── pane navigation ──
        elif self.active_pane == "block":
            self._nav_block(key)
        elif self.active_pane == "cell" and bs:
            self._nav_cell(key, bs)
        elif self.active_pane == "inner" and ir > 0 and ic > 0:
            self._nav_inner(key, ir, ic)
        elif self.active_pane == "params":
            self._nav_params(key)

    # ── edit mode key handling ──

    def _handle_edit_key(self, key):
        if key == 27:   # Esc
            self._cancel_edit()
        elif self._edit_type == "choice":
            if key == curses.KEY_LEFT and self._edit_choice_idx > 0:
                self._edit_choice_idx -= 1
                self._edit_buffer = self._edit_choices[self._edit_choice_idx]
            elif key == curses.KEY_RIGHT and self._edit_choice_idx < len(self._edit_choices) - 1:
                self._edit_choice_idx += 1
                self._edit_buffer = self._edit_choices[self._edit_choice_idx]
            elif key in (curses.KEY_ENTER, 10, 13):
                self._commit_edit()
        else:
            if key in (curses.KEY_ENTER, 10, 13):
                self._commit_edit()
            elif key in (curses.KEY_BACKSPACE, 127, 8):
                self._edit_buffer = self._edit_buffer[:-1]
            elif 32 <= key <= 126:
                self._edit_buffer += chr(key)

    # ── per-pane navigation ──

    def _nav_block(self, key):
        n = len(self.block_ids)
        if key == curses.KEY_UP and self.current_block_idx > 0:
            self.current_block_idx -= 1
        elif key == curses.KEY_DOWN and self.current_block_idx < n - 1:
            self.current_block_idx += 1
        elif key == curses.KEY_NPAGE:
            self.current_block_idx = min(n - 1, self.current_block_idx + 10)
        elif key == curses.KEY_PPAGE:
            self.current_block_idx = max(0, self.current_block_idx - 10)

    def _nav_cell(self, key, bs):
        if key == curses.KEY_UP:
            self.current_cell_row = max(0, self.current_cell_row - 1)
        elif key == curses.KEY_DOWN:
            self.current_cell_row = min(bs.rows - 1, self.current_cell_row + 1)
        elif key == curses.KEY_LEFT:
            self.current_cell_col = max(0, self.current_cell_col - 1)
        elif key == curses.KEY_RIGHT:
            self.current_cell_col = min(bs.cols - 1, self.current_cell_col + 1)
        elif key == curses.KEY_NPAGE:
            self.current_cell_row = min(bs.rows - 1, self.current_cell_row + 10)
        elif key == curses.KEY_PPAGE:
            self.current_cell_row = max(0, self.current_cell_row - 10)

    def _nav_inner(self, key, ir, ic):
        if key == curses.KEY_UP:
            self.current_inner_row = max(0, self.current_inner_row - 1)
        elif key == curses.KEY_DOWN:
            self.current_inner_row = min(ir - 1, self.current_inner_row + 1)
        elif key == curses.KEY_LEFT:
            self.current_inner_col = max(0, self.current_inner_col - 1)
        elif key == curses.KEY_RIGHT:
            self.current_inner_col = min(ic - 1, self.current_inner_col + 1)

    def _nav_params(self, key):
        n = len(self._param_fields)
        if key == curses.KEY_UP and self._param_cursor > 0:
            self._param_cursor -= 1
        elif key == curses.KEY_DOWN and self._param_cursor < n - 1:
            self._param_cursor += 1
        elif key == curses.KEY_NPAGE:
            self._param_cursor = min(n - 1, self._param_cursor + 8)
        elif key == curses.KEY_PPAGE:
            self._param_cursor = max(0, self._param_cursor - 8)

    # ── jump to next/prev free cell ──

    def _jump_next_free_cell(self):
        bid = self._current_block()
        bs = self._current_block_summary()
        if not bs:
            return
        cr, cc = self.current_cell_row, self.current_cell_col
        ir_v, ic_v = self.map_snapshot.inner_rows, self.map_snapshot.inner_cols
        h = self.current_heading
        for r in range(cr, bs.rows):
            sc = cc if r == cr else 0
            for c in range(sc, bs.cols):
                si_r = self.current_inner_row if (r == cr and c == sc) else 0
                for inner_r in range(si_r, ir_v):
                    si_c = self.current_inner_col if (r == cr and c == sc and inner_r == si_r) else 0
                    for inner_c in range(si_c, ic_v):
                        if self.map_snapshot.is_free(bid, r, c, inner_r, inner_c, h):
                            self.current_cell_row = r
                            self.current_cell_col = c
                            self.current_inner_row = inner_r
                            self.current_inner_col = inner_c
                            self.status_msg = (
                                f"jumped to B{bid}({r},{c})({inner_r},{inner_c})")
                            return
        self.status_msg = "No more free poses"

    def _jump_prev_free_cell(self):
        bid = self._current_block()
        bs = self._current_block_summary()
        if not bs:
            return
        cr, cc = self.current_cell_row, self.current_cell_col
        ir_v, ic_v = self.map_snapshot.inner_rows, self.map_snapshot.inner_cols
        h = self.current_heading
        for r in range(cr, -1, -1):
            ec = cc if r == cr else bs.cols - 1
            for c in range(ec, -1, -1):
                ei_r = self.current_inner_row if (r == cr and c == ec) else ir_v - 1
                for inner_r in range(ei_r, -1, -1):
                    ei_c = self.current_inner_col if (r == cr and c == ec and inner_r == ei_r) else ic_v - 1
                    for inner_c in range(ei_c, -1, -1):
                        if self.map_snapshot.is_free(bid, r, c, inner_r, inner_c, h):
                            self.current_cell_row = r
                            self.current_cell_col = c
                            self.current_inner_row = inner_r
                            self.current_inner_col = inner_c
                            self.status_msg = (
                                f"jumped to B{bid}({r},{c})({inner_r},{inner_c})")
                            return
        self.status_msg = "No previous free pose"


def run_tui():
    curses.wrapper(lambda stdscr: PlannerTUI(stdscr).run())
