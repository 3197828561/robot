#include "map_planner/planning/block_coverage_candidate_generator.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <iomanip>
#include <iterator>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <tuple>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace map_planner {
namespace {

struct ConnectedCandidate {
  LanePlanCandidate candidate;
  std::vector<PathWaypoint> waypoints;
  std::vector<std::string> connection_errors;
  size_t connected_segment_count{};
  size_t skipped_segment_count{};
  size_t total_clean_center_count{};
  size_t covered_clean_center_count{};
  size_t transition_waypoint_count{};
  size_t transition_turn_count{};
  size_t continuity_error_count{};
  double coverage_ratio{};
  bool coverage_complete{false};
  double cost{};
  std::string score_breakdown;
};

struct ConnectionChoice {
  size_t segment_index{};
  LaneSegment segment;
  std::vector<PathWaypoint> connection_waypoints;
  size_t connection_size{};
  int distance{};
};

struct RankedPose {
  GridPose pose;
  int corner_index{};
  int distance{};
};

struct CoverageReference {
  std::vector<CenterInnerCell> centers;
  std::vector<uint64_t> center_keys;
  int cross_radius_cells{};
};

constexpr double kMinGoodCoverageRatio = 0.95;

Heading opposite_heading(Heading heading) {
  switch (heading) {
    case Heading::BlockUPositive:
      return Heading::BlockUNegative;
    case Heading::BlockUNegative:
      return Heading::BlockUPositive;
    case Heading::BlockVPositive:
      return Heading::BlockVNegative;
    case Heading::BlockVNegative:
      return Heading::BlockVPositive;
  }
  return Heading::BlockUPositive;
}

LaneSegment reversed_segment(const LaneSegment &segment) {
  LaneSegment reversed = segment;
  std::reverse(reversed.centers.begin(), reversed.centers.end());
  reversed.heading = opposite_heading(segment.heading);
  return reversed;
}

bool same_center(const CenterInnerCell &a, const CenterInnerCell &b) {
  return a.block_id == b.block_id && a.cell_row == b.cell_row &&
         a.cell_col == b.cell_col && a.inner_row == b.inner_row &&
         a.inner_col == b.inner_col;
}

bool same_pose(const GridPose &a, const GridPose &b) {
  return same_center(a.center, b.center) && a.heading == b.heading;
}

void hash_combine(size_t &seed, size_t value) {
  seed ^= value + 0x9e3779b97f4a7c15ULL + (seed << 6U) + (seed >> 2U);
}

struct ConnectionCacheKey {
  GridPose from;
  GridPose to;

  bool operator==(const ConnectionCacheKey &other) const {
    return same_pose(from, other.from) && same_pose(to, other.to);
  }
};

struct ConnectionCacheKeyHash {
  size_t operator()(const ConnectionCacheKey &key) const {
    size_t seed      = 0;
    auto hash_center = [&](const CenterInnerCell &center) {
      hash_combine(seed, std::hash<uint32_t>{}(center.block_id));
      hash_combine(seed, std::hash<int>{}(center.cell_row));
      hash_combine(seed, std::hash<int>{}(center.cell_col));
      hash_combine(seed, std::hash<int>{}(center.inner_row));
      hash_combine(seed, std::hash<int>{}(center.inner_col));
    };
    hash_center(key.from.center);
    hash_combine(
      seed, std::hash<uint8_t>{}(static_cast<uint8_t>(key.from.heading)));
    hash_center(key.to.center);
    hash_combine(
      seed, std::hash<uint8_t>{}(static_cast<uint8_t>(key.to.heading)));
    return seed;
  }
};

struct CachedConnection {
  bool success{false};
  std::vector<PathWaypoint> waypoints;
  std::string error;
};

class CachedMotionConnector {
 public:
  bool append_connection(
    const GridPose &from, const GridPose &to, const CenterGrid &center_grid,
    const CellModel &cell_model, std::vector<PathWaypoint> &waypoints,
    std::string &error) {
    const ConnectionCacheKey key{from, to};
    const auto it = cache_.find(key);
    if (it != cache_.end()) {
      if (!it->second.success) {
        error = it->second.error;
        return false;
      }
      waypoints.insert(
        waypoints.end(), it->second.waypoints.begin(),
        it->second.waypoints.end());
      return true;
    }

    std::vector<PathWaypoint> computed;
    std::string computed_error;
    const bool success = connector_.append_connection(
      from, to, center_grid, cell_model, computed, computed_error);
    const auto [inserted_it, inserted] =
      cache_.emplace(key, CachedConnection{success, computed, computed_error});
    (void)inserted;
    if (!inserted_it->second.success) {
      error = inserted_it->second.error;
      return false;
    }
    waypoints.insert(
      waypoints.end(), inserted_it->second.waypoints.begin(),
      inserted_it->second.waypoints.end());
    return true;
  }

