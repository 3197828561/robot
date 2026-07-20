#!/usr/bin/env python3
"""Convert PV panel geometry in a DWG/DXF file to map_planner YAML."""

from __future__ import annotations

import argparse
import math
import statistics
import shutil
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict, deque
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Tuple

try:
    import ezdxf
except ImportError as exc:  # pragma: no cover - exercised by user environment
    print(
        "Missing dependency: ezdxf. Install it with: python3 -m pip install ezdxf",
        file=sys.stderr,
    )
    raise SystemExit(2) from exc

try:
    import yaml
except ImportError as exc:  # pragma: no cover - exercised by user environment
    print(
        "Missing dependency: PyYAML. Install it with: sudo apt install python3-yaml",
        file=sys.stderr,
    )
    raise SystemExit(2) from exc

# Data model
Point = Tuple[float, float]


@dataclass
class QuadCandidate:
    """A closed 4-point polyline that may be a PV panel, in DXF coordinates (mm)."""
    points_dxf: list[Point]
    short_side: float
    long_side: float
    layer: str
    source_chain: tuple[str, ...]


@dataclass
class Panel:
    """A single PV panel normalised to output centimetres."""
    polygon_cm: list[Point]   # ordered as p00, p10, p11, p01
    center_cm: Point
    u_axis: Point
    v_axis: Point
    u_size_cm: float
    v_size_cm: float
    yaw_deg: float
    block_id: int = 0
    row: int = 0
    col: int = 0
    cell_id: int = 0


@dataclass
class BlockModel:
    """A spatially contiguous group of same-orientation panels."""
    block_id: int
    panels: list[Panel]
    origin: Point
    u_axis: Point
    v_axis: Point
    rows: int
    cols: int
    grid: list[list[int]]    # 1 = panel present, 0 = empty slot
    cell_ids: list[int]


@dataclass
class ExtractionStats:
    """Counters collected during the extract phase, for diagnostics."""
    modelspace_entities: int = 0
    top_layer_entities: int = 0
    expanded_entities: int = 0
    insert_errors: int = 0
    closed_quads: int = 0
    accepted_panels: int = 0
    size_clusters: Counter = None

    def __post_init__(self) -> None:
        if self.size_clusters is None:
            self.size_clusters = Counter()

# DWG → DXF conversion


def _ensure_dxf(input_path: Path, verbose: bool) -> Path:
    """If *input_path* is a DWG file, convert it to DXF via ODAFileConverter.

    Returns the path to a DXF file (either the original or the converted copy).
    The caller should remove the temporary file when done.
    """
    if input_path.suffix.lower() != ".dwg":
        return input_path

    tmp_dir = Path(tempfile.mkdtemp(prefix="dxf_convert_"))
    src_dir = input_path.resolve().parent
    if verbose:
        print(f"Converting DWG to DXF: {input_path.name} ...", file=sys.stderr)

    result = subprocess.run(
        ["xvfb-run", "-a", "ODAFileConverter",
         str(src_dir), str(tmp_dir), "ACAD2018", "DXF", "0", "1"],
        capture_output=True, text=True, timeout=120,
    )
    if result.returncode != 0:
        stderr = result.stderr.strip()
        msg = f"DWG conversion failed: {stderr}" if stderr else f"DWG conversion failed (exit {result.returncode})"
        raise RuntimeError(msg)

    dxf_path = tmp_dir / input_path.with_suffix(".dxf").name
    if not dxf_path.exists():
        raise RuntimeError(f"DWG conversion produced no DXF file at {dxf_path}")

    if verbose:
        print(f"Converted: {dxf_path}", file=sys.stderr)
    return dxf_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert closed PV panel polylines in a DXF file to map_planner YAML."
    )
    parser.add_argument("input_dxf", type=Path, help="Input DXF file")
    parser.add_argument("output_yaml", type=Path, help="Output map_planner YAML file")
    parser.add_argument("--layer", default=None, help="Top-level DXF layer to scan (auto-detected if omitted)")
    parser.add_argument("--map-id", type=int, default=1, help="Output map_id")
    parser.add_argument("--version", type=int, default=1, help="Output map version")
    parser.add_argument("--inner-rows", type=int, default=3, help="Cell inner row count")
    parser.add_argument("--inner-cols", type=int, default=6, help="Cell inner column count")
    parser.add_argument("--panel-short", type=float, default=None,
                        help="Expected panel short side in DXF units (auto-detected if omitted)")
    parser.add_argument("--panel-long", type=float, default=None,
                        help="Expected panel long side in DXF units (auto-detected if omitted)")
    parser.add_argument("--size-tolerance", type=float, default=0.08, help="Relative panel size tolerance")
    parser.add_argument(
        "--unit-scale",
        choices=("mm", "cm", "m"),
        default="mm",
        help="DXF coordinate unit; output is always centimeter",
    )
    parser.add_argument(
        "--origin",
        choices=("min", "cad", "manual", "axis"),
        default="min",
        help="Output map origin strategy (default: min — lower-left corner of detected PV panels)",
    )
    parser.add_argument("--origin-x", type=float, default=0.0, help="Manual origin x in output centimeters")
    parser.add_argument("--origin-y", type=float, default=0.0, help="Manual origin y in output centimeters")
    parser.add_argument("--latitude", type=float, default=0.0, help="GPS latitude (degrees) of the map origin")
    parser.add_argument("--longitude", type=float, default=0.0, help="GPS longitude (degrees) of the map origin")
    parser.add_argument("--yaw", type=float, default=0.0, help="Yaw angle (degrees) of the map frame")
    parser.add_argument(
        "--u-axis",
        choices=("long", "short"),
        default="long",
        help="Use panel long side or short side as block local u axis",
    )
    parser.add_argument("--angle-tolerance-deg", type=float, default=5.0, help="Orientation clustering tolerance")
    parser.add_argument("--max-adjacent-gap-cm", type=float, default=20.0,
                        help="Maximum edge gap between panels inside one block")
    parser.add_argument("--same-line-tolerance-cm", type=float, default=10.0,
                        help="Maximum perpendicular center offset for adjacent panels")
    parser.add_argument("--min-block-cells", type=int, default=1, help="Drop inferred blocks smaller than this")
    parser.add_argument("--precision", type=int, default=3, help="Floating point precision in YAML")
    parser.add_argument("--dry-run", action="store_true", help="Print statistics without writing YAML")
    parser.add_argument("--verbose", action="store_true", help="Print detailed statistics")
    return parser.parse_args()

