#!/usr/bin/env python3
"""Convenience client for testing map_planner mission-facing planning services.

CLI usage:
    ros2 run map_planner test_plan_path.py coverage ...
    ros2 run map_planner test_plan_path.py transit ...
    ros2 run map_planner test_plan_path.py tui
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from typing import Iterable

import rclpy
from rclpy.node import Node

from map_planner.srv import GetMap, PlanCoveragePath, PlanTransitPath

# Shared model (also used by plan_tui.py)
from plan_tui_model import (
    HEADING_NAMES,
    PlannerConfig,
    PRESETS,
    WAYPOINT_TYPE_NAMES,
)


def parse_block_ids(value: str) -> list[int]:
    if not value:
        return []
    return [int(item.strip()) for item in value.split(",") if item.strip()]


def _apply_preset(args: argparse.Namespace) -> None:
    """Apply a named preset to args *before* explicit CLI overrides."""
    preset_name = getattr(args, "preset", None)
    if not preset_name:
        return
    preset = PRESETS.get(preset_name)
    if preset is None:
        return

    def _set_if_arg_default(attr: str, value):
        """Set an attribute only if it still holds the parser default."""
        action = next(
            (a for a in args._actions if a.dest == attr), None
        )
        default = action.default if action else None
        if getattr(args, attr) == default:
            setattr(args, attr, value)

    _set_if_arg_default("robot_length", preset.robot_length_cm)
    _set_if_arg_default("front_roller_width", preset.front_roller_width_cm)
    _set_if_arg_default("rear_roller_width", preset.rear_roller_width_cm)
    _set_if_arg_default("robot_width", preset.robot_width_cm)
    _set_if_arg_default("safety_margin", preset.safety_margin_cm)
    _set_if_arg_default("cleaning_width", preset.cleaning_width_cm)
    _set_if_arg_default("overlap", preset.overlap_ratio)
    _set_if_arg_default("disable_tail_coverage", not preset.enable_tail_coverage)
    _set_if_arg_default("search_effort", preset.planning_search_effort)
    _set_if_arg_default("debug_score", preset.debug_score_breakdown)


def add_common_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--preset",
        choices=list(PRESETS.keys()),
        default="node-default",
        help="parameter preset; explicit CLI args override preset values",
    )
    parser.add_argument(
        "--map-id", type=int, default=0,
        help="map id to request; 0 means query /map_planner/get_map first",
    )
    parser.add_argument(
        "--map-version", type=int, default=0,
        help="map version to request; 0 means query /map_planner/get_map first",
    )
    parser.add_argument("--robot-length", type=float, default=120.0)
    parser.add_argument("--front-roller-width", type=float, default=0.0)
    parser.add_argument("--rear-roller-width", type=float, default=0.0)
    parser.add_argument("--robot-width", type=float, default=70.0)
    parser.add_argument("--safety-margin", type=float, default=10.0)
    parser.add_argument("--cleaning-width", type=float, default=55.0)
    parser.add_argument("--overlap", type=float, default=0.2)
    parser.add_argument(
        "--search-effort",
        choices=["fast", "balanced", "quality", "exhaustive"],
        default="balanced",
    )
    parser.add_argument("--disable-tail-coverage", action="store_true")
    parser.add_argument("--debug-score", action="store_true")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument(
        "--scores", action="store_true", help="print PlanningDebug.score_breakdown"
    )
    parser.add_argument(
        "--list-waypoints", action="store_true", help="print every waypoint"
    )
    parser.add_argument("--start-block-id", type=int, default=1)
    parser.add_argument("--start-cell-row", type=int, default=0)
    parser.add_argument("--start-cell-col", type=int, default=0)
    parser.add_argument("--start-inner-row", type=int, default=1)
    parser.add_argument("--start-inner-col", type=int, default=1)
    parser.add_argument("--start-heading", type=int, default=0, choices=range(4))


def fill_common_request(
    request: object, args: argparse.Namespace, map_id: int, map_version: int
) -> None:
    request.map_id = map_id
    request.map_version = map_version
    request.start_block_id = args.start_block_id
    request.start_cell_row = args.start_cell_row
    request.start_cell_col = args.start_cell_col
    request.start_inner_row = args.start_inner_row
    request.start_inner_col = args.start_inner_col
    request.start_heading = args.start_heading
    request.robot_length_cm = args.robot_length
    request.front_roller_width_cm = args.front_roller_width
    request.rear_roller_width_cm = args.rear_roller_width
    request.robot_width_cm = args.robot_width
    request.safety_margin_cm = args.safety_margin
    request.planning_search_effort = args.search_effort
    request.debug_score_breakdown = args.debug_score


def fill_coverage_request(
    request: PlanCoveragePath.Request,
    args: argparse.Namespace,
    map_id: int,
    map_version: int,
) -> None:
    fill_common_request(request, args, map_id, map_version)
    request.target_block_ids = parse_block_ids(args.blocks)
    request.global_plan = args.global_plan
    request.cleaning_width_cm = args.cleaning_width
    request.overlap_ratio = args.overlap
    request.enable_tail_coverage = not args.disable_tail_coverage


def fill_transit_request(
    request: PlanTransitPath.Request,
    args: argparse.Namespace,
    map_id: int,
    map_version: int,
) -> None:
    fill_common_request(request, args, map_id, map_version)
    request.goal_block_id = args.goal_block_id
    request.goal_cell_row = args.goal_cell_row
    request.goal_cell_col = args.goal_cell_col
    request.goal_inner_row = args.goal_inner_row
    request.goal_inner_col = args.goal_inner_col
    request.goal_heading = args.goal_heading
    request.require_goal_heading = args.require_goal_heading
    request.allowed_block_ids = parse_block_ids(args.blocks)


def waypoint_type_name(value: int) -> str:
    return WAYPOINT_TYPE_NAMES.get(value, str(value))


def heading_name(value: int) -> str:
    return HEADING_NAMES.get(value, str(value))


def print_waypoint(index: int, waypoint: object) -> None:
    print(
        f"  #{index:04d} {waypoint_type_name(waypoint.type):15s} "
        f"block={waypoint.block_id} cell=({waypoint.cell_row},{waypoint.cell_col}) "
        f"inner=({waypoint.inner_row},{waypoint.inner_col}) "
        f"heading={heading_name(waypoint.heading)} brush={waypoint.brush_on} "
        f"bridge={waypoint.bridge_id}"
    )


def print_response(response: object, args: argparse.Namespace) -> int:
    print(f"success: {response.success}")
    print(f"message: {response.message}")
    path = response.path
    debug = path.debug
    print(f"map: id={path.map_id} version={path.map_version}")
    print(f"waypoints: {len(path.waypoints)}")
    print(f"coverage_complete: {debug.coverage_complete}")
    print(f"selected_block_id: {debug.selected_block_id}")
    print(f"total_cost: {debug.total_cost:.3f}")

    type_counts = Counter(
        waypoint_type_name(waypoint.type) for waypoint in path.waypoints
    )
    block_clean_counts = Counter(
        waypoint.block_id for waypoint in path.waypoints if waypoint.type == 0
    )
    first_clean = None
    for i, wp in enumerate(path.waypoints):
        if wp.type == 0:
            first_clean = i
            break

    if type_counts:
        print("waypoint_types:")
        for name, count in sorted(type_counts.items()):
            print(f"  {name}: {count}")
    if block_clean_counts:
        print("clean_waypoints_by_block:")
        for block_id, count in sorted(block_clean_counts.items()):
            print(f"  block {block_id}: {count}")
    if first_clean is not None:
        print(f"first_clean_index: {first_clean}")

    for field_name in ("unusable_bridges", "unreachable_segments", "invalid_reasons"):
        items = getattr(debug, field_name)
        if items:
            print(f"{field_name}:")
            for item in items:
                print(f"  - {item}")
    if args.scores and debug.score_breakdown:
        print("score_breakdown:")
        for item in debug.score_breakdown:
            print(f"  - {item}")
    if args.list_waypoints:
        print("waypoint_list:")
        for index, waypoint in enumerate(path.waypoints):
            print_waypoint(index, waypoint)
    return 0 if response.success else 1


class PlanningServiceClient(Node):
    def __init__(self, service_name: str, srv_type: object) -> None:
        super().__init__("test_plan_path_client")
        self.client = self.create_client(srv_type, service_name)
        self.get_map_client = self.create_client(GetMap, "/map_planner/get_map")

    def wait_for_service_or_raise(self, timeout_sec: float) -> None:
        if not self.client.wait_for_service(timeout_sec=timeout_sec):
            raise RuntimeError(f"service '{self.client.srv_name}' is not available")

    def resolve_map(
        self, map_id: int, map_version: int, timeout_sec: float
    ) -> tuple[int, int]:
        if map_id != 0 and map_version != 0:
            return map_id, map_version
        if not self.get_map_client.wait_for_service(timeout_sec=timeout_sec):
            raise RuntimeError(
                "service '/map_planner/get_map' is not available; "
                "pass --map-id and --map-version explicitly"
            )
        response = self.call_with_client(
            self.get_map_client, GetMap.Request(), timeout_sec
        )
        if not response.success:
            raise RuntimeError(f"get_map failed: {response.message}")
        resolved_id = map_id or response.map.map_id
        resolved_version = map_version or response.map.version
        print(f"using map: id={resolved_id} version={resolved_version}")
        return resolved_id, resolved_version

    def call_with_client(
        self, client: object, request: object, timeout_sec: float
    ) -> object:
        future = client.call_async(request)
        rclpy.spin_until_future_complete(self, future, timeout_sec=timeout_sec)
        if not future.done():
            raise TimeoutError(f"service call timed out after {timeout_sec:.1f}s")
        response = future.result()
        if response is None:
            raise RuntimeError("service call failed without a response")
        return response

    def call(self, request: object, timeout_sec: float) -> object:
        return self.call_with_client(self.client, request, timeout_sec)


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    coverage = subparsers.add_parser(
        "coverage", help="call /map_planner/plan_coverage_path"
    )
    add_common_arguments(coverage)
    coverage.add_argument("--service", default="/map_planner/plan_coverage_path")
    coverage.add_argument(
        "--blocks", default="",
        help="comma-separated target block ids; empty means all cleanable blocks",
    )
    coverage.add_argument(
        "--global-plan", action=argparse.BooleanOptionalAction, default=True,
    )

    transit = subparsers.add_parser(
        "transit", help="call /map_planner/plan_transit_path"
    )
    add_common_arguments(transit)
    transit.add_argument("--service", default="/map_planner/plan_transit_path")
    transit.add_argument(
        "--blocks", default="",
        help="comma-separated allowed block ids; empty means all cleanable blocks",
    )
    transit.add_argument("--goal-block-id", type=int, required=True)
    transit.add_argument("--goal-cell-row", type=int, required=True)
    transit.add_argument("--goal-cell-col", type=int, required=True)
    transit.add_argument("--goal-inner-row", type=int, required=True)
    transit.add_argument("--goal-inner-col", type=int, required=True)
    transit.add_argument("--goal-heading", type=int, default=0, choices=range(4))
    transit.add_argument("--require-goal-heading", action="store_true")

    tui = subparsers.add_parser("tui", help="launch interactive TUI")
    tui.add_argument(
        "--preset",
        choices=list(PRESETS.keys()),
        default="real-map-debug",
        help="parameter preset for TUI (default: real-map-debug)",
    )

    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = make_parser().parse_args(argv)

    if args.command == "tui":
        from plan_tui import run_tui

        run_tui()
        return 0

    # Apply preset for coverage/transit CLI
    _apply_preset(args)

    srv_type = PlanCoveragePath if args.command == "coverage" else PlanTransitPath

    rclpy.init()
    node = PlanningServiceClient(args.service, srv_type)
    try:
        node.wait_for_service_or_raise(args.timeout)
        map_id, map_version = node.resolve_map(
            args.map_id, args.map_version, args.timeout
        )
        request = srv_type.Request()
        if args.command == "coverage":
            fill_coverage_request(request, args, map_id, map_version)
        else:
            fill_transit_request(request, args, map_id, map_version)
        response = node.call(request, args.timeout)
        return print_response(response, args)
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    sys.exit(main())
