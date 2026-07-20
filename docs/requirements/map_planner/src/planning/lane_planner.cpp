#include "map_planner/planning/lane_planner.hpp"

#include <algorithm>
#include <cmath>
#include <optional>
#include <set>

namespace map_planner {

namespace {

Heading forward_heading(SweepAxis axis) {
  return axis == SweepAxis::BlockU ? Heading::BlockUPositive
                                   : Heading::BlockVPositive;
}

Heading reverse_heading(SweepAxis axis) {
  return axis == SweepAxis::BlockU ? Heading::BlockUNegative
                                   : Heading::BlockVNegative;
}

int total_cross_cells(
  const Block &block, const CellModel &cell_model, SweepAxis axis) {
  return axis == SweepAxis::BlockU ? block.rows * cell_model.inner_rows
                                   : block.cols * cell_model.inner_cols;
}

int total_along_cells(
  const Block &block, const CellModel &cell_model, SweepAxis axis) {
  return axis == SweepAxis::BlockU ? block.cols * cell_model.inner_cols
                                   : block.rows * cell_model.inner_rows;
}

CenterInnerCell make_center(
  uint32_t block_id, const CellModel &cell_model, SweepAxis axis,
  int cross_index, int along_index) {
  CenterInnerCell center;
  center.block_id = block_id;
  if (axis == SweepAxis::BlockU) {
    center.cell_row  = cross_index / cell_model.inner_rows;
    center.inner_row = cross_index % cell_model.inner_rows;
    center.cell_col  = along_index / cell_model.inner_cols;
    center.inner_col = along_index % cell_model.inner_cols;
  } else {
    center.cell_col  = cross_index / cell_model.inner_cols;
    center.inner_col = cross_index % cell_model.inner_cols;
    center.cell_row  = along_index / cell_model.inner_rows;
    center.inner_row = along_index % cell_model.inner_rows;
  }
  return center;
}

LaneSegment make_segment(SweepAxis axis, int cross_index, Heading heading) {
  LaneSegment segment;
  segment.sweep_axis        = axis;
  segment.lane_index        = cross_index;
  segment.lane_offset_inner = cross_index;
  segment.heading           = heading;
  return segment;
}

std::vector<LaneSegment> make_segments_for_lane(
  SweepAxis axis, int cross_index, uint32_t block_id, const Block &block,
  const CellModel &cell_model, const CenterGrid &center_grid, Heading heading,
  bool reverse) {
  std::vector<LaneSegment> segments;
  auto current          = make_segment(axis, cross_index, heading);
  const int along_total = total_along_cells(block, cell_model, axis);

  auto flush_current = [&]() {
    if (!current.centers.empty()) {
      segments.push_back(std::move(current));
      current = make_segment(axis, cross_index, heading);
    }
  };

  const int begin = reverse ? along_total - 1 : 0;
  const int end   = reverse ? -1 : along_total;
  const int step  = reverse ? -1 : 1;
  for (int along = begin; along != end; along += step) {
    const auto center =
      make_center(block_id, cell_model, axis, cross_index, along);
    if (center_grid.is_traversable(center, heading)) {
      current.centers.push_back(center);
    } else {
      flush_current();
    }
  }
  flush_current();
  return segments;
}

double cross_cell_size_cm(
  SweepAxis axis, const map_geometry::BlockInnerCellSizeStats &stats) {
  return axis == SweepAxis::BlockU ? stats.median_inner_v_size_cm
                                   : stats.median_inner_u_size_cm;
}

int full_coverage_cross_radius_cells(
  SweepAxis axis, const map_geometry::BlockInnerCellSizeStats &stats,
  const RobotPlanningConfig &config) {
  const double cross_size = cross_cell_size_cm(axis, stats);
  const double half_width = config.cleaning_width_cm * 0.5;
  if (cross_size <= 0.0 || half_width <= 0.0) {
    return 0;
  }
  return std::max(
    0,
    static_cast<int>(std::floor((half_width - cross_size * 0.5) / cross_size)));
}

bool lane_has_free_center_for_heading(
  SweepAxis axis, int cross_index, uint32_t block_id, const Block &block,
  const CellModel &cell_model, const CenterGrid &center_grid, Heading heading,
  bool reverse) {
  const int along_total = total_along_cells(block, cell_model, axis);
  const int begin       = reverse ? along_total - 1 : 0;
  const int end         = reverse ? -1 : along_total;
  const int step        = reverse ? -1 : 1;
  for (int along = begin; along != end; along += step) {
    const auto center =
      make_center(block_id, cell_model, axis, cross_index, along);
    if (center_grid.is_traversable(center, heading)) {
      return true;
    }
  }
  return false;
}

bool lane_has_free_center(
  SweepAxis axis, int cross_index, uint32_t block_id, const Block &block,
  const CellModel &cell_model, const CenterGrid &center_grid) {
  return lane_has_free_center_for_heading(
           axis, cross_index, block_id, block, cell_model, center_grid,
           forward_heading(axis), false) ||
         lane_has_free_center_for_heading(
           axis, cross_index, block_id, block, cell_model, center_grid,
           reverse_heading(axis), true);
}

std::optional<int> find_boundary_full_cover_lane(
  SweepAxis axis, uint32_t block_id, const Block &block,
  const CellModel &cell_model, const CenterGrid &center_grid, int cross_total,
  int full_cover_radius_cells, bool left_boundary) {
  if (cross_total <= 0 || full_cover_radius_cells < 0) {
    return std::nullopt;
  }

  const int boundary         = left_boundary ? 0 : cross_total - 1;
  const int begin            = left_boundary
                                 ? std::min(full_cover_radius_cells, cross_total - 1)
                                 : std::max(0, cross_total - 1 - full_cover_radius_cells);
  const int step_to_boundary = left_boundary ? -1 : 1;
  for (int cross = begin;; cross += step_to_boundary) {
    if (lane_has_free_center(
          axis, cross, block_id, block, cell_model, center_grid)) {
      return cross;
    }
    if (cross == boundary) {
      break;
    }
  }

  const int step_inward = left_boundary ? 1 : -1;
  for (int cross = boundary + step_inward; cross >= 0 && cross < cross_total;
       cross += step_inward) {
    if (lane_has_free_center(
          axis, cross, block_id, block, cell_model, center_grid)) {
      return cross;
    }
  }
  return std::nullopt;
}

void add_lane_if_usable(
  SweepAxis axis, int cross_index, uint32_t block_id, const Block &block,
  const CellModel &cell_model, const CenterGrid &center_grid,
  std::vector<int> &lane_indices) {
  if (
    cross_index < 0 ||
    cross_index >= total_cross_cells(block, cell_model, axis)) {
    return;
  }
  if (lane_has_free_center(
        axis, cross_index, block_id, block, cell_model, center_grid)) {
    lane_indices.push_back(cross_index);
  }
}

void add_boundary_lane_with_backup(
  SweepAxis axis, uint32_t block_id, const Block &block,
  const CellModel &cell_model, const CenterGrid &center_grid, int cross_total,
  int full_cover_radius_cells, bool left_boundary,
  std::vector<int> &lane_indices) {
  const auto boundary_lane = find_boundary_full_cover_lane(
    axis, block_id, block, cell_model, center_grid, cross_total,
    full_cover_radius_cells, left_boundary);
  if (!boundary_lane.has_value()) {
    return;
  }
  lane_indices.push_back(*boundary_lane);
  const int backup_lane =
    left_boundary ? *boundary_lane + 1 : *boundary_lane - 1;
  add_lane_if_usable(
    axis, backup_lane, block_id, block, cell_model, center_grid, lane_indices);
}

std::vector<int> representative_values(
  const std::set<int, std::greater<int>> &values) {
  return std::vector<int>(values.begin(), values.end());
}

std::vector<int> stride_values_for_effort(
  int initial_stride, const RobotPlanningConfig &config) {
  if (
    config.planning_search_effort == "quality" ||
    config.planning_search_effort == "exhaustive") {
    std::vector<int> strides;
    for (int stride = initial_stride; stride >= 1; --stride) {
      strides.push_back(stride);
    }
    return strides;
  }

  std::set<int, std::greater<int>> strides;
  auto add_stride = [&](int stride) {
    if (stride >= 1 && stride <= initial_stride) {
      strides.insert(stride);
    }
  };
  add_stride(initial_stride);
  if (config.planning_search_effort == "balanced") {
    add_stride(initial_stride - 1);
  }
  add_stride(std::max(1, initial_stride / 2));
  add_stride(1);
  return representative_values(strides);
}

std::vector<int> offset_values_for_effort(
  int stride, int cross_total, const RobotPlanningConfig &config) {
  const int offset_count = std::min(stride, cross_total);
  if (
    offset_count <= 0 || config.planning_search_effort == "quality" ||
    config.planning_search_effort == "exhaustive" || offset_count <= 3) {
    std::vector<int> offsets;
    for (int offset = 0; offset < offset_count; ++offset) {
      offsets.push_back(offset);
    }
    return offsets;
  }

  std::set<int> offsets;
  auto add_offset = [&](int offset) {
    if (offset >= 0 && offset < offset_count) {
      offsets.insert(offset);
    }
  };
  add_offset(0);
  add_offset(offset_count / 2);
  add_offset(offset_count - 1);
  if (config.planning_search_effort == "balanced") {
    add_offset(offset_count / 3);
    add_offset((offset_count * 2) / 3);
  }
  return {offsets.begin(), offsets.end()};
}

}  // namespace

int LanePlanner::compute_lane_stride(
  SweepAxis sweep_axis, const map_geometry::BlockInnerCellSizeStats &stats,
  const RobotPlanningConfig &config) {
  const double cross_size = sweep_axis == SweepAxis::BlockU
                              ? stats.median_inner_v_size_cm
                              : stats.median_inner_u_size_cm;
  if (cross_size <= 0.0) {
    return 1;
  }
  const double effective_width =
    config.cleaning_width_cm * (1.0 - config.overlap_ratio);
  return std::max(
    1, static_cast<int>(std::floor(effective_width / cross_size)));
}

std::vector<LanePlanCandidate> LanePlanner::make_candidates(
  const PvMap &map, const MapRepository &repository, const Block &block,
  const CenterGrid &center_grid, const RobotPlanningConfig &config) const {
  std::vector<LanePlanCandidate> candidates;
  const auto stats =
    map_geometry::estimate_block_inner_cell_size_stats(map, repository, block);

  for (const auto axis : {SweepAxis::BlockU, SweepAxis::BlockV}) {
    const int initial_stride = compute_lane_stride(axis, stats, config);
    const int cross_total    = total_cross_cells(block, map.cell_model, axis);
    for (const auto stride : stride_values_for_effort(initial_stride, config)) {
      for (const auto offset :
           offset_values_for_effort(stride, cross_total, config)) {
        LanePlanCandidate candidate;
        candidate.sweep_axis  = axis;
        candidate.lane_stride = stride;
        candidate.lane_offset = offset;

        std::vector<int> lane_indices;
        for (int cross = offset; cross < cross_total; cross += stride) {
          lane_indices.push_back(cross);
        }
        if (config.enable_tail_coverage && stride > 1) {
          const int full_cover_radius_cells =
            full_coverage_cross_radius_cells(axis, stats, config);
          add_boundary_lane_with_backup(
            axis, block.block_id, block, map.cell_model, center_grid,
            cross_total, full_cover_radius_cells, true, lane_indices);
          add_boundary_lane_with_backup(
            axis, block.block_id, block, map.cell_model, center_grid,
            cross_total, full_cover_radius_cells, false, lane_indices);
        }
        std::sort(lane_indices.begin(), lane_indices.end());
        lane_indices.erase(
          std::unique(lane_indices.begin(), lane_indices.end()),
          lane_indices.end());

        bool reverse = false;
        for (const auto cross : lane_indices) {
          const auto heading =
            reverse ? reverse_heading(axis) : forward_heading(axis);
          auto lane_segments = make_segments_for_lane(
            axis, cross, block.block_id, block, map.cell_model, center_grid,
            heading, reverse);
          if (!lane_segments.empty()) {
            candidate.segments.insert(
              candidate.segments.end(),
              std::make_move_iterator(lane_segments.begin()),
              std::make_move_iterator(lane_segments.end()));
          } else if (cross != 0 && cross != cross_total - 1) {
            candidate.unreachable_segments.push_back(
              "lane " + std::to_string(cross) + " has no FREE centers");
          }
          reverse = !reverse;
        }

        candidate.coverage_complete =
          candidate.unreachable_segments.empty() && !candidate.segments.empty();
        candidate.cost = static_cast<double>(candidate.segments.size());
        for (const auto &segment : candidate.segments) {
          candidate.cost += static_cast<double>(segment.centers.size());
        }
        if (!candidate.segments.empty()) {
          candidates.push_back(std::move(candidate));
        }
      }
    }
  }

  return candidates;
}

}  // namespace map_planner