# Vector helpers (2-D tuples)


def sub(a: Point, b: Point) -> Point:
    return a[0] - b[0], a[1] - b[1]


def add(a: Point, b: Point) -> Point:
    return a[0] + b[0], a[1] + b[1]


def mul(a: Point, s: float) -> Point:
    return a[0] * s, a[1] * s


def dot(a: Point, b: Point) -> float:
    return a[0] * b[0] + a[1] * b[1]


def norm(a: Point) -> float:
    return math.hypot(a[0], a[1])


def normalize(a: Point) -> Point:
    length = norm(a)
    if length <= 1e-9:
        return 1.0, 0.0
    return a[0] / length, a[1] / length


def canonical_axis(axis: Point) -> Point:
    axis = normalize(axis)
    if abs(axis[0]) >= abs(axis[1]):
        return axis if axis[0] >= 0.0 else (-axis[0], -axis[1])
    return axis if axis[1] >= 0.0 else (-axis[0], -axis[1])


def polygon_area(points: list[Point]) -> float:
    area = 0.0
    for p1, p2 in zip(points, points[1:] + points[:1]):
        area += p1[0] * p2[1] - p2[0] * p1[1]
    return abs(area) * 0.5


def center(points: list[Point]) -> Point:
    return sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points)


def angle_distance_deg(a: float, b: float) -> float:
    diff = abs((a - b + 90.0) % 180.0 - 90.0)
    return diff


def round_float(value: float, precision: int) -> float:
    rounded = round(value, precision)
    if abs(rounded) < 10 ** (-(precision + 1)):
        return 0.0
    return rounded


def round_point(point: Point, precision: int) -> list[float]:
    return [round_float(point[0], precision), round_float(point[1], precision)]

# DXF entity expansion


def iter_expanded_entities(entity, stats: ExtractionStats, source_chain: tuple[str, ...] = (), max_depth: int = 20, flip_x: bool = False):
    """Recursively expand INSERT entities, tracking whether x-coordinates should be flipped.

    ezdxf incorrectly negates the x-coordinate when a top-level INSERT has
    xscale=-1.0 combined with rotation=180.0.  We track the accumulated sign
    so that callers can correct the final vertex positions.
    """
    if entity.dxftype() == "INSERT" and max_depth > 0:
        name = entity.dxf.name
        try:
            child_flip_x = flip_x
            try:
                if entity.dxf.xscale < 0:
                    child_flip_x = not child_flip_x
            except Exception:
                pass
            virtual_entities = list(entity.virtual_entities())
        except Exception:
            stats.insert_errors += 1
            return
        for child in virtual_entities:
            yield from iter_expanded_entities(child, stats, source_chain + (name,), max_depth - 1, child_flip_x)
        return
    yield entity, source_chain, flip_x


