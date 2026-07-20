#!/usr/bin/env python3
"""Add automatically inferred bridge definitions to a map_planner YAML map.

The generator is intentionally spatial-neighbor first: it merges exposed cell
sides into compact block boundary segments, compares only nearby/facing
segments, then expands the best segment pairs into concrete bridge endpoints.
"""

from __future__ import annotations

import argparse
import copy
import math
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Tuple

try:
    import yaml
except ImportError as exc:  # pragma: no cover - exercised by user environment
    print(
        "Missing dependency: PyYAML. Install it with: sudo apt install python3-yaml",
        file=sys.stderr,
    )
    raise SystemExit(2) from exc

Point = Tuple[float, float]


@dataclass(frozen=True)
class CellInfo:
    cell_id: int
    block_id: int
    row: int
    col: int
    polygon: tuple[Point, Point, Point, Point]


@dataclass(frozen=True)
class BlockInfo:
    block_id: int
    rows: int
    cols: int
    grid: tuple[tuple[int, ...], ...]
    cleanable: bool


@dataclass(frozen=True)
class SideRecord:
    block_id: int
    row: int
    col: int
    edge: str
    start: Point
    end: Point
    normal: Point


@dataclass(frozen=True)
class BoundarySegment:
    segment_id: int
    block_id: int
    edge: str
    records: tuple[SideRecord, ...]
    start: Point
    end: Point
    midpoint: Point
    normal: Point
    length: float


@dataclass(frozen=True)
class BridgeEndpointCandidate:
    block_id: int
    cell_row: int
    cell_col: int
    edge: str
    inner_row: int
    inner_col: int
    anchor: Point
    centeredness_penalty: float


@dataclass(frozen=True)
class BridgeCandidate:
    endpoint_a: BridgeEndpointCandidate
    endpoint_b: BridgeEndpointCandidate
    segment_a: BoundarySegment
    segment_b: BoundarySegment
    distance_cm: float
    score: float


@dataclass
class GenerationStats:
    cleanable_blocks: int = 0
    raw_exposed_sides: int = 0
    merged_boundary_segments: int = 0
    spatial_bucket_checks: int = 0
    segment_pairs_after_distance: int = 0
    segment_pairs_after_facing: int = 0
    bridge_candidates: int = 0
    selected_bridges: int = 0
    disconnected_components: list[list[int]] | None = None


@dataclass
class GenerationResult:
    bridges: list[dict[str, Any]]
    stats: GenerationStats


@dataclass(frozen=True)
class GeneratorConfig:
    max_bridge_length_cm: float = 600.0
    spatial_bucket_cm: float | None = None
    max_neighbor_segments: int = 12
    max_bridges_per_block_pair: int = 1
    bridge_width_cm: float = 80.0
    bridge_density: str = "sparse"
    dense_score_ratio: float = 0.0
    balanced_extra_score_ratio: float = 1.15
    precision: int = 3
    allow_partial: bool = False
    facing_threshold: float = 0.35
    max_samples_per_segment: int = 3


# Vector helpers


def add(a: Point, b: Point) -> Point:
    return a[0] + b[0], a[1] + b[1]


def sub(a: Point, b: Point) -> Point:
    return a[0] - b[0], a[1] - b[1]


def mul(a: Point, scalar: float) -> Point:
    return a[0] * scalar, a[1] * scalar


def dot(a: Point, b: Point) -> float:
    return a[0] * b[0] + a[1] * b[1]


def norm(a: Point) -> float:
    return math.hypot(a[0], a[1])


def distance(a: Point, b: Point) -> float:
    return norm(sub(a, b))


def normalize(a: Point) -> Point:
    length = norm(a)
    if length <= 1e-9:
        return 1.0, 0.0
    return a[0] / length, a[1] / length