 private:
  MotionConnector connector_;
  std::unordered_map<
    ConnectionCacheKey, CachedConnection, ConnectionCacheKeyHash>
    cache_;
};

int global_row(const CenterInnerCell &center, const CellModel &cell_model) {
  return center.cell_row * cell_model.inner_rows + center.inner_row;
}

int global_col(const CenterInnerCell &center, const CellModel &cell_model) {
  return center.cell_col * cell_model.inner_cols + center.inner_col;
}

int grid_distance(
  const CenterInnerCell &a, const CenterInnerCell &b,
  const CellModel &cell_model) {
  return std::abs(global_row(a, cell_model) - global_row(b, cell_model)) +
         std::abs(global_col(a, cell_model) - global_col(b, cell_model));
}

int cross_index(
  const CenterInnerCell &center, SweepAxis axis, const CellModel &cell_model) {
  return axis == SweepAxis::BlockU ? global_row(center, cell_model)
                                   : global_col(center, cell_model);
}

int along_index(
  const CenterInnerCell &center, SweepAxis axis, const CellModel &cell_model) {
  return axis == SweepAxis::BlockU ? global_col(center, cell_model)
                                   : global_row(center, cell_model);
}

std::tuple<uint32_t, int, int, int, int> center_key(
  const CenterInnerCell &center) {
  return {
    center.block_id, center.cell_row, center.cell_col, center.inner_row,
    center.inner_col};
}

std::vector<CenterInnerCell> collect_reference_centers(
  const std::vector<LanePlanCandidate> &candidates) {
  std::vector<CenterInnerCell> centers;
  std::set<std::tuple<uint32_t, int, int, int, int>> seen;
  for (const auto &candidate : candidates) {
    for (const auto &segment : candidate.segments) {
      for (const auto &center : segment.centers) {
        if (seen.insert(center_key(center)).second) {
          centers.push_back(center);
        }
      }
    }
  }
  return centers;
}

int full_coverage_cross_radius_cells(
  SweepAxis axis, const map_geometry::BlockInnerCellSizeStats &stats,
  const RobotPlanningConfig &config) {
  const double cross_size = axis == SweepAxis::BlockU
                              ? stats.median_inner_v_size_cm
                              : stats.median_inner_u_size_cm;
  const double half_width = config.cleaning_width_cm * 0.5;
  if (cross_size <= 0.0 || half_width <= 0.0) {
    return 0;
  }
  return std::max(
    0,
    static_cast<int>(std::floor((half_width - cross_size * 0.5) / cross_size)));
}

bool clean_waypoint_covers_center(
  const PathWaypoint &waypoint, const CenterInnerCell &center, SweepAxis axis,
  const CellModel &cell_model, int cross_radius_cells) {
  if (
    waypoint.type != WaypointType::Clean ||
    !waypoint.center_inner_cell.has_value()) {
    return false;
  }
  const auto &clean_center = *waypoint.center_inner_cell;
  if (clean_center.block_id != center.block_id) {
    return false;
  }
  return along_index(clean_center, axis, cell_model) ==
           along_index(center, axis, cell_model) &&
         std::abs(
           cross_index(clean_center, axis, cell_model) -
           cross_index(center, axis, cell_model)) <= cross_radius_cells;
}

uint64_t coverage_key(
  const CenterInnerCell &center, const CellModel &cell_model, SweepAxis axis) {
  const auto along =
    static_cast<uint64_t>(along_index(center, axis, cell_model));
  const auto cross =
    static_cast<uint64_t>(cross_index(center, axis, cell_model));
  return (static_cast<uint64_t>(center.block_id) << 48U) | (along << 24U) |
         cross;
}

CoverageReference make_coverage_reference(
  const std::vector<CenterInnerCell> &centers, SweepAxis axis,
  const CellModel &cell_model, int cross_radius_cells) {
  CoverageReference reference;
  reference.centers            = centers;
  reference.cross_radius_cells = cross_radius_cells;
  reference.center_keys.reserve(reference.centers.size());
  for (const auto &center : reference.centers) {
    reference.center_keys.push_back(coverage_key(center, cell_model, axis));
  }
  return reference;
}

size_t count_geometric_covered_centers(
  const std::vector<PathWaypoint> &waypoints,
  const CoverageReference &reference, SweepAxis axis,
  const CellModel &cell_model) {
  std::unordered_set<uint64_t> covered_keys;
  covered_keys.reserve(
    waypoints.size() *
    static_cast<size_t>(reference.cross_radius_cells * 2 + 1));
  for (const auto &waypoint : waypoints) {
    if (
      waypoint.type != WaypointType::Clean ||
      !waypoint.center_inner_cell.has_value()) {
      continue;
    }
    const auto &center    = *waypoint.center_inner_cell;
    const int clean_cross = cross_index(center, axis, cell_model);
    for (int delta = -reference.cross_radius_cells;
         delta <= reference.cross_radius_cells; ++delta) {
      CenterInnerCell covered_center = center;
      const int covered_cross        = clean_cross + delta;
      if (covered_cross < 0) {
        continue;
      }
      if (axis == SweepAxis::BlockU) {
        covered_center.cell_row  = covered_cross / cell_model.inner_rows;
        covered_center.inner_row = covered_cross % cell_model.inner_rows;
      } else {
        covered_center.cell_col  = covered_cross / cell_model.inner_cols;
        covered_center.inner_col = covered_cross % cell_model.inner_cols;
      }
      covered_keys.insert(coverage_key(covered_center, cell_model, axis));
    }
  }

  size_t covered_count = 0;
  for (const auto key : reference.center_keys) {
    if (covered_keys.find(key) != covered_keys.end()) {
      ++covered_count;
    }
  }
  return covered_count;
}

bool are_consecutive_waypoints_valid(
  const PathWaypoint &prev, const PathWaypoint &next,
  const CellModel &cell_model) {
  if (
    !prev.center_inner_cell.has_value() ||
    !next.center_inner_cell.has_value()) {
    return true;
  }
  const auto &prev_center = *prev.center_inner_cell;
  const auto &next_center = *next.center_inner_cell;
  if (prev_center.block_id != next_center.block_id) {
    return true;
  }
  if (same_center(prev_center, next_center)) {
    return true;
  }
  return grid_distance(prev_center, next_center, cell_model) == 1;
}

std::string center_to_string(const CenterInnerCell &center) {
  return "block=" + std::to_string(center.block_id) + " cell=(" +
         std::to_string(center.cell_row) + "," +
         std::to_string(center.cell_col) + ") inner=(" +
         std::to_string(center.inner_row) + "," +
         std::to_string(center.inner_col) + ")";
}

std::string pose_to_string(const GridPose &pose) {
  return center_to_string(pose.center) + " heading=" + to_string(pose.heading);
}

std::string segment_to_string(size_t index, const LaneSegment &segment) {
  std::string message = "segment#" + std::to_string(index) +
                        " lane=" + std::to_string(segment.lane_index) +
                        " axis=" + to_string(segment.sweep_axis) +
                        " heading=" + to_string(segment.heading) +
                        " size=" + std::to_string(segment.centers.size());
  if (!segment.centers.empty()) {
    message += " [" + center_to_string(segment.centers.front()) + " -> " +
               center_to_string(segment.centers.back()) + "]";
  }
  return message;
}

size_t validate_path_continuity(
  const std::vector<PathWaypoint> &waypoints, const CellModel &cell_model,
  std::vector<std::string> &errors) {
  size_t error_count = 0;
  for (size_t index = 1; index < waypoints.size(); ++index) {
    if (are_consecutive_waypoints_valid(
          waypoints[index - 1], waypoints[index], cell_model)) {
      continue;
    }
    ++error_count;
    if (
      errors.size() < 20 &&
      waypoints[index - 1].center_inner_cell.has_value() &&
      waypoints[index].center_inner_cell.has_value()) {
      errors.push_back(
        "continuity jump " + std::to_string(index - 1) + " -> " +
        std::to_string(index) + ": " +
        center_to_string(*waypoints[index - 1].center_inner_cell) + " -> " +
        center_to_string(*waypoints[index].center_inner_cell));
    }
  }
  return error_count;
}

PathWaypoint make_clean_waypoint(
  const CenterInnerCell &center, Heading heading) {
  PathWaypoint waypoint;
  waypoint.type              = WaypointType::Clean;
  waypoint.center_inner_cell = center;
  waypoint.heading           = heading;
  waypoint.brush_on          = true;
  return waypoint;
}

void append_clean_waypoints(
  const LaneSegment &segment, std::vector<PathWaypoint> &waypoints,
  size_t &covered_clean_center_count) {
  for (const auto &center : segment.centers) {
    waypoints.push_back(make_clean_waypoint(center, segment.heading));
    ++covered_clean_center_count;
  }
}

std::string describe_segment_error(
  const LaneSegment &segment, const std::string &error) {
  std::string message =
    "lane " + std::to_string(segment.lane_index) + " segment";
  if (!segment.centers.empty()) {
    message += " [" + center_to_string(segment.centers.front()) + " -> " +
               center_to_string(segment.centers.back()) + "]";
  }
  message += ": " + error;
  return message;
}

std::optional<ConnectionChoice> choose_next_connected_segment(
  const std::vector<LaneSegment> &segments, const std::vector<bool> &used,
  const GridPose &previous_pose, CachedMotionConnector &connector,
  const CenterGrid &center_grid, const CellModel &cell_model,
  std::vector<std::string> *debug_errors = nullptr) {
  std::optional<ConnectionChoice> best;
  for (size_t segment_index = 0; segment_index < segments.size();
       ++segment_index) {
    if (used[segment_index] || segments[segment_index].centers.empty()) {
      continue;
    }
    const auto reversed = reversed_segment(segments[segment_index]);
    const std::array<LaneSegment, 2> options{segments[segment_index], reversed};
    for (const auto &option : options) {
      std::vector<PathWaypoint> trial;
      std::string error;
      const GridPose start{option.centers.front(), option.heading};
      if (!connector.append_connection(
            previous_pose, start, center_grid, cell_model, trial, error)) {
        if (debug_errors != nullptr && debug_errors->size() < 30) {
          debug_errors->push_back(
            "from " + pose_to_string(previous_pose) + " to " +
            pose_to_string(start) + " failed: " + error);
        }
        continue;
      }
      const auto distance =
        grid_distance(previous_pose.center, option.centers.front(), cell_model);
      const ConnectionChoice choice{
        segment_index, option, trial, trial.size(), distance};
      if (
        !best.has_value() || choice.connection_size < best->connection_size ||
        (choice.connection_size == best->connection_size &&
         choice.distance < best->distance) ||
        (choice.connection_size == best->connection_size &&
         choice.distance == best->distance &&
         choice.segment_index < best->segment_index)) {
        best = choice;
      }
    }
  }
  return best;
}

std::vector<size_t> representative_segment_indices(
  const std::vector<LaneSegment> &segments, size_t max_indices) {
  std::vector<size_t> non_empty_indices;
  for (size_t index = 0; index < segments.size(); ++index) {
    if (!segments[index].centers.empty()) {
      non_empty_indices.push_back(index);
    }
  }
  if (non_empty_indices.size() <= max_indices || max_indices == 0) {
    return non_empty_indices;
  }

  std::set<size_t> selected;
  auto add_non_empty_position = [&](size_t source_index) {
    if (source_index < non_empty_indices.size()) {
      selected.insert(non_empty_indices[source_index]);
    }
  };

  add_non_empty_position(0);
  add_non_empty_position(non_empty_indices.size() / 2U);
  add_non_empty_position(non_empty_indices.size() - 1U);

  std::vector<size_t> by_length = non_empty_indices;
  std::sort(by_length.begin(), by_length.end(), [&](size_t lhs, size_t rhs) {
    if (segments[lhs].centers.size() != segments[rhs].centers.size()) {
      return segments[lhs].centers.size() > segments[rhs].centers.size();
    }
    return lhs < rhs;
  });
  for (const auto index : by_length) {
    if (selected.size() >= max_indices) {
      break;
    }
    selected.insert(index);
  }

  for (size_t slot = 0; selected.size() < max_indices; ++slot) {
    const size_t source_index =
      max_indices == 1
        ? 0
        : slot * (non_empty_indices.size() - 1U) / (max_indices - 1U);
    add_non_empty_position(source_index);
  }
  return {selected.begin(), selected.end()};
}

bool enables_lane_candidate_pruning(const std::string &effort) {
  return effort == "fast" || effort == "balanced";
}

size_t lane_candidate_limit_per_axis(const std::string &effort) {
  if (effort == "fast") {
    return 8;
  }
  if (effort == "balanced") {
    return 16;
  }
  return 0;
}

int candidate_distance_to_entry(
  const LanePlanCandidate &candidate, const std::optional<GridPose> &entry_pose,
  const CellModel &cell_model) {
  if (!entry_pose.has_value()) {
    return std::numeric_limits<int>::max();
  }
  int best = std::numeric_limits<int>::max();
  for (const auto &segment : candidate.segments) {
    if (segment.centers.empty()) {
      continue;
    }
    best = std::min(
      best,
      grid_distance(entry_pose->center, segment.centers.front(), cell_model));
    best = std::min(
      best,
      grid_distance(entry_pose->center, segment.centers.back(), cell_model));
  }
  return best;
}

void add_lane_candidate_index(
  std::set<size_t> &selected, size_t index, size_t limit) {
  if (selected.size() < limit) {
    selected.insert(index);
  }
}

std::vector<LanePlanCandidate> select_lane_candidate_representatives(
  const std::vector<LanePlanCandidate> &candidates,
  const std::optional<GridPose> &entry_pose, const CellModel &cell_model,
  const RobotPlanningConfig &config) {
  if (!enables_lane_candidate_pruning(config.planning_search_effort)) {
    return candidates;
  }
  const size_t per_axis_limit =
    lane_candidate_limit_per_axis(config.planning_search_effort);
  if (per_axis_limit == 0 || candidates.size() <= per_axis_limit * 2U) {
    return candidates;
  }

  std::vector<LanePlanCandidate> result;
  result.reserve(std::min(candidates.size(), per_axis_limit * 2U));
  for (const auto axis : {SweepAxis::BlockU, SweepAxis::BlockV}) {
    std::vector<size_t> axis_indices;
    for (size_t index = 0; index < candidates.size(); ++index) {
      if (candidates[index].sweep_axis == axis) {
        axis_indices.push_back(index);
      }
    }
    if (axis_indices.size() <= per_axis_limit) {
      for (const auto index : axis_indices) {
        result.push_back(candidates[index]);
      }
      continue;
    }

    std::set<size_t> selected;
    std::map<int, std::vector<size_t>, std::greater<int>> by_stride;
    for (const auto index : axis_indices) {
      by_stride[candidates[index].lane_stride].push_back(index);
    }

    for (auto &[stride, indices] : by_stride) {
      (void)stride;
      std::sort(indices.begin(), indices.end(), [&](size_t lhs, size_t rhs) {
        const auto lhs_entry_distance =
          candidate_distance_to_entry(candidates[lhs], entry_pose, cell_model);
        const auto rhs_entry_distance =
          candidate_distance_to_entry(candidates[rhs], entry_pose, cell_model);
        if (
          entry_pose.has_value() && lhs_entry_distance != rhs_entry_distance) {
          return lhs_entry_distance < rhs_entry_distance;
        }
        if (
          candidates[lhs].coverage_complete !=
          candidates[rhs].coverage_complete) {
          return candidates[lhs].coverage_complete &&
                 !candidates[rhs].coverage_complete;
        }
        if (candidates[lhs].cost != candidates[rhs].cost) {
          return candidates[lhs].cost < candidates[rhs].cost;
        }
        return candidates[lhs].lane_offset < candidates[rhs].lane_offset;
      });
      add_lane_candidate_index(selected, indices.front(), per_axis_limit);

      std::vector<size_t> by_offset = indices;
      std::sort(
        by_offset.begin(), by_offset.end(), [&](size_t lhs, size_t rhs) {
          return candidates[lhs].lane_offset < candidates[rhs].lane_offset;
        });
      add_lane_candidate_index(selected, by_offset.front(), per_axis_limit);
      add_lane_candidate_index(
        selected, by_offset[by_offset.size() / 2U], per_axis_limit);
      add_lane_candidate_index(selected, by_offset.back(), per_axis_limit);
    }

    std::vector<size_t> ranked = axis_indices;
    std::sort(ranked.begin(), ranked.end(), [&](size_t lhs, size_t rhs) {
      if (
        candidates[lhs].coverage_complete !=
        candidates[rhs].coverage_complete) {
        return candidates[lhs].coverage_complete &&
               !candidates[rhs].coverage_complete;
      }
      const auto lhs_entry_distance =
        candidate_distance_to_entry(candidates[lhs], entry_pose, cell_model);
      const auto rhs_entry_distance =
        candidate_distance_to_entry(candidates[rhs], entry_pose, cell_model);
      if (entry_pose.has_value() && lhs_entry_distance != rhs_entry_distance) {
        return lhs_entry_distance < rhs_entry_distance;
      }
      if (candidates[lhs].cost != candidates[rhs].cost) {
        return candidates[lhs].cost < candidates[rhs].cost;
      }
      if (candidates[lhs].lane_stride != candidates[rhs].lane_stride) {
        return candidates[lhs].lane_stride > candidates[rhs].lane_stride;
      }
      return candidates[lhs].lane_offset < candidates[rhs].lane_offset;
    });
    for (const auto index : ranked) {
      if (selected.size() >= per_axis_limit) {
        break;
      }
      selected.insert(index);
    }

    for (const auto index : selected) {
      result.push_back(candidates[index]);
    }
  }
  return result;
}

bool connected_candidate_less(
  const ConnectedCandidate &lhs, const ConnectedCandidate &rhs) {
  if (lhs.continuity_error_count != rhs.continuity_error_count) {
    return lhs.continuity_error_count < rhs.continuity_error_count;
  }
  if (lhs.coverage_complete != rhs.coverage_complete) {
    return lhs.coverage_complete && !rhs.coverage_complete;
  }
  if (
    lhs.candidate.sweep_axis == rhs.candidate.sweep_axis &&
    lhs.candidate.lane_stride != rhs.candidate.lane_stride &&
    lhs.coverage_ratio >= kMinGoodCoverageRatio &&
    rhs.coverage_ratio >= kMinGoodCoverageRatio) {
    return lhs.candidate.lane_stride > rhs.candidate.lane_stride;
  }
  if (lhs.covered_clean_center_count != rhs.covered_clean_center_count) {
    return lhs.covered_clean_center_count > rhs.covered_clean_center_count;
  }
  if (lhs.coverage_ratio != rhs.coverage_ratio) {
    return lhs.coverage_ratio > rhs.coverage_ratio;
  }
  if (lhs.skipped_segment_count != rhs.skipped_segment_count) {
    return lhs.skipped_segment_count < rhs.skipped_segment_count;
  }
  if (lhs.transition_turn_count != rhs.transition_turn_count) {
    return lhs.transition_turn_count < rhs.transition_turn_count;
  }
  if (lhs.transition_waypoint_count != rhs.transition_waypoint_count) {
    return lhs.transition_waypoint_count < rhs.transition_waypoint_count;
  }
  if (lhs.connected_segment_count != rhs.connected_segment_count) {
    return lhs.connected_segment_count > rhs.connected_segment_count;
  }
  return lhs.cost < rhs.cost;
}

void add_transition_waypoints(
  ConnectedCandidate &connected, const std::vector<PathWaypoint> &waypoints) {
  connected.transition_waypoint_count += waypoints.size();
  for (const auto &waypoint : waypoints) {
    if (waypoint.type == WaypointType::TurnInPlace) {
      ++connected.transition_turn_count;
    }
  }
  connected.waypoints.insert(
    connected.waypoints.end(), waypoints.begin(), waypoints.end());
}

void finalize_connected_candidate(
  ConnectedCandidate &connected, const LanePlanCandidate &candidate,
  const CellModel &cell_model, const CoverageReference &coverage_reference) {
  connected.total_clean_center_count   = coverage_reference.centers.size();
  connected.covered_clean_center_count = count_geometric_covered_centers(
    connected.waypoints, coverage_reference, candidate.sweep_axis, cell_model);
  connected.coverage_ratio =
    connected.total_clean_center_count == 0
      ? 0.0
      : static_cast<double>(connected.covered_clean_center_count) /
          static_cast<double>(connected.total_clean_center_count);
  connected.continuity_error_count = validate_path_continuity(
    connected.waypoints, cell_model, connected.connection_errors);
  connected.coverage_complete =
    candidate.coverage_complete && connected.skipped_segment_count == 0 &&
    connected.covered_clean_center_count >=
      connected.total_clean_center_count &&
    connected.continuity_error_count == 0 &&
    connected.connection_errors.size() == candidate.unreachable_segments.size();
  const auto missed_clean_center_count =
    connected.total_clean_center_count > connected.covered_clean_center_count
      ? connected.total_clean_center_count -
          connected.covered_clean_center_count
      : 0U;
  const double continuity_penalty =
    static_cast<double>(connected.continuity_error_count) * 10000.0;
  const double missed_penalty =
    static_cast<double>(missed_clean_center_count) * 1000.0;
  const double ratio_penalty = (1.0 - connected.coverage_ratio) * 5000.0;
  const double turn_penalty =
    static_cast<double>(connected.transition_turn_count) * 100.0;
  const double skipped_penalty =
    static_cast<double>(connected.skipped_segment_count) * 1000.0;
  const double unreachable_penalty =
    static_cast<double>(candidate.unreachable_segments.size()) * 1000.0;
  const double transition_penalty =
    static_cast<double>(connected.transition_waypoint_count);
  const double segment_penalty = static_cast<double>(candidate.segments.size());
  const double covered_bonus =
    static_cast<double>(connected.covered_clean_center_count) * 0.01;
  connected.cost = continuity_penalty + missed_penalty + ratio_penalty +
                   turn_penalty + skipped_penalty + unreachable_penalty +
                   transition_penalty + segment_penalty - covered_bonus;

  std::ostringstream stream;
  stream << std::fixed << std::setprecision(3)
         << "axis=" << to_string(candidate.sweep_axis)
         << " stride=" << candidate.lane_stride
         << " offset=" << candidate.lane_offset << " cost=" << connected.cost
         << " complete=" << (connected.coverage_complete ? "true" : "false")
         << " covered=" << connected.covered_clean_center_count << "/"
         << connected.total_clean_center_count
         << " ratio=" << connected.coverage_ratio
         << " turns=" << connected.transition_turn_count
         << " transition_wp=" << connected.transition_waypoint_count
         << " segments=" << candidate.segments.size()
         << " connected_segments=" << connected.connected_segment_count
         << " skipped_segments=" << connected.skipped_segment_count
         << " continuity_errors=" << connected.continuity_error_count
         << " unreachable=" << candidate.unreachable_segments.size()
         << " penalties{continuity=" << continuity_penalty
         << ",missed=" << missed_penalty << ",ratio=" << ratio_penalty
         << ",turn=" << turn_penalty << ",skipped=" << skipped_penalty
         << ",unreachable=" << unreachable_penalty
         << ",transition=" << transition_penalty
         << ",segment=" << segment_penalty << ",covered_bonus=-"
         << covered_bonus << "}";
  connected.score_breakdown = stream.str();
}

ConnectedCandidate build_connected_trial(
  const LanePlanCandidate &candidate, const CenterGrid &center_grid,
  const CellModel &cell_model, size_t start_index,
  const LaneSegment &start_segment, const std::optional<GridPose> &entry_pose,
  const CoverageReference &coverage_reference,
  CachedMotionConnector &connector) {
  ConnectedCandidate connected;
  connected.candidate         = candidate;
  connected.connection_errors = candidate.unreachable_segments;

  std::vector<bool> used(candidate.segments.size(), false);
  used[start_index] = true;

  if (entry_pose.has_value()) {
    std::vector<PathWaypoint> entry_connection;
    std::string error;
    const GridPose start{start_segment.centers.front(), start_segment.heading};
    if (!connector.append_connection(
          *entry_pose, start, center_grid, cell_model, entry_connection,
          error)) {
      connected.connection_errors.push_back(describe_segment_error(
        start_segment, "entry connector failed: " + error));
      ++connected.skipped_segment_count;
      finalize_connected_candidate(
        connected, candidate, cell_model, coverage_reference);
      return connected;
    }
    add_transition_waypoints(connected, entry_connection);
  }

  append_clean_waypoints(
    start_segment, connected.waypoints, connected.covered_clean_center_count);
  ++connected.connected_segment_count;
  GridPose previous_pose{start_segment.centers.back(), start_segment.heading};
  std::optional<GridPose> failed_previous_pose;
  std::vector<std::string> failed_connection_errors;

  while (connected.connected_segment_count < candidate.segments.size()) {
    std::vector<std::string> debug_errors;
    const auto choice = choose_next_connected_segment(
      candidate.segments, used, previous_pose, connector, center_grid,
      cell_model, &debug_errors);
    if (!choice.has_value()) {
      failed_previous_pose     = previous_pose;
      failed_connection_errors = std::move(debug_errors);
      break;
    }

    used[choice->segment_index] = true;
    add_transition_waypoints(connected, choice->connection_waypoints);
    append_clean_waypoints(
      choice->segment, connected.waypoints,
      connected.covered_clean_center_count);
    ++connected.connected_segment_count;
    previous_pose =
      GridPose{choice->segment.centers.back(), choice->segment.heading};
  }

  for (size_t index = 0; index < candidate.segments.size(); ++index) {
    if (used[index]) {
      continue;
    }
    ++connected.skipped_segment_count;
    std::string reason =
      "no reachable L-shaped connector from selected component";
    if (failed_previous_pose.has_value()) {
      reason += "; current_pose=" + pose_to_string(*failed_previous_pose);
    }
    reason +=
      "; skipped=" + segment_to_string(index, candidate.segments[index]);
    if (!failed_connection_errors.empty()) {
      reason += "; sampled_failures=";
      for (size_t error_index = 0;
           error_index < failed_connection_errors.size(); ++error_index) {
        if (error_index > 0) {
          reason += " | ";
        }
        reason += failed_connection_errors[error_index];
      }
    }
    connected.connection_errors.push_back(
      describe_segment_error(candidate.segments[index], reason));
  }

  finalize_connected_candidate(
    connected, candidate, cell_model, coverage_reference);
  return connected;
}

ConnectedCandidate build_ordered_candidate(
  const LanePlanCandidate &candidate, const CenterGrid &center_grid,
  const CellModel &cell_model, const std::optional<GridPose> &entry_pose,
  bool reverse_order, const CoverageReference &coverage_reference,
  CachedMotionConnector &connector) {
  ConnectedCandidate connected;
  connected.candidate         = candidate;
  connected.connection_errors = candidate.unreachable_segments;

  std::vector<LaneSegment> ordered_segments = candidate.segments;
  if (reverse_order) {
    std::reverse(ordered_segments.begin(), ordered_segments.end());
    for (auto &segment : ordered_segments) {
      segment = reversed_segment(segment);
    }
  }

  std::optional<GridPose> previous_pose = entry_pose;
  for (const auto &raw_segment : ordered_segments) {
    if (raw_segment.centers.empty()) {
      continue;
    }

    LaneSegment segment = raw_segment;
    std::vector<PathWaypoint> connection_waypoints;
    if (previous_pose.has_value()) {
      const auto reversed = reversed_segment(raw_segment);
      const std::array<LaneSegment, 2> options{raw_segment, reversed};
      std::optional<LaneSegment> best;
      size_t best_size  = std::numeric_limits<size_t>::max();
      int best_distance = std::numeric_limits<int>::max();
      std::vector<PathWaypoint> best_waypoints;
      for (const auto &option : options) {
        std::vector<PathWaypoint> trial;
        std::string error;
        const GridPose start{option.centers.front(), option.heading};
        if (!connector.append_connection(
              *previous_pose, start, center_grid, cell_model, trial, error)) {
          if (
            connected.connection_errors.size() <
            candidate.unreachable_segments.size() + 30U) {
            connected.connection_errors.push_back(describe_segment_error(
              raw_segment, "ordered connector failed from " +
                             pose_to_string(*previous_pose) + " to " +
                             pose_to_string(start) + ": " + error));
          }
          continue;
        }
        const auto distance = grid_distance(
          previous_pose->center, option.centers.front(), cell_model);
        if (
          !best.has_value() || trial.size() < best_size ||
          (trial.size() == best_size && distance < best_distance)) {
          best           = option;
          best_size      = trial.size();
          best_distance  = distance;
          best_waypoints = std::move(trial);
        }
      }
      if (!best.has_value()) {
        ++connected.skipped_segment_count;
        connected.connection_errors.push_back(
          describe_segment_error(raw_segment, "ordered connector failed"));
        continue;
      }
      segment = *best;
      add_transition_waypoints(connected, best_waypoints);
    }

    append_clean_waypoints(
      segment, connected.waypoints, connected.covered_clean_center_count);
    ++connected.connected_segment_count;
    previous_pose = GridPose{segment.centers.back(), segment.heading};
  }

  finalize_connected_candidate(
    connected, candidate, cell_model, coverage_reference);
  return connected;
}

BlockCoverageCandidate to_block_candidate(
  const ConnectedCandidate &connected, uint32_t block_id, uint32_t candidate_id,
  const std::optional<GridPose> &entry_pose) {
  BlockCoverageCandidate result;
  result.block_id                   = block_id;
  result.candidate_id               = candidate_id;
  result.sweep_axis                 = connected.candidate.sweep_axis;
  result.lane_stride                = connected.candidate.lane_stride;
  result.lane_offset                = connected.candidate.lane_offset;
  result.waypoints                  = connected.waypoints;
  result.coverage_complete          = connected.coverage_complete;
  result.total_cost                 = connected.cost;
  result.covered_clean_center_count = connected.covered_clean_center_count;
  result.total_clean_center_count   = connected.total_clean_center_count;
  result.transition_waypoint_count  = connected.transition_waypoint_count;
  result.transition_turn_count      = connected.transition_turn_count;
  result.continuity_error_count     = connected.continuity_error_count;
  result.score_breakdown            = connected.score_breakdown;
  result.invalid_reasons            = connected.candidate.invalid_reasons;
  result.unreachable_segments       = connected.connection_errors;

  const auto first_with_center = std::find_if(
    result.waypoints.begin(), result.waypoints.end(), [](const auto &waypoint) {
      return waypoint.center_inner_cell.has_value() &&
             waypoint.heading.has_value();
    });
  const auto last_with_center = std::find_if(
    result.waypoints.rbegin(), result.waypoints.rend(),
    [](const auto &waypoint) {
      return waypoint.center_inner_cell.has_value() &&
             waypoint.heading.has_value();
    });
  if (entry_pose.has_value()) {
    result.entry_pose = *entry_pose;
  } else if (first_with_center != result.waypoints.end()) {
    result.entry_pose = GridPose{
      *first_with_center->center_inner_cell, *first_with_center->heading};
  }
  if (last_with_center != result.waypoints.rend()) {
    result.exit_pose = GridPose{
      *last_with_center->center_inner_cell, *last_with_center->heading};
  }
  return result;
}

int total_inner_rows(const Block &block, const CellModel &cell_model) {
  return block.rows * cell_model.inner_rows;
}

int total_inner_cols(const Block &block, const CellModel &cell_model) {
  return block.cols * cell_model.inner_cols;
}

}  // namespace