def _is_closed_polyline(entity) -> bool:
    """Return True if *entity* is a closed LWPOLYLINE or POLYLINE."""
    if entity.dxftype() == "LWPOLYLINE":
        return bool(getattr(entity, "closed", False))
    if entity.dxftype() == "POLYLINE":
        return bool(entity.is_closed)
    return False


def _polyline_points(entity) -> list[Point]:
    """Extract 2-D vertex coordinates from a LWPOLYLINE or POLYLINE."""
    if entity.dxftype() == "LWPOLYLINE":
        return [(float(p[0]), float(p[1])) for p in entity.get_points("xy")]
    if entity.dxftype() == "POLYLINE":
        return [(float(p[0]), float(p[1])) for p in entity.points()]
    return []

# Rectangle validation


def measure_quad(points: list[Point]) -> tuple[float, float, float] | None:
    """Validate a 4-point polygon as an approximate rectangle.

    Returns (short_side, long_side, area) or None if the quad is
    degenerate, self-intersecting, or too skewed.
    """
    if len(points) != 4:
        return None
    edges = [sub(points[(i + 1) % 4], points[i]) for i in range(4)]
    lengths = [norm(edge) for edge in edges]
    if min(lengths) <= 1e-6:
        return None
    area = polygon_area(points)
    if area <= 1e-6:
        return None

    opposite_0 = abs(lengths[0] - lengths[2]) / max(lengths[0], lengths[2])
    opposite_1 = abs(lengths[1] - lengths[3]) / max(lengths[1], lengths[3])
    if opposite_0 > 0.15 or opposite_1 > 0.15:
        return None

    for i in range(4):
        a = normalize(edges[i])
        b = normalize(edges[(i + 1) % 4])
        if abs(dot(a, b)) > 0.2:
            return None

    sorted_lengths = sorted(lengths)
    short_side = statistics.mean(sorted_lengths[:2])
    long_side = statistics.mean(sorted_lengths[2:])
    return short_side, long_side, area

# Layer & panel-size auto-detection


def _detect_layer(input_dxf: Path) -> str | None:
    """Return the modelspace layer that contains the most closed-quad polylines."""
    try:
        doc = ezdxf.readfile(input_dxf)
    except Exception:
        return None
    msp = doc.modelspace()

    layer_quad_counts: Counter[str] = Counter()
    for entity in msp:
        layer_name = entity.dxf.layer
        for expanded, _chain, _flip in iter_expanded_entities(entity, ExtractionStats()):
            if not _is_closed_polyline(expanded):
                continue
            if len(_polyline_points(expanded)) != 4:
                continue
            layer_quad_counts[layer_name] += 1

    if not layer_quad_counts:
        return None
    return layer_quad_counts.most_common(1)[0][0]

# Stage 1 & 2: entity expansion & candidate extraction


