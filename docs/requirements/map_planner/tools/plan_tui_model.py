#!/usr/bin/env python3
"""Data model for the map_planner TUI.

Provides PlannerConfig presets, PoseSelection, MapSnapshot with multi-level
indexing over /get_center_poses results, and request/response helpers shared by
the CLI and TUI.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class PlannerConfig:
    robot_length_cm: float = 120.0
    front_roller_width_cm: float = 0.0
    rear_roller_width_cm: float = 0.0
    robot_width_cm: float = 70.0
    safety_margin_cm: float = 10.0
    cleaning_width_cm: float = 55.0
    overlap_ratio: float = 0.2
    enable_tail_coverage: bool = True
    planning_search_effort: str = "balanced"
    debug_score_breakdown: bool = False


PRESETS = {
    "node-default": PlannerConfig(
        robot_length_cm=120.0,
        front_roller_width_cm=0.0,
        rear_roller_width_cm=0.0,
        robot_width_cm=70.0,
        safety_margin_cm=10.0,
        cleaning_width_cm=55.0,
        overlap_ratio=0.2,
        enable_tail_coverage=True,
        planning_search_effort="balanced",
        debug_score_breakdown=False,
    ),
    "real-map-debug": PlannerConfig(
        robot_length_cm=120.0,
        front_roller_width_cm=20.0,
        rear_roller_width_cm=20.0,
        robot_width_cm=70.0,
        safety_margin_cm=10.0,
        cleaning_width_cm=80.0,
        overlap_ratio=0.1,
        enable_tail_coverage=True,
        planning_search_effort="balanced",
        debug_score_breakdown=True,
    ),
    "launch-default": PlannerConfig(
        robot_length_cm=120.0,
        front_roller_width_cm=20.0,
        rear_roller_width_cm=20.0,
        robot_width_cm=60.0,
        safety_margin_cm=0.0,
        cleaning_width_cm=80.0,
        overlap_ratio=0.1,
        enable_tail_coverage=True,
        planning_search_effort="quality",
        debug_score_breakdown=False,
    ),
}


HEADING_NAMES = {
    0: "block_u_positive",
    1: "block_u_negative",
    2: "block_v_positive",
    3: "block_v_negative",
}

WAYPOINT_TYPE_NAMES = {
    0: "clean",
    1: "deadhead",
    2: "turn_in_place",
    3: "approach_bridge",
    4: "bridge_crossing",
    5: "reinit_vision",
}

STATUS_NAMES = {
    0: "Free",
    1: "BlockedMissingCell",
    2: "BlockedBoundary",
    3: "BlockedMissingInflation",
    4: "BlockedBridgeEdge",
    5: "BlockedObstacle",
    255: "Unknown",
}


@dataclass
class PoseSelection:
    block_id: int = 0
    cell_row: int = 0
    cell_col: int = 0
    inner_row: int = 0
    inner_col: int = 0
    heading: int = 0
    status: int = 0  # TraversabilityStatus value

    def is_free(self) -> bool:
        return self.status == 0


@dataclass
class BlockSummary:
    block_id: int = 0
    selected: bool = False
    free_count: int = 0
    cell_count: int = 0  # cells with at least one free pose
    rows: int = 0
    cols: int = 0
    cleanable: bool = True
    bridge_count: int = 0


@dataclass
class MapSnapshot:
    """Cached map + center-pose data indexed for efficient TUI rendering."""

    map_id: int = 0
    map_version: int = 0
    inner_rows: int = 0
    inner_cols: int = 0
    blocks: dict[int, BlockSummary] = field(default_factory=dict)
    # block_id -> set of (cell_row, cell_col) that have at least one free pose
    free_cells: dict[int, set[tuple[int, int]]] = field(default_factory=dict)
    # (block_id, cell_row, cell_col) -> list of free (inner_row, inner_col, heading)
    free_inners: dict[tuple[int, int, int], list[tuple[int, int, int]]] = field(
        default_factory=dict
    )
    # (block_id, cell_row, cell_col, inner_row, inner_col, heading) -> status
    pose_status: dict[tuple[int, int, int, int, int, int], int] = field(
        default_factory=dict
    )

    def build_index(self, poses):
        """Build multi-level index from a list of CenterPoseStatus ROS messages."""
        self.free_cells.clear()
        self.free_inners.clear()
        self.pose_status.clear()

        for p in poses:
            key = (p.block_id, p.cell_row, p.cell_col, p.inner_row, p.inner_col, p.heading)
            self.pose_status[key] = p.status

            if p.status == 0:  # Free
                self.free_cells.setdefault(p.block_id, set()).add(
                    (p.cell_row, p.cell_col)
                )
                cell_key = (p.block_id, p.cell_row, p.cell_col)
                self.free_inners.setdefault(cell_key, []).append(
                    (p.inner_row, p.inner_col, p.heading)
                )

        # Update block summaries
        for bid, cells in self.free_cells.items():
            if bid in self.blocks:
                self.blocks[bid].free_count = sum(
                    len(self.free_inners.get((bid, cr, cc), []))
                    for cr, cc in cells
                )
                self.blocks[bid].cell_count = len(cells)

    def has_free_at_cell(self, block_id: int, cell_row: int, cell_col: int) -> bool:
        return (block_id, cell_row, cell_col) in self.free_inners

    def is_free(self, block_id: int, cell_row: int, cell_col: int,
                inner_row: int, inner_col: int, heading: int) -> bool:
        key = (block_id, cell_row, cell_col, inner_row, inner_col, heading)
        return self.pose_status.get(key, 255) == 0

    def get_status(self, block_id: int, cell_row: int, cell_col: int,
                   inner_row: int, inner_col: int, heading: int) -> int:
        key = (block_id, cell_row, cell_col, inner_row, inner_col, heading)
        return self.pose_status.get(key, 255)