std::vector<BlockCoverageCandidate>
BlockCoverageCandidateGenerator::make_free_start_candidates(
  const PvMap &map, const MapRepository &repository, const Block &block,
  const CenterGrid &center_grid, const RobotPlanningConfig &config,
  size_t max_candidates, size_t max_start_segments) const {
  return make_candidates_impl(
    map, repository, block, center_grid, config, std::nullopt, max_candidates,
    max_start_segments);
}

std::vector<BlockCoverageCandidate>
BlockCoverageCandidateGenerator::make_entry_constrained_candidates(
  const PvMap &map, const MapRepository &repository, const Block &block,
  const CenterGrid &center_grid, const RobotPlanningConfig &config,
  const GridPose &entry_pose, size_t max_candidates) const {
  return make_candidates_impl(
    map, repository, block, center_grid, config, entry_pose, max_candidates, 0);
}

std::vector<GridPose> BlockCoverageCandidateGenerator::make_corner_entry_poses(
  const PvMap &map, const Block &block, const CenterGrid &center_grid,
  size_t max_per_corner) const {
  std::vector<RankedPose> ranked;
  const std::array<Heading, 4> headings{
    Heading::BlockUPositive, Heading::BlockUNegative, Heading::BlockVPositive,
    Heading::BlockVNegative};
  const int rows = total_inner_rows(block, map.cell_model);
  const int cols = total_inner_cols(block, map.cell_model);
  const std::array<std::pair<int, int>, 4> corners{
    std::pair<int, int>{0, 0}, std::pair<int, int>{0, cols - 1},
    std::pair<int, int>{rows - 1, 0}, std::pair<int, int>{rows - 1, cols - 1}};

  for (int cell_row = 0; cell_row < block.rows; ++cell_row) {
    for (int cell_col = 0; cell_col < block.cols; ++cell_col) {
      for (int inner_row = 0; inner_row < map.cell_model.inner_rows;
           ++inner_row) {
        for (int inner_col = 0; inner_col < map.cell_model.inner_cols;
             ++inner_col) {
          const CenterInnerCell center{
            block.block_id, cell_row, cell_col, inner_row, inner_col};
          const int row = global_row(center, map.cell_model);
          const int col = global_col(center, map.cell_model);
          for (const auto heading : headings) {
            if (!center_grid.is_traversable(center, heading)) {
              continue;
            }
            for (size_t corner_index = 0; corner_index < corners.size();
                 ++corner_index) {
              const int distance = std::abs(row - corners[corner_index].first) +
                                   std::abs(col - corners[corner_index].second);
              ranked.push_back(RankedPose{
                GridPose{center, heading}, static_cast<int>(corner_index),
                distance});
            }
          }
        }
      }
    }
  }

  std::sort(ranked.begin(), ranked.end(), [](const auto &lhs, const auto &rhs) {
    if (lhs.corner_index != rhs.corner_index) {
      return lhs.corner_index < rhs.corner_index;
    }
    if (lhs.distance != rhs.distance) {
      return lhs.distance < rhs.distance;
    }
    return static_cast<uint8_t>(lhs.pose.heading) <
           static_cast<uint8_t>(rhs.pose.heading);
  });

  std::vector<GridPose> poses;
  std::array<size_t, 4> counts{0, 0, 0, 0};
  for (const auto &item : ranked) {
    auto &count = counts[static_cast<size_t>(item.corner_index)];
    if (count >= max_per_corner) {
      continue;
    }
    const auto duplicate =
      std::find_if(poses.begin(), poses.end(), [&](const auto &pose) {
        return same_pose(pose, item.pose);
      });
    if (duplicate != poses.end()) {
      continue;
    }
    poses.push_back(item.pose);
    ++count;
  }
  return poses;
}