def extract_candidates(input_dxf: Path, layer: str | None, args: argparse.Namespace) -> tuple[list[QuadCandidate], ExtractionStats, str]:
    doc = ezdxf.readfile(input_dxf)
    modelspace_entities = list(doc.modelspace())
    stats = ExtractionStats(modelspace_entities=len(modelspace_entities))

    if layer is None:
        layer = _detect_layer(input_dxf)
        if layer is None:
            print("Could not auto-detect a layer with closed polylines. Use --layer.", file=sys.stderr)
            return [], stats, layer
        if args.verbose:
            print(f"Auto-detected layer: {layer}")
    top_layer_entities = [entity for entity in modelspace_entities if entity.dxf.layer == layer]
    stats.top_layer_entities = len(top_layer_entities)

    candidates: list[QuadCandidate] = []
    all_quads: list[tuple[list[Point], float, float, str, tuple]] = []  # (points, short, long, layer, chain)
    for entity in top_layer_entities:
        for expanded, source_chain, flip_x in iter_expanded_entities(entity, stats):
            stats.expanded_entities += 1
            if not _is_closed_polyline(expanded):
                continue
            points = _polyline_points(expanded)
            if flip_x:
                points = [(-p[0], p[1]) for p in points]
            measured = measure_quad(points)
            if measured is None:
                continue
            short_side, long_side, _area = measured
            stats.closed_quads += 1
            size_key = (round(short_side / 100.0) * 100, round(long_side / 100.0) * 100)
            stats.size_clusters[size_key] += 1
            all_quads.append((points, short_side, long_side, expanded.dxf.layer, source_chain))

    # Auto-detect panel size if not specified (in DXF units, per --unit-scale)
    panel_short = args.panel_short
    panel_long = args.panel_long
    if panel_short is None or panel_long is None:
        detected = _detect_panel_size(stats.size_clusters)
        if detected is None:
            print("Could not auto-detect panel size. Use --panel-short and --panel-long.", file=sys.stderr)
            return [], stats, layer
        if panel_short is None:
            panel_short = detected[0]
        if panel_long is None:
            panel_long = detected[1]
        if args.verbose:
            print(f"Auto-detected panel size: {panel_short:.0f} x {panel_long:.0f} DXF units")

    # Filter by size (in DXF units, scale-independent)
    for points, short_side, long_side, quad_layer, source_chain in all_quads:
        short_error = abs(short_side - panel_short) / panel_short
        long_error = abs(long_side - panel_long) / panel_long
        if short_error > args.size_tolerance or long_error > args.size_tolerance:
            continue
        candidates.append(
            QuadCandidate(
                points_dxf=points,
                short_side=short_side,
                long_side=long_side,
                layer=quad_layer,
                source_chain=source_chain,
            )
        )

    stats.accepted_panels = len(candidates)
    return candidates, stats, layer

# Axis-origin auto-detection


def _detect_panel_size(size_clusters: Counter) -> tuple[float, float] | None:
    """Auto-detect the dominant panel size from the size-cluster histogram.

    The panel size is simply the most frequent closed-quad size — photovoltaic
    panels will naturally dominate the count among all closed quadrilaterals.
    """
    if not size_clusters:
        return None
    (short, long), _count = size_clusters.most_common(1)[0]
    return float(short), float(long)


def _detect_axis_origin(input_dxf: Path) -> tuple[float, float] | None:
    """Try to find the X/Y axis cross in the DXF and return its (x, y) in mm.

    Looks for long perpendicular lines that intersect, forming an axis grid.
    Returns the axis-cross centre in DXF millimetres, or None.
    """
    try:
        doc = ezdxf.readfile(input_dxf)
    except Exception:
        return None
    msp = doc.modelspace()

    vert_lines: list[tuple[float, float, float, float]] = []  # (x, y_min, y_max, length)
    horz_lines: list[tuple[float, float, float, float]] = []  # (y, x_min, x_max, length)

    for entity in msp:
        if entity.dxftype() != "LINE":
            continue
        x1, y1 = entity.dxf.start[0], entity.dxf.start[1]
        x2, y2 = entity.dxf.end[0], entity.dxf.end[1]
        dx = abs(x2 - x1)
        dy = abs(y2 - y1)
        length = math.hypot(dx, dy)
        if length < 50000:
            continue
        if dx < dy:
            vert_lines.append(((x1 + x2) / 2.0, min(y1, y2), max(y1, y2), length))
        else:
            horz_lines.append(((y1 + y2) / 2.0, min(x1, x2), max(x1, x2), length))

    if not vert_lines or not horz_lines:
        return None

    # Cluster lines by primary coordinate
    def _cluster(lines: list[tuple[float, float, float, float]]) -> list[list[tuple[float, float, float, float]]]:
        result: list[list[tuple[float, float, float, float]]] = []
        for line in sorted(lines, key=lambda v: v[0]):
            if not result or abs(line[0] - statistics.mean(c[0] for c in result[-1])) > 500:
                result.append([line])
            else:
                result[-1].append(line)
        return result

    vert_clusters = _cluster(vert_lines)
    horz_clusters = _cluster(horz_lines)

    # Find the leftmost-vertical × bottommost-horizontal pair that intersect.
    # This corresponds to the ①/Ⓐ axis origin in Chinese construction drawings.
    best_x = None
    best_y = None
    for vc in vert_clusters:
        vx = statistics.mean(c[0] for c in vc)
        vy_min = min(c[1] for c in vc)
        vy_max = max(c[2] for c in vc)
        for hc in horz_clusters:
            hy = statistics.mean(c[0] for c in hc)
            hx_min = min(c[1] for c in hc)
            hx_max = max(c[2] for c in hc)
            if not (hx_min <= vx <= hx_max and vy_min <= hy <= vy_max):
                continue
            # Pick leftmost x, then bottommost y
            if best_x is None or vx < best_x - 1.0 or (abs(vx - best_x) < 1.0 and hy < best_y):
                best_x = vx
                best_y = hy

    if best_x is None:
        return None
    return best_x, best_y