def midpoint(a: Point, b: Point) -> Point:
    return (a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0


def lerp(a: Point, b: Point, ratio: float) -> Point:
    return a[0] + (b[0] - a[0]) * ratio, a[1] + (b[1] - a[1]) * ratio


def round_float(value: float, precision: int) -> float:
    rounded = round(value, precision)
    if abs(rounded) < 10 ** (-(precision + 1)):
        return 0.0
    return rounded


def round_point(point: Point, precision: int) -> list[float]:
    return [round_float(point[0], precision), round_float(point[1], precision)]


# Map indexing and C++ geometry mirror


def _as_point(value: Any, field_name: str) -> Point:
    if not isinstance(value, (list, tuple)) or len(value) < 2:
        raise ValueError(f"{field_name} must be a 2D point")
    return float(value[0]), float(value[1])


def load_map_yaml(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as stream:
        data = yaml.safe_load(stream)
    if not isinstance(data, dict):
        raise ValueError(f"{path} does not contain a YAML object")
    return data


def build_indexes(map_data: dict[str, Any]) -> tuple[dict[int, BlockInfo], dict[tuple[int, int, int], CellInfo]]:
    blocks_node = map_data.get("blocks")
    cells_node = map_data.get("cells")
    if not isinstance(blocks_node, list):
        raise ValueError("map must contain blocks[]")
    if not isinstance(cells_node, list):
        raise ValueError("map must contain cells[]")

    blocks: dict[int, BlockInfo] = {}
    for block in blocks_node:
        block_id = int(block["block_id"])
        rows = int(block["rows"])
        cols = int(block["cols"])
        grid = block.get("grid")
        if not isinstance(grid, list) or len(grid) != rows:
            raise ValueError(f"block {block_id} grid row count does not match rows")
        grid_rows: list[tuple[int, ...]] = []
        for row_index, row in enumerate(grid):
            if not isinstance(row, list) or len(row) != cols:
                raise ValueError(f"block {block_id} grid row {row_index} does not match cols")
            grid_rows.append(tuple(1 if int(value) != 0 else 0 for value in row))
        blocks[block_id] = BlockInfo(
            block_id=block_id,
            rows=rows,
            cols=cols,
            grid=tuple(grid_rows),
            cleanable=bool(block.get("cleanable", True)),
        )

    cells: dict[tuple[int, int, int], CellInfo] = {}
    for cell in cells_node:
        polygon_node = cell.get("polygon")
        if not isinstance(polygon_node, list) or len(polygon_node) < 4:
            raise ValueError(f"cell {cell.get('cell_id')} polygon must contain at least 4 points")
        block_id = int(cell["block_id"])
        row = int(cell["row"])
        col = int(cell["col"])
        key = (block_id, row, col)
        if block_id not in blocks:
            raise ValueError(f"cell {cell.get('cell_id')} references missing block {block_id}")
        block = blocks[block_id]
        if row < 0 or row >= block.rows or col < 0 or col >= block.cols:
            raise ValueError(f"cell {cell.get('cell_id')} row/col is out of block range")
        if block.grid[row][col] != 1:
            raise ValueError(f"cell {cell.get('cell_id')} references a missing grid slot")
        cells[key] = CellInfo(
            cell_id=int(cell["cell_id"]),
            block_id=block_id,
            row=row,
            col=col,
            polygon=tuple(_as_point(polygon_node[index], "cell polygon") for index in range(4)),
        )

    for block in blocks.values():
        for row in range(block.rows):
            for col in range(block.cols):
                if block.grid[row][col] == 1 and (block.block_id, row, col) not in cells:
                    raise ValueError(
                        f"block {block.block_id} grid slot ({row}, {col}) is present but has no cell"
                    )

    return blocks, cells


def interpolate_cell_point(cell: CellInfo, u_ratio: float, v_ratio: float) -> Point:
    p00, p10, p11, p01 = cell.polygon
    bottom = lerp(p00, p10, u_ratio)
    top = lerp(p01, p11, u_ratio)
    return lerp(bottom, top, v_ratio)


def derive_bridge_endpoint_anchor(
    cell: CellInfo,
    endpoint: BridgeEndpointCandidate | dict[str, int | str],
    inner_rows: int,
    inner_cols: int,
) -> Point:
    inner_row = int(endpoint.inner_row if isinstance(endpoint, BridgeEndpointCandidate) else endpoint["inner_row"])
    inner_col = int(endpoint.inner_col if isinstance(endpoint, BridgeEndpointCandidate) else endpoint["inner_col"])
    edge = str(endpoint.edge if isinstance(endpoint, BridgeEndpointCandidate) else endpoint["edge"])
    if inner_rows <= 0 or inner_cols <= 0:
        raise ValueError("inner_rows and inner_cols must be positive")
    if inner_row < 0 or inner_row >= inner_rows or inner_col < 0 or inner_col >= inner_cols:
        raise ValueError("bridge endpoint inner index is out of range")

    u_ratio = (inner_col + 0.5) / inner_cols
    v_ratio = (inner_row + 0.5) / inner_rows
    if edge == "u_min":
        u_ratio = 0.0
    elif edge == "u_max":
        u_ratio = 1.0
    elif edge == "v_min":
        v_ratio = 0.0
    elif edge == "v_max":
        v_ratio = 1.0
    else:
        raise ValueError(f"unsupported bridge endpoint edge: {edge}")
    return interpolate_cell_point(cell, u_ratio, v_ratio)


def _cell_axes(cell: CellInfo) -> tuple[Point, Point]:
    p00, p10, _p11, p01 = cell.polygon
    return normalize(sub(p10, p00)), normalize(sub(p01, p00))


def _edge_geometry(cell: CellInfo, edge: str) -> tuple[Point, Point, Point]:
    p00, p10, p11, p01 = cell.polygon
    u_axis, v_axis = _cell_axes(cell)
    if edge == "u_min":
        return p00, p01, mul(u_axis, -1.0)
    if edge == "u_max":
        return p10, p11, u_axis
    if edge == "v_min":
        return p00, p10, mul(v_axis, -1.0)
    if edge == "v_max":
        return p01, p11, v_axis
    raise ValueError(f"unsupported edge: {edge}")


# Boundary segment generation


def _is_present(block: BlockInfo, row: int, col: int) -> bool:
    return 0 <= row < block.rows and 0 <= col < block.cols and block.grid[row][col] == 1


def enumerate_exposed_sides(
    blocks: dict[int, BlockInfo],
    cells: dict[tuple[int, int, int], CellInfo],
) -> list[SideRecord]:
    sides: list[SideRecord] = []
    for block in blocks.values():
        if not block.cleanable:
            continue
        for row in range(block.rows):
            for col in range(block.cols):
                if not _is_present(block, row, col):
                    continue
                cell = cells[(block.block_id, row, col)]
                exposed_edges = []
                if not _is_present(block, row, col - 1):
                    exposed_edges.append("u_min")
                if not _is_present(block, row, col + 1):
                    exposed_edges.append("u_max")
                if not _is_present(block, row - 1, col):
                    exposed_edges.append("v_min")
                if not _is_present(block, row + 1, col):
                    exposed_edges.append("v_max")
                for edge in exposed_edges:
                    start, end, normal = _edge_geometry(cell, edge)
                    sides.append(SideRecord(block.block_id, row, col, edge, start, end, normal))
    return sides


def merge_boundary_segments(sides: Iterable[SideRecord]) -> list[BoundarySegment]:
    by_group: dict[tuple[int, str, int], list[SideRecord]] = defaultdict(list)
    for side in sides:
        line_index = side.col if side.edge in ("u_min", "u_max") else side.row
        by_group[(side.block_id, side.edge, line_index)].append(side)

    segments: list[BoundarySegment] = []
    next_id = 0
    for (_block_id, edge, _line_index), records in sorted(by_group.items()):
        if edge in ("u_min", "u_max"):
            records.sort(key=lambda item: item.row)
            def contiguous(prev, cur): return cur.row == prev.row + 1
        else:
            records.sort(key=lambda item: item.col)
            def contiguous(prev, cur): return cur.col == prev.col + 1

        run: list[SideRecord] = []
        for record in records:
            if run and not contiguous(run[-1], record):
                segments.append(_make_segment(next_id, run))
                next_id += 1
                run = []
            run.append(record)
        if run:
            segments.append(_make_segment(next_id, run))
            next_id += 1
    return segments


def _make_segment(segment_id: int, records: list[SideRecord]) -> BoundarySegment:
    start = records[0].start
    end = records[-1].end
    length = distance(start, end)
    if length <= 1e-9:
        start = records[0].start
        end = records[0].end
        length = distance(start, end)
    avg_normal = normalize(
        (
            sum(record.normal[0] for record in records) / len(records),
            sum(record.normal[1] for record in records) / len(records),
        )
    )
    return BoundarySegment(
        segment_id=segment_id,
        block_id=records[0].block_id,
        edge=records[0].edge,
        records=tuple(records),
        start=start,
        end=end,
        midpoint=midpoint(start, end),
        normal=avg_normal,
        length=length,
    )


# Spatial-neighbor candidate generation


def _bucket_key(point: Point, bucket_size: float) -> tuple[int, int]:
    return math.floor(point[0] / bucket_size), math.floor(point[1] / bucket_size)


def _nearby_segments(
    segment: BoundarySegment,
    buckets: dict[tuple[int, int], list[BoundarySegment]],
    bucket_size: float,
) -> list[BoundarySegment]:
    bx, by = _bucket_key(segment.midpoint, bucket_size)
    neighbors: list[BoundarySegment] = []
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            neighbors.extend(buckets.get((bx + dx, by + dy), []))
    return neighbors


def _segment_pair_base_score(a: BoundarySegment, b: BoundarySegment) -> float:
    vector_ab = sub(b.midpoint, a.midpoint)
    dist = norm(vector_ab)
    if dist <= 1e-9:
        return float("inf")
    direction = mul(vector_ab, 1.0 / dist)
    facing_a = max(0.0, dot(a.normal, direction))
    facing_b = max(0.0, dot(b.normal, mul(direction, -1.0)))
    return dist + 300.0 * (2.0 - facing_a - facing_b)


def candidate_segment_pairs(
    segments: list[BoundarySegment],
    config: GeneratorConfig,
    stats: GenerationStats,
) -> list[tuple[BoundarySegment, BoundarySegment]]:
    if not segments:
        return []
    bucket_size = config.spatial_bucket_cm or max(config.max_bridge_length_cm, 1.0)
    buckets: dict[tuple[int, int], list[BoundarySegment]] = defaultdict(list)
    for segment in segments:
        buckets[_bucket_key(segment.midpoint, bucket_size)].append(segment)

    pairs: list[tuple[float, BoundarySegment, BoundarySegment]] = []
    seen: set[tuple[int, int]] = set()
    for segment in segments:
        nearby = _nearby_segments(segment, buckets, bucket_size)
        nearby.sort(key=lambda other: distance(segment.midpoint, other.midpoint))
        checked_for_segment = 0
        for other in nearby:
            if other.segment_id == segment.segment_id or other.block_id == segment.block_id:
                continue
            key = tuple(sorted((segment.segment_id, other.segment_id)))
            if key in seen:
                continue
            seen.add(key)
            checked_for_segment += 1
            stats.spatial_bucket_checks += 1
            if checked_for_segment > config.max_neighbor_segments:
                break

            midpoint_distance = distance(segment.midpoint, other.midpoint)
            slack = 0.5 * (segment.length + other.length)
            if midpoint_distance > config.max_bridge_length_cm + slack:
                continue
            stats.segment_pairs_after_distance += 1

            vector_ab = sub(other.midpoint, segment.midpoint)
            dist = norm(vector_ab)
            if dist <= 1e-9:
                continue
            direction = mul(vector_ab, 1.0 / dist)
            if dot(segment.normal, direction) < config.facing_threshold:
                continue
            if dot(other.normal, mul(direction, -1.0)) < config.facing_threshold:
                continue
            stats.segment_pairs_after_facing += 1
            pairs.append((_segment_pair_base_score(segment, other), segment, other))

    pairs.sort(key=lambda item: item[0])
    return [(a, b) for _score, a, b in pairs]


def _sample_ratios_for_segment(segment: BoundarySegment, config: GeneratorConfig) -> list[float]:
    if config.max_samples_per_segment <= 1 or segment.length < 1e-6:
        return [0.5]
    half_width = max(0.0, config.bridge_width_cm) / 2.0
    edge_inset = min(0.5, max(0.0, half_width / segment.length))
    ratios = [0.5, edge_inset, 1.0 - edge_inset, 1.0 / 3.0, 2.0 / 3.0]
    deduped: list[float] = []
    for ratio in ratios:
        ratio = max(0.0, min(0.999999, ratio))
        if all(abs(ratio - existing) > 1e-6 for existing in deduped):
            deduped.append(ratio)
    return deduped[: config.max_samples_per_segment]


def _point_to_record_distance(point: Point, record: SideRecord) -> float:
    side = sub(record.end, record.start)
    side_len2 = dot(side, side)
    if side_len2 <= 1e-12:
        return distance(point, record.start)
    ratio = max(0.0, min(1.0, dot(sub(point, record.start), side) / side_len2))
    return distance(point, lerp(record.start, record.end, ratio))


def _endpoint_from_sample(
    segment: BoundarySegment,
    sample_ratio: float,
    inner_rows: int,
    inner_cols: int,
    cells: dict[tuple[int, int, int], CellInfo],
) -> BridgeEndpointCandidate:
    sample = lerp(segment.start, segment.end, sample_ratio)
    record = min(segment.records, key=lambda item: _point_to_record_distance(sample, item))
    side = sub(record.end, record.start)
    side_len2 = dot(side, side)
    ratio = 0.5 if side_len2 <= 1e-12 else dot(sub(sample, record.start), side) / side_len2
    ratio = max(0.0, min(0.999999, ratio))

    if record.edge == "u_min":
        inner_row = min(inner_rows - 1, max(0, int(ratio * inner_rows)))
        inner_col = 0
    elif record.edge == "u_max":
        inner_row = min(inner_rows - 1, max(0, int(ratio * inner_rows)))
        inner_col = inner_cols - 1
    elif record.edge == "v_min":
        inner_row = 0
        inner_col = min(inner_cols - 1, max(0, int(ratio * inner_cols)))
    elif record.edge == "v_max":
        inner_row = inner_rows - 1
        inner_col = min(inner_cols - 1, max(0, int(ratio * inner_cols)))
    else:
        raise ValueError(f"unsupported edge: {record.edge}")

    endpoint = {
        "edge": record.edge,
        "inner_row": inner_row,
        "inner_col": inner_col,
    }
    anchor = derive_bridge_endpoint_anchor(
        cells[(record.block_id, record.row, record.col)], endpoint, inner_rows, inner_cols
    )
    centeredness_penalty = abs(ratio - 0.5)
    return BridgeEndpointCandidate(
        block_id=record.block_id,
        cell_row=record.row,
        cell_col=record.col,
        edge=record.edge,
        inner_row=inner_row,
        inner_col=inner_col,
        anchor=anchor,
        centeredness_penalty=centeredness_penalty,
    )


def _inward_depth(block: BlockInfo, endpoint: BridgeEndpointCandidate) -> int:
    depth = 0
    row = endpoint.cell_row
    col = endpoint.cell_col
    if endpoint.edge == "u_min":
        for c in range(col, block.cols):
            if not _is_present(block, row, c):
                break
            depth += 1
    elif endpoint.edge == "u_max":
        for c in range(col, -1, -1):
            if not _is_present(block, row, c):
                break
            depth += 1
    elif endpoint.edge == "v_min":
        for r in range(row, block.rows):
            if not _is_present(block, r, col):
                break
            depth += 1
    elif endpoint.edge == "v_max":
        for r in range(row, -1, -1):
            if not _is_present(block, r, col):
                break
            depth += 1
    return depth


def _corner_penalty(block: BlockInfo, endpoint: BridgeEndpointCandidate) -> float:
    if endpoint.edge in ("u_min", "u_max"):
        if block.rows <= 1:
            return 0.0
        return min(endpoint.cell_row, block.rows - 1 - endpoint.cell_row) / max(1, block.rows - 1)
    if block.cols <= 1:
        return 0.0
    return min(endpoint.cell_col, block.cols - 1 - endpoint.cell_col) / max(1, block.cols - 1)


def _score_bridge_candidate(
    endpoint_a: BridgeEndpointCandidate,
    endpoint_b: BridgeEndpointCandidate,
    segment_a: BoundarySegment,
    segment_b: BoundarySegment,
    blocks: dict[int, BlockInfo],
) -> tuple[float, float]:
    vector_ab = sub(endpoint_b.anchor, endpoint_a.anchor)
    dist = norm(vector_ab)
    if dist <= 1e-9:
        return float("inf"), dist
    direction = mul(vector_ab, 1.0 / dist)
    facing_a = max(0.0, dot(segment_a.normal, direction))
    facing_b = max(0.0, dot(segment_b.normal, mul(direction, -1.0)))
    alignment_penalty = 2.0 - facing_a - facing_b
    center_penalty = endpoint_a.centeredness_penalty + endpoint_b.centeredness_penalty
    corner_penalty = _corner_penalty(blocks[endpoint_a.block_id], endpoint_a) + _corner_penalty(
        blocks[endpoint_b.block_id], endpoint_b
    )
    staging_penalty = 0.0
    if _inward_depth(blocks[endpoint_a.block_id], endpoint_a) <= 1:
        staging_penalty += 1.0
    if _inward_depth(blocks[endpoint_b.block_id], endpoint_b) <= 1:
        staging_penalty += 1.0
    score = (
        dist
        + 300.0 * alignment_penalty
        + 20.0 * center_penalty
        + 180.0 * corner_penalty
        + 120.0 * staging_penalty
    )
    return score, dist


def make_bridge_candidates(
    segment_pairs: list[tuple[BoundarySegment, BoundarySegment]],
    blocks: dict[int, BlockInfo],
    cells: dict[tuple[int, int, int], CellInfo],
    inner_rows: int,
    inner_cols: int,
    config: GeneratorConfig,
) -> list[BridgeCandidate]:
    candidates: list[BridgeCandidate] = []
    for segment_a, segment_b in segment_pairs:
        ratios_a = _sample_ratios_for_segment(segment_a, config)
        ratios_b = _sample_ratios_for_segment(segment_b, config)
        for ratio_a in ratios_a:
            endpoint_a = _endpoint_from_sample(segment_a, ratio_a, inner_rows, inner_cols, cells)
            for ratio_b in ratios_b:
                endpoint_b = _endpoint_from_sample(segment_b, ratio_b, inner_rows, inner_cols, cells)
                score, dist = _score_bridge_candidate(endpoint_a, endpoint_b, segment_a, segment_b, blocks)
                if dist <= 1e-9 or dist > config.max_bridge_length_cm:
                    continue
                half_width = max(0.0, config.bridge_width_cm) / 2.0
                if half_width > 0.0:
                    record_a = _record_for_endpoint(segment_a, endpoint_a)
                    record_b = _record_for_endpoint(segment_b, endpoint_b)
                    tangent_a = normalize(sub(record_a.end, record_a.start))
                    tangent_b = normalize(sub(record_b.end, record_b.start))
                    if _available_half_width(endpoint_a.anchor, record_a, tangent_a) + 1e-6 < half_width:
                        continue
                    if _available_half_width(endpoint_b.anchor, record_b, tangent_b) + 1e-6 < half_width:
                        continue
                candidates.append(
                    BridgeCandidate(
                        endpoint_a=endpoint_a,
                        endpoint_b=endpoint_b,
                        segment_a=segment_a,
                        segment_b=segment_b,
                        distance_cm=dist,
                        score=score,
                    )
                )
    candidates.sort(key=lambda item: item.score)
    return candidates


# Graph selection and YAML bridge emission


class UnionFind:
    def __init__(self, items: Iterable[int]) -> None:
        self.parent = {item: item for item in items}

    def find(self, item: int) -> int:
        parent = self.parent[item]
        if parent != item:
            self.parent[item] = self.find(parent)
        return self.parent[item]

    def union(self, a: int, b: int) -> bool:
        root_a = self.find(a)
        root_b = self.find(b)
        if root_a == root_b:
            return False
        self.parent[root_b] = root_a
        return True

    def components(self) -> list[list[int]]:
        grouped: dict[int, list[int]] = defaultdict(list)
        for item in sorted(self.parent):
            grouped[self.find(item)].append(item)
        return list(grouped.values())


def _candidate_key(candidate: BridgeCandidate) -> tuple[Any, ...]:
    return (
        candidate.endpoint_a.block_id,
        candidate.endpoint_a.cell_row,
        candidate.endpoint_a.cell_col,
        candidate.endpoint_a.edge,
        candidate.endpoint_a.inner_row,
        candidate.endpoint_a.inner_col,
        candidate.endpoint_b.block_id,
        candidate.endpoint_b.cell_row,
        candidate.endpoint_b.cell_col,
        candidate.endpoint_b.edge,
        candidate.endpoint_b.inner_row,
        candidate.endpoint_b.inner_col,
    )


def _filter_candidates_per_pair(
    candidates: list[BridgeCandidate], max_per_pair: int
) -> list[BridgeCandidate]:
    pair_counts: dict[tuple[int, int], int] = defaultdict(int)
    filtered: list[BridgeCandidate] = []
    for candidate in candidates:
        pair_key = tuple(sorted((candidate.endpoint_a.block_id, candidate.endpoint_b.block_id)))
        if pair_counts[pair_key] >= max_per_pair:
            continue
        pair_counts[pair_key] += 1
        filtered.append(candidate)
    return filtered


def select_bridge_candidates(
    candidates: list[BridgeCandidate],
    cleanable_block_ids: list[int],
    config: GeneratorConfig,
) -> tuple[list[BridgeCandidate], list[list[int]]]:
    filtered = _filter_candidates_per_pair(candidates, config.max_bridges_per_block_pair)

    uf = UnionFind(cleanable_block_ids)
    selected: list[BridgeCandidate] = []
    selected_keys: set[tuple[Any, ...]] = set()
    for candidate in filtered:
        if uf.union(candidate.endpoint_a.block_id, candidate.endpoint_b.block_id):
            selected.append(candidate)
            selected_keys.add(_candidate_key(candidate))
            if len(selected) >= max(0, len(cleanable_block_ids) - 1):
                break

    components = uf.components()
    if config.bridge_density == "sparse" or not selected:
        return selected, components

    selected_worst_score = max(candidate.score for candidate in selected)
    extra_score_limit = (
        selected_worst_score * config.balanced_extra_score_ratio
        if config.bridge_density == "balanced"
        else (
            float("inf")
            if config.dense_score_ratio <= 0.0
            else selected_worst_score * config.dense_score_ratio
        )
    )
    for candidate in filtered:
        key = _candidate_key(candidate)
        if key in selected_keys:
            continue
        if config.bridge_density == "balanced" and candidate.score > extra_score_limit:
            continue
        if config.bridge_density == "dense" and candidate.score > extra_score_limit:
            continue
        selected.append(candidate)
        selected_keys.add(key)
    selected.sort(key=lambda candidate: candidate.score)
    return selected, components


def _endpoint_to_yaml(endpoint: BridgeEndpointCandidate) -> dict[str, Any]:
    return {
        "block_id": endpoint.block_id,
        "cell_row": endpoint.cell_row,
        "cell_col": endpoint.cell_col,
        "edge": endpoint.edge,
        "inner_row": endpoint.inner_row,
        "inner_col": endpoint.inner_col,
    }


def _record_for_endpoint(
    segment: BoundarySegment, endpoint: BridgeEndpointCandidate
) -> SideRecord:
    for record in segment.records:
        if record.row == endpoint.cell_row and record.col == endpoint.cell_col:
            return record
    return min(
        segment.records,
        key=lambda record: abs(record.row - endpoint.cell_row) + abs(record.col - endpoint.cell_col),
    )


def _available_half_width(anchor: Point, record: SideRecord, tangent: Point) -> float:
    return min(
        abs(dot(sub(anchor, record.start), tangent)),
        abs(dot(sub(record.end, anchor), tangent)),
    )


def _bridge_polygon(candidate: BridgeCandidate, width_cm: float, precision: int) -> list[list[float]]:
    record_a = _record_for_endpoint(candidate.segment_a, candidate.endpoint_a)
    record_b = _record_for_endpoint(candidate.segment_b, candidate.endpoint_b)
    tangent_a = normalize(sub(record_a.end, record_a.start))
    tangent_b = normalize(sub(record_b.end, record_b.start))
    if dot(tangent_a, tangent_b) < 0.0:
        tangent_b = mul(tangent_b, -1.0)

    anchor_a = candidate.endpoint_a.anchor
    anchor_b = candidate.endpoint_b.anchor
    half_width = max(0.0, width_cm) / 2.0
    offset_a = mul(tangent_a, half_width)
    offset_b = mul(tangent_b, half_width)
    return [
        round_point(add(anchor_a, offset_a), precision),
        round_point(add(anchor_b, offset_b), precision),
        round_point(sub(anchor_b, offset_b), precision),
        round_point(sub(anchor_a, offset_a), precision),
    ]


def candidates_to_bridges(
    selected: list[BridgeCandidate],
    first_bridge_id: int,
    config: GeneratorConfig,
) -> list[dict[str, Any]]:
    bridges: list[dict[str, Any]] = []
    for index, candidate in enumerate(selected):
        bridge_id = first_bridge_id + index
        anchor_a = candidate.endpoint_a.anchor
        anchor_b = candidate.endpoint_b.anchor
        bridges.append(
            {
                "bridge_id": bridge_id,
                "source": "auto",
                "endpoints": [
                    _endpoint_to_yaml(candidate.endpoint_a),
                    _endpoint_to_yaml(candidate.endpoint_b),
                ],
                "centerline": [
                    round_point(anchor_a, config.precision),
                    round_point(anchor_b, config.precision),
                ],
                "polygon": _bridge_polygon(candidate, config.bridge_width_cm, config.precision),
            }
        )
    return bridges


def generate_bridges(map_data: dict[str, Any], config: GeneratorConfig) -> GenerationResult:
    cell_model = map_data.get("cell_model")
    if not isinstance(cell_model, dict):
        raise ValueError("map must contain cell_model")
    inner_rows = int(cell_model["inner_rows"])
    inner_cols = int(cell_model["inner_cols"])
    if inner_rows <= 0 or inner_cols <= 0:
        raise ValueError("cell_model inner_rows/inner_cols must be positive")

    blocks, cells = build_indexes(map_data)
    cleanable_block_ids = sorted(block.block_id for block in blocks.values() if block.cleanable)
    stats = GenerationStats(cleanable_blocks=len(cleanable_block_ids))
    if len(cleanable_block_ids) <= 1:
        stats.disconnected_components = [cleanable_block_ids] if cleanable_block_ids else []
        return GenerationResult([], stats)

    sides = enumerate_exposed_sides(blocks, cells)
    stats.raw_exposed_sides = len(sides)
    segments = merge_boundary_segments(sides)
    stats.merged_boundary_segments = len(segments)
    segment_pairs = candidate_segment_pairs(segments, config, stats)
    candidates = make_bridge_candidates(segment_pairs, blocks, cells, inner_rows, inner_cols, config)
    stats.bridge_candidates = len(candidates)
    selected, components = select_bridge_candidates(candidates, cleanable_block_ids, config)
    stats.selected_bridges = len(selected)
    stats.disconnected_components = components
    if len(components) > 1 and not config.allow_partial:
        raise RuntimeError(
            "auto bridge candidates cannot connect all cleanable blocks; "
            f"components={components}. Increase --max-bridge-length-cm or use --allow-partial."
        )
    return GenerationResult(candidates_to_bridges(selected, 1, config), stats)


def apply_auto_bridges(
    map_data: dict[str, Any],
    config: GeneratorConfig,
    overwrite_existing: bool = False,
) -> GenerationResult:
    existing = map_data.get("bridges")
    if existing not in (None, []) and not overwrite_existing:
        raise RuntimeError("map already has non-empty bridges; pass --overwrite-existing to replace them")
    result = generate_bridges(map_data, config)
    map_data["bridges"] = result.bridges
    return result


# CLI


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate source:auto bridges for a map_planner YAML map using spatial-neighbor boundary segments."
    )
    parser.add_argument("input_yaml", type=Path, help="Input map YAML")
    parser.add_argument("output_yaml", type=Path, help="Output map YAML")
    parser.add_argument("--overwrite-existing", action="store_true", help="Replace an existing non-empty bridges list")
    parser.add_argument("--max-bridge-length-cm", type=float, default=600.0, help="Maximum generated bridge length")
    parser.add_argument(
        "--spatial-bucket-cm",
        type=float,
        default=None,
        help="Spatial hash bucket size; defaults to max bridge length",
    )
    parser.add_argument(
        "--max-neighbor-segments",
        type=int,
        default=12,
        help="Maximum nearby boundary segments checked per segment",
    )
    parser.add_argument(
        "--max-bridges-per-block-pair",
        type=int,
        default=1,
        help="Maximum candidate bridges kept per block pair before graph selection",
    )
    parser.add_argument("--bridge-width-cm", type=float, default=80.0, help="Fixed generated bridge polygon width")
    parser.add_argument(
        "--bridge-density",
        choices=("sparse", "balanced", "dense"),
        default="sparse",
        help="Bridge count strategy: sparse=minimal connectivity, balanced=some redundant nearby bridges, dense=connect most nearby good pairs",
    )
    parser.add_argument(
        "--dense-score-ratio",
        type=float,
        default=0.0,
        help="Dense mode keeps extra candidates up to this ratio of the worst selected connectivity bridge score; 0 keeps all nearby valid candidates",
    )
    parser.add_argument(
        "--balanced-extra-score-ratio",
        type=float,
        default=1.15,
        help="Balanced mode keeps extra candidates up to this ratio of the worst selected connectivity bridge score",
    )
    parser.add_argument("--precision", type=int, default=3, help="Coordinate precision in output YAML")
    parser.add_argument("--allow-partial", action="store_true",
                        help="Write bridges even if not all blocks can be connected")
    parser.add_argument("--dry-run", action="store_true", help="Print generation summary without writing output YAML")
    parser.add_argument("--verbose", action="store_true", help="Print detailed generation statistics")
    return parser.parse_args()


def print_summary(result: GenerationResult) -> None:
    stats = result.stats
    print(f"cleanable blocks: {stats.cleanable_blocks}")
    print(f"raw exposed sides: {stats.raw_exposed_sides}")
    print(f"merged boundary segments: {stats.merged_boundary_segments}")
    print(f"spatial bucket checks: {stats.spatial_bucket_checks}")
    print(f"segment pairs after distance filter: {stats.segment_pairs_after_distance}")
    print(f"segment pairs after facing filter: {stats.segment_pairs_after_facing}")
    print(f"bridge candidates: {stats.bridge_candidates}")
    print(f"selected bridges: {stats.selected_bridges}")
    if stats.disconnected_components and len(stats.disconnected_components) > 1:
        print(f"disconnected components: {stats.disconnected_components}")


def main() -> int:
    args = parse_args()
    config = GeneratorConfig(
        max_bridge_length_cm=args.max_bridge_length_cm,
        spatial_bucket_cm=args.spatial_bucket_cm,
        max_neighbor_segments=args.max_neighbor_segments,
        max_bridges_per_block_pair=args.max_bridges_per_block_pair,
        bridge_width_cm=args.bridge_width_cm,
        bridge_density=args.bridge_density,
        dense_score_ratio=args.dense_score_ratio,
        balanced_extra_score_ratio=args.balanced_extra_score_ratio,
        precision=args.precision,
        allow_partial=args.allow_partial,
    )

    try:
        original = load_map_yaml(args.input_yaml)
        output_map = copy.deepcopy(original)
        result = apply_auto_bridges(output_map, config, args.overwrite_existing)
    except Exception as exc:
        print(f"auto_bridge_yaml: {exc}", file=sys.stderr)
        return 1

    if args.verbose or args.dry_run:
        print_summary(result)
    if args.dry_run:
        return 0

    with args.output_yaml.open("w", encoding="utf-8") as stream:
        yaml.safe_dump(output_map, stream, allow_unicode=True, sort_keys=False)
    if args.verbose:
        print(f"wrote: {args.output_yaml}")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