bool BlockCoverageCandidateGenerator::candidate_less(
  const BlockCoverageCandidate &lhs, const BlockCoverageCandidate &rhs) {
  if (lhs.continuity_error_count != rhs.continuity_error_count) {
    return lhs.continuity_error_count < rhs.continuity_error_count;
  }
  if (lhs.coverage_complete != rhs.coverage_complete) {
    return lhs.coverage_complete && !rhs.coverage_complete;
  }
  const double lhs_ratio =
    lhs.total_clean_center_count == 0
      ? 0.0
      : static_cast<double>(lhs.covered_clean_center_count) /
          static_cast<double>(lhs.total_clean_center_count);
  const double rhs_ratio =
    rhs.total_clean_center_count == 0
      ? 0.0
      : static_cast<double>(rhs.covered_clean_center_count) /
          static_cast<double>(rhs.total_clean_center_count);
  if (
    lhs.sweep_axis == rhs.sweep_axis && lhs.lane_stride != rhs.lane_stride &&
    lhs_ratio >= kMinGoodCoverageRatio && rhs_ratio >= kMinGoodCoverageRatio) {
    return lhs.lane_stride > rhs.lane_stride;
  }
  if (lhs.covered_clean_center_count != rhs.covered_clean_center_count) {
    return lhs.covered_clean_center_count > rhs.covered_clean_center_count;
  }
  if (lhs_ratio != rhs_ratio) {
    return lhs_ratio > rhs_ratio;
  }
  if (lhs.transition_turn_count != rhs.transition_turn_count) {
    return lhs.transition_turn_count < rhs.transition_turn_count;
  }
  if (lhs.transition_waypoint_count != rhs.transition_waypoint_count) {
    return lhs.transition_waypoint_count < rhs.transition_waypoint_count;
  }
  return lhs.total_cost < rhs.total_cost;
}