# Stage 3: coordinate normalisation (unit scale, origin, polygon ordering)


def detect_scale_to_cm(unit_scale: str) -> float:
    return {"mm": 0.1, "cm": 1.0, "m": 100.0}[unit_scale]


def panel_axes(points: list[Point], u_axis_mode: str) -> tuple[Point, Point, float, float]:
    edges = [sub(points[(i + 1) % 4], points[i]) for i in range(4)]
    lengths = [norm(edge) for edge in edges]
    long_index = max(range(4), key=lambda i: lengths[i])
    short_index = min(range(4), key=lambda i: lengths[i])

    axis_edge = edges[long_index] if u_axis_mode == "long" else edges[short_index]
    u_axis = canonical_axis(axis_edge)
    v_axis = (-u_axis[1], u_axis[0])

    edge_for_v = edges[short_index] if u_axis_mode == "long" else edges[long_index]
    if dot(edge_for_v, v_axis) < 0.0:
        v_axis = (-v_axis[0], -v_axis[1])

    u_extent = max(dot(point, u_axis) for point in points) - min(dot(point, u_axis) for point in points)
    v_extent = max(dot(point, v_axis) for point in points) - min(dot(point, v_axis) for point in points)
    return u_axis, v_axis, u_extent, v_extent


def order_polygon(points: list[Point], u_axis: Point, v_axis: Point) -> list[Point]:
    scored = [(dot(point, u_axis), dot(point, v_axis), point) for point in points]
    min_u = min(item[0] for item in scored)
    max_u = max(item[0] for item in scored)
    min_v = min(item[1] for item in scored)
    max_v = max(item[1] for item in scored)
    targets = [(min_u, min_v), (max_u, min_v), (max_u, max_v), (min_u, max_v)]
    ordered: list[Point] = []
    remaining = scored[:]
    for target_u, target_v in targets:
        best = min(remaining, key=lambda item: (item[0] - target_u) ** 2 + (item[1] - target_v) ** 2)
        ordered.append(best[2])
        remaining.remove(best)
    return ordered


def normalize_panels(candidates: list[QuadCandidate], args: argparse.Namespace, input_dxf: Path) -> tuple[list[Panel], float, Point]:
    scale_to_cm = detect_scale_to_cm(args.unit_scale)
    scaled_polygons = [
        [(point[0] * scale_to_cm, point[1] * scale_to_cm) for point in candidate.points_dxf]
        for candidate in candidates
    ]

    if args.origin == "min":
        origin = (
            min(point[0] for polygon in scaled_polygons for point in polygon),
            min(point[1] for polygon in scaled_polygons for point in polygon),
        )
    elif args.origin == "manual":
        origin = (args.origin_x, args.origin_y)
    elif args.origin == "axis":
        detected = _detect_axis_origin(input_dxf)
        if detected is not None:
            # Convert axis from DXF mm to output cm
            origin = (detected[0] * scale_to_cm, detected[1] * scale_to_cm)
        else:
            # Fall back to min if axis not found
            origin = (
                min(point[0] for polygon in scaled_polygons for point in polygon),
                min(point[1] for polygon in scaled_polygons for point in polygon),
            )
    else:
        origin = (0.0, 0.0)

    panels: list[Panel] = []
    for polygon in scaled_polygons:
        shifted = [(point[0] - origin[0], point[1] - origin[1]) for point in polygon]
        u_axis, v_axis, u_size, v_size = panel_axes(shifted, args.u_axis)
        ordered = order_polygon(shifted, u_axis, v_axis)
        panel_center = center(ordered)
        yaw_deg = math.degrees(math.atan2(u_axis[1], u_axis[0])) % 180.0
        panels.append(
            Panel(
                polygon_cm=ordered,
                center_cm=panel_center,
                u_axis=u_axis,
                v_axis=v_axis,
                u_size_cm=u_size,
                v_size_cm=v_size,
                yaw_deg=yaw_deg,
            )
        )
    return panels, scale_to_cm, origin

# Stage 4: block inference


def cluster_by_orientation(panels: list[Panel], tolerance_deg: float) -> list[list[Panel]]:
    """Group panels by yaw angle, returning clusters of similarly-oriented panels."""
    clusters: list[list[Panel]] = []
    cluster_yaws: list[float] = []
    for panel in sorted(panels, key=lambda p: p.yaw_deg):
        for index, yaw in enumerate(cluster_yaws):
            if angle_distance_deg(panel.yaw_deg, yaw) <= tolerance_deg:
                clusters[index].append(panel)
                cluster_yaws[index] = statistics.mean(p.yaw_deg for p in clusters[index])
                break
        else:
            clusters.append([panel])
            cluster_yaws.append(panel.yaw_deg)
    return clusters


def average_axis(axes: Iterable[Point]) -> Point:
    sx = 0.0
    sy = 0.0
    for axis in axes:
        canonical = canonical_axis(axis)
        sx += canonical[0]
        sy += canonical[1]
    return canonical_axis((sx, sy))


def estimate_pitch(panels: list[Panel], u_axis: Point, v_axis: Point) -> tuple[float, float]:
    """Estimate median centre-to-centre spacing along u and v within a block.

    For each panel the function finds the nearest neighbour in the same row
    (same v, different u) and same column (same u, different v), then takes
    the median of those nearest-neighbour distances.
    """
    centers = [panel.center_cm for panel in panels]
    median_u_size = statistics.median(panel.u_size_cm for panel in panels)
    median_v_size = statistics.median(panel.v_size_cm for panel in panels)
    nearest_u_samples: list[float] = []
    nearest_v_samples: list[float] = []

    for i, first in enumerate(centers):
        same_row_distances: list[float] = []
        same_col_distances: list[float] = []
        for j, second in enumerate(centers):
            if i == j:
                continue
            delta = sub(second, first)
            du = abs(dot(delta, u_axis))
            dv = abs(dot(delta, v_axis))
            if dv < median_v_size * 0.5 and du > median_u_size * 0.5:
                same_row_distances.append(du)
            if du < median_u_size * 0.5 and dv > median_v_size * 0.5:
                same_col_distances.append(dv)
        if same_row_distances:
            nearest_u_samples.append(min(same_row_distances))
        if same_col_distances:
            nearest_v_samples.append(min(same_col_distances))

    u_pitch = statistics.median(nearest_u_samples) if nearest_u_samples else median_u_size
    v_pitch = statistics.median(nearest_v_samples) if nearest_v_samples else median_v_size
    return max(u_pitch, median_u_size), max(v_pitch, median_v_size)


def connected_components(panels: list[Panel], u_axis: Point, v_axis: Point, args: argparse.Namespace) -> list[list[Panel]]:
    if not panels:
        return []
    adjacency: list[list[int]] = [[] for _ in panels]

    max_u_size = max(panel.u_size_cm for panel in panels)
    max_v_size = max(panel.v_size_cm for panel in panels)
    search_radius = max(max_u_size, max_v_size) + args.max_adjacent_gap_cm + args.same_line_tolerance_cm
    bucket_size = max(search_radius, 1.0)
    bucket_span = max(1, math.ceil(search_radius / bucket_size))
    projected_centers = [(dot(panel.center_cm, u_axis), dot(panel.center_cm, v_axis)) for panel in panels]
    buckets: dict[tuple[int, int], list[int]] = defaultdict(list)
    for index, (center_u, center_v) in enumerate(projected_centers):
        buckets[(math.floor(center_u / bucket_size), math.floor(center_v / bucket_size))].append(index)

    for i, first in enumerate(panels):
        center_u, center_v = projected_centers[i]
        bucket_u = math.floor(center_u / bucket_size)
        bucket_v = math.floor(center_v / bucket_size)
        for du_bucket in range(-bucket_span, bucket_span + 1):
            for dv_bucket in range(-bucket_span, bucket_span + 1):
                for j in buckets.get((bucket_u + du_bucket, bucket_v + dv_bucket), []):
                    if j <= i:
                        continue
                    second = panels[j]
                    other_u, other_v = projected_centers[j]
                    du = abs(other_u - center_u)
                    dv = abs(other_v - center_v)
                    if du > search_radius and dv > search_radius:
                        continue
                    u_gap = du - 0.5 * (first.u_size_cm + second.u_size_cm)
                    v_gap = dv - 0.5 * (first.v_size_cm + second.v_size_cm)
                    u_neighbor = (
                        dv <= args.same_line_tolerance_cm
                        and -args.same_line_tolerance_cm <= u_gap <= args.max_adjacent_gap_cm
                    )
                    v_neighbor = (
                        du <= args.same_line_tolerance_cm
                        and -args.same_line_tolerance_cm <= v_gap <= args.max_adjacent_gap_cm
                    )
                    if u_neighbor or v_neighbor:
                        adjacency[i].append(j)
                        adjacency[j].append(i)

    components: list[list[Panel]] = []
    visited = [False] * len(panels)
    for start in range(len(panels)):
        if visited[start]:
            continue
        queue: deque[int] = deque([start])
        visited[start] = True
        component: list[Panel] = []
        while queue:
            index = queue.popleft()
            component.append(panels[index])
            for neighbor in adjacency[index]:
                if not visited[neighbor]:
                    visited[neighbor] = True
                    queue.append(neighbor)
        if len(component) >= args.min_block_cells:
            components.append(component)
    return components