std::vector<BlockCoverageCandidate>
BlockCoverageCandidateGenerator::make_candidates_impl(
  const PvMap &map, const MapRepository &repository, const Block &block,
  const CenterGrid &center_grid, const RobotPlanningConfig &config,
  const std::optional<GridPose> &entry_pose, size_t max_candidates,
  size_t max_start_segments) const {
  const LanePlanner lane_planner;
  const auto all_lane_candidates =
    lane_planner.make_candidates(map, repository, block, center_grid, config);
  auto lane_candidates = select_lane_candidate_representatives(
    all_lane_candidates, entry_pose, map.cell_model, config);
  std::vector<ConnectedCandidate> connected_candidates;

  const auto stats =
    map_geometry::estimate_block_inner_cell_size_stats(map, repository, block);
  const auto reference_centers = collect_reference_centers(all_lane_candidates);
  const auto block_u_reference = make_coverage_reference(
    reference_centers, SweepAxis::BlockU, map.cell_model,
    full_coverage_cross_radius_cells(SweepAxis::BlockU, stats, config));
  const auto block_v_reference = make_coverage_reference(
    reference_centers, SweepAxis::BlockV, map.cell_model,
    full_coverage_cross_radius_cells(SweepAxis::BlockV, stats, config));

  CachedMotionConnector connector;
  for (auto &candidate : lane_candidates) {
    const auto &coverage_reference = candidate.sweep_axis == SweepAxis::BlockU
                                       ? block_u_reference
                                       : block_v_reference;
    connected_candidates.push_back(build_ordered_candidate(
      candidate, center_grid, map.cell_model, entry_pose, false,
      coverage_reference, connector));
    connected_candidates.push_back(build_ordered_candidate(
      candidate, center_grid, map.cell_model, entry_pose, true,
      coverage_reference, connector));
    if (entry_pose.has_value()) {
      continue;
    }
    for (const auto index : representative_segment_indices(
           candidate.segments, max_start_segments)) {
      const auto reversed = reversed_segment(candidate.segments[index]);
      const std::array<LaneSegment, 2> starts{
        candidate.segments[index], reversed};
      for (const auto &start : starts) {
        connected_candidates.push_back(build_connected_trial(
          candidate, center_grid, map.cell_model, index, start, entry_pose,
          coverage_reference, connector));
      }
    }
  }

  std::sort(
    connected_candidates.begin(), connected_candidates.end(),
    connected_candidate_less);

  std::vector<BlockCoverageCandidate> result;
  result.reserve(std::min(max_candidates, connected_candidates.size()));
  uint32_t candidate_id = 0;
  for (const auto &connected : connected_candidates) {
    auto block_candidate =
      to_block_candidate(connected, block.block_id, candidate_id++, entry_pose);
    if (block_candidate.waypoints.empty()) {
      continue;
    }
    result.push_back(std::move(block_candidate));
    if (result.size() >= max_candidates) {
      break;
    }
  }

  if (config.debug_score_breakdown && !result.empty()) {
    for (const auto axis : {SweepAxis::BlockU, SweepAxis::BlockV}) {
      const auto best_axis_it = std::find_if(
        connected_candidates.begin(), connected_candidates.end(),
        [axis](const auto &candidate) {
          return candidate.candidate.sweep_axis == axis;
        });
      if (best_axis_it == connected_candidates.end()) {
        continue;
      }
      const std::string prefix = "best axis " + to_string(axis) + ": ";
      result.front().debug_score_breakdowns.push_back(
        prefix + best_axis_it->score_breakdown);
      for (size_t error_index = 0;
           error_index < best_axis_it->connection_errors.size() &&
           error_index < 8U;
           ++error_index) {
        result.front().debug_score_breakdowns.push_back(
          prefix + "issue: " + best_axis_it->connection_errors[error_index]);
      }
    }
  }
  return result;
}

}  // namespace map_planner