def cluster_values(values: list[float], tolerance: float) -> list[float]:
    clusters: list[list[float]] = []
    for value in sorted(values):
        if not clusters or abs(value - statistics.mean(clusters[-1])) > tolerance:
            clusters.append([value])
        else:
            clusters[-1].append(value)
    return [statistics.mean(cluster) for cluster in clusters]


def nearest_index(value: float, centers: list[float]) -> int:
    return min(range(len(centers)), key=lambda index: abs(value - centers[index]))


def build_block(component: list[Panel], block_id: int, args: argparse.Namespace) -> BlockModel:
    u_axis = average_axis(panel.u_axis for panel in component)
    v_axis = (-u_axis[1], u_axis[0])
    if dot(component[0].v_axis, v_axis) < 0.0:
        v_axis = (-v_axis[0], -v_axis[1])

    u_pitch, v_pitch = estimate_pitch(component, u_axis, v_axis)
    row_tolerance = v_pitch * 0.5
    col_tolerance = u_pitch * 0.5

    center_u_values = [dot(panel.center_cm, u_axis) for panel in component]
    center_v_values = [dot(panel.center_cm, v_axis) for panel in component]
    col_centers = cluster_values(center_u_values, col_tolerance)
    row_centers = cluster_values(center_v_values, row_tolerance)

    rows = len(row_centers)
    cols = len(col_centers)
    grid = [[0 for _ in range(cols)] for _ in range(rows)]
    occupied: set[tuple[int, int]] = set()
    for panel in component:
        row = nearest_index(dot(panel.center_cm, v_axis), row_centers)
        col = nearest_index(dot(panel.center_cm, u_axis), col_centers)
        if (row, col) in occupied:
            raise RuntimeError(f"duplicate panel assignment in block {block_id}: row={row}, col={col}")
        occupied.add((row, col))
        panel.block_id = block_id
        panel.row = row
        panel.col = col
        grid[row][col] = 1

    all_points = [point for panel in component for point in panel.polygon_cm]
    min_u = min(dot(point, u_axis) for point in all_points)
    min_v = min(dot(point, v_axis) for point in all_points)
    origin = add(mul(u_axis, min_u), mul(v_axis, min_v))
    return BlockModel(
        block_id=block_id,
        panels=component,
        origin=origin,
        u_axis=u_axis,
        v_axis=v_axis,
        rows=rows,
        cols=cols,
        grid=grid,
        cell_ids=[],
    )


def infer_blocks(panels: list[Panel], args: argparse.Namespace) -> list[BlockModel]:
    preliminary: list[list[Panel]] = []
    for orientation_cluster in cluster_by_orientation(panels, args.angle_tolerance_deg):
        u_axis = average_axis(panel.u_axis for panel in orientation_cluster)
        v_axis = (-u_axis[1], u_axis[0])
        preliminary.extend(connected_components(orientation_cluster, u_axis, v_axis, args))

    preliminary.sort(key=lambda component: (
        min(panel.center_cm[1] for panel in component), min(panel.center_cm[0] for panel in component)))
    blocks = [build_block(component, index + 1, args) for index, component in enumerate(preliminary)]

    cell_id = 1
    for block in blocks:
        block.panels.sort(key=lambda panel: (panel.row, panel.col))
        for panel in block.panels:
            panel.cell_id = cell_id
            block.cell_ids.append(cell_id)
            cell_id += 1
    return blocks

# Output helpers


def build_yaml_map(blocks: list[BlockModel], args: argparse.Namespace) -> dict:
    cells = []
    block_entries = []
    for block in blocks:
        block_entries.append(
            {
                "block_id": block.block_id,
                "block_frame": {
                    "block_origin": round_point(block.origin, args.precision),
                    "u_axis": round_point(block.u_axis, 6),
                    "v_axis": round_point(block.v_axis, 6),
                },
                "rows": block.rows,
                "cols": block.cols,
                "grid": block.grid,
                "cell_ids": block.cell_ids,
                "cleanable": True,
            }
        )
        for panel in block.panels:
            cells.append(
                {
                    "cell_id": panel.cell_id,
                    "block_id": block.block_id,
                    "row": panel.row,
                    "col": panel.col,
                    "polygon": [round_point(point, args.precision) for point in panel.polygon_cm],
                }
            )

    frame: dict = {
        "unit": "centimeter",
        "origin": {
            "latitude_deg": round_float(args.latitude, 7),
            "longitude_deg": round_float(args.longitude, 7),
            "yaw_deg": round_float(args.yaw, 3),
        },
    }

    return {
        "map_id": args.map_id,
        "version": args.version,
        "source": {"type": "dxf", "file_name": args.input_dxf.name},
        "frame": frame,
        "cell_model": {"inner_rows": args.inner_rows, "inner_cols": args.inner_cols},
        "blocks": block_entries,
        "bridges": [],
        "cells": cells,
    }


def print_summary(stats: ExtractionStats, panels: list[Panel], blocks: list[BlockModel], scale_to_cm: float, origin: Point, args: argparse.Namespace, layer: str) -> None:
    print(f"input: {args.input_dxf}")
    print(f"layer: {layer}")
    print(f"modelspace entities: {stats.modelspace_entities}")
    print(f"top-level layer entities: {stats.top_layer_entities}")
    print(f"expanded entities: {stats.expanded_entities}")
    print(f"insert expansion errors: {stats.insert_errors}")
    print(f"closed 4-point polylines: {stats.closed_quads}")
    print(f"accepted panel candidates: {stats.accepted_panels}")
    print(f"scale_to_cm: {scale_to_cm}")
    print(f"origin_cm: ({origin[0]:.3f}, {origin[1]:.3f})")
    print(f"inferred blocks: {len(blocks)}")
    print(f"output cells: {len(panels)}")
    if args.verbose:
        print("size clusters in DXF units, rounded to 100:")
        for (short_side, long_side), count in stats.size_clusters.most_common(20):
            print(f"  {short_side:.0f} x {long_side:.0f}: {count}")
        print("blocks:")
        for block in blocks:
            occupied = sum(sum(row) for row in block.grid)
            print(f"  block {block.block_id}: rows={block.rows} cols={block.cols} cells={occupied}")


def main() -> int:
    args = parse_args()
    if args.inner_rows <= 0 or args.inner_cols <= 0:
        raise RuntimeError("--inner-rows and --inner-cols must be positive")

    dxf_path = _ensure_dxf(args.input_dxf, args.verbose)
    try:
        candidates, stats, used_layer = extract_candidates(dxf_path, args.layer, args)
        if not candidates:
            print("No panel candidates matched the configured filters.", file=sys.stderr)
            if stats.size_clusters:
                print("Top closed-quad size clusters, rounded to 100 DXF units:", file=sys.stderr)
                for (short_side, long_side), count in stats.size_clusters.most_common(10):
                    print(f"  {short_side:.0f} x {long_side:.0f}: {count}", file=sys.stderr)
            return 1

        panels, scale_to_cm, origin = normalize_panels(candidates, args, dxf_path)
        blocks = infer_blocks(panels, args)
        print_summary(stats, panels, blocks, scale_to_cm, origin, args, used_layer)

        if args.dry_run:
            print("dry-run: YAML was not written")
            return 0

        map_data = build_yaml_map(blocks, args)
        args.output_yaml.parent.mkdir(parents=True, exist_ok=True)
        with args.output_yaml.open("w", encoding="utf-8") as stream:
            yaml.safe_dump(map_data, stream, allow_unicode=True, sort_keys=False)
        print(f"wrote: {args.output_yaml}")
        return 0
    finally:
        # Clean up temporary DXF if it was converted from DWG
        if dxf_path != args.input_dxf and dxf_path.exists():
            shutil.rmtree(dxf_path.parent, ignore_errors=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
