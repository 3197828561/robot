#include "map_planner/planning/motion_connector.hpp"

#include <cmath>
#include <cstdlib>
#include <limits>
#include <optional>
#include <string>
#include <vector>

namespace map_planner {
namespace {

struct GlobalIndex {
  int row{};
  int col{};
};

GlobalIndex to_global(
  const CenterInnerCell &center, const CellModel &cell_model) {
  return {
    center.cell_row * cell_model.inner_rows + center.inner_row,
    center.cell_col * cell_model.inner_cols + center.inner_col};
}

CenterInnerCell from_global(
  uint32_t block_id, const CellModel &cell_model, const GlobalIndex &index) {
  return {
    block_id, index.row / cell_model.inner_rows,
    index.col / cell_model.inner_cols, index.row % cell_model.inner_rows,
    index.col % cell_model.inner_cols};
}

Heading heading_between(const GlobalIndex &from, const GlobalIndex &to) {
  if (to.col > from.col) {
    return Heading::BlockUPositive;
  }
  if (to.col < from.col) {
    return Heading::BlockUNegative;
  }
  if (to.row > from.row) {
    return Heading::BlockVPositive;
  }
  return Heading::BlockVNegative;
}

std::string center_string(const CenterInnerCell &center) {
  return "block=" + std::to_string(center.block_id) + " cell=(" +
         std::to_string(center.cell_row) + "," +
         std::to_string(center.cell_col) + ") inner=(" +
         std::to_string(center.inner_row) + "," +
         std::to_string(center.inner_col) + ")";
}

std::string status_string(TraversabilityStatus status) {
  switch (status) {
    case TraversabilityStatus::Free:
      return "Free";
    case TraversabilityStatus::BlockedMissingCell:
      return "BlockedMissingCell";
    case TraversabilityStatus::BlockedBoundary:
      return "BlockedBoundary";
    case TraversabilityStatus::BlockedMissingInflation:
      return "BlockedMissingInflation";
    case TraversabilityStatus::BlockedBridgeEdge:
      return "BlockedBridgeEdge";
    case TraversabilityStatus::BlockedObstacle:
      return "BlockedObstacle";
    case TraversabilityStatus::Unknown:
      return "Unknown";
  }
  return "Unknown";
}

std::vector<CenterInnerCell> make_straight_path(
  uint32_t block_id, const CellModel &cell_model, const GlobalIndex &from,
  const GlobalIndex &to) {
  std::vector<CenterInnerCell> path;
  if (from.row == to.row) {
    const int step = to.col >= from.col ? 1 : -1;
    for (int col = from.col + step; col != to.col + step; col += step) {
      path.push_back(from_global(block_id, cell_model, {from.row, col}));
    }
  } else if (from.col == to.col) {
    const int step = to.row >= from.row ? 1 : -1;
    for (int row = from.row + step; row != to.row + step; row += step) {
      path.push_back(from_global(block_id, cell_model, {row, from.col}));
    }
  }
  return path;
}

bool append_straight_path(
  const CenterInnerCell &from, const GlobalIndex &from_index,
  const GlobalIndex &to_index, const CellModel &cell_model,
  Heading &current_heading, const CenterGrid &center_grid,
  std::vector<PathWaypoint> &waypoints, std::string &error) {
  if (from_index.row == to_index.row && from_index.col == to_index.col) {
    return true;
  }
  if (from_index.row != to_index.row && from_index.col != to_index.col) {
    error = "straight path got non-straight indexes";
    return false;
  }

  const auto next_heading = heading_between(from_index, to_index);
  auto turn_center        = from_global(from.block_id, cell_model, from_index);
  const auto turn_status  = center_grid.status(turn_center, next_heading);
  if (turn_status != TraversabilityStatus::Free) {
    error = "turn center is not traversable for deadhead heading: " +
            center_string(turn_center) +
            " heading=" + std::to_string(static_cast<uint8_t>(next_heading)) +
            " status=" + status_string(turn_status);
    return false;
  }
  if (current_heading != next_heading) {
    waypoints.push_back(PathWaypoint{
      WaypointType::TurnInPlace, turn_center, current_heading, false,
      std::nullopt,
      rotation_between_headings_deg(current_heading, next_heading)});
    current_heading = next_heading;
  }

  const auto path =
    make_straight_path(from.block_id, cell_model, from_index, to_index);
  for (const auto &center : path) {
    const auto status = center_grid.status(center, next_heading);
    if (status != TraversabilityStatus::Free) {
      error =
        "deadhead path contains blocked center: " + center_string(center) +
        " heading=" + std::to_string(static_cast<uint8_t>(next_heading)) +
        " status=" + status_string(status);
      return false;
    }
    waypoints.push_back(PathWaypoint{
      WaypointType::Deadhead, center, next_heading, false, std::nullopt});
  }
  return true;
}

size_t turn_count(const std::vector<PathWaypoint> &waypoints) {
  size_t count = 0;
  for (const auto &waypoint : waypoints) {
    if (waypoint.type == WaypointType::TurnInPlace) {
      ++count;
    }
  }
  return count;
}

bool make_l_connection_trial(
  const GridPose &from, const GridPose &to, const CellModel &cell_model,
  const CenterGrid &center_grid, const GlobalIndex &from_index,
  const GlobalIndex &corner_index, const GlobalIndex &to_index,
  std::vector<PathWaypoint> &trial, std::string &error) {
  Heading trial_heading = from.heading;
  if (
    !append_straight_path(
      from.center, from_index, corner_index, cell_model, trial_heading,
      center_grid, trial, error) ||
    !append_straight_path(
      from.center, corner_index, to_index, cell_model, trial_heading,
      center_grid, trial, error)) {
    return false;
  }
  if (trial_heading != to.heading) {
    if (!center_grid.is_traversable(to.center, to.heading)) {
      error = "target is not traversable for final heading";
      return false;
    }
    trial.push_back(PathWaypoint{
      WaypointType::TurnInPlace, to.center, trial_heading, false, std::nullopt,
      rotation_between_headings_deg(trial_heading, to.heading)});
  }
  return true;
}

}  // namespace

bool MotionConnector::same_position(
  const CenterInnerCell &a, const CenterInnerCell &b) {
  return a.block_id == b.block_id && a.cell_row == b.cell_row &&
         a.cell_col == b.cell_col && a.inner_row == b.inner_row &&
         a.inner_col == b.inner_col;
}

Heading MotionConnector::connection_heading(
  const CenterInnerCell &from, const CenterInnerCell &to, bool &valid) {
  valid = true;
  if (from.block_id != to.block_id) {
    valid = false;
    return Heading::BlockUPositive;
  }
  if (from.cell_row == to.cell_row && from.inner_row == to.inner_row) {
    if (
      to.cell_col > from.cell_col ||
      (to.cell_col == from.cell_col && to.inner_col > from.inner_col)) {
      return Heading::BlockUPositive;
    }
    if (
      to.cell_col < from.cell_col ||
      (to.cell_col == from.cell_col && to.inner_col < from.inner_col)) {
      return Heading::BlockUNegative;
    }
  }
  if (from.cell_col == to.cell_col && from.inner_col == to.inner_col) {
    if (
      to.cell_row > from.cell_row ||
      (to.cell_row == from.cell_row && to.inner_row > from.inner_row)) {
      return Heading::BlockVPositive;
    }
    if (
      to.cell_row < from.cell_row ||
      (to.cell_row == from.cell_row && to.inner_row < from.inner_row)) {
      return Heading::BlockVNegative;
    }
  }
  valid = false;
  return Heading::BlockUPositive;
}

bool MotionConnector::append_connection(
  const GridPose &from, const GridPose &to, const CenterGrid &center_grid,
  const CellModel &cell_model, std::vector<PathWaypoint> &waypoints,
  std::string &error) const {
  if (from.center.block_id != to.center.block_id) {
    error = "cross-block connection is not supported in MotionConnector";
    return false;
  }
  if (
    !center_grid.is_traversable(from.center, from.heading) ||
    !center_grid.is_traversable(to.center, to.heading)) {
    error = "turn endpoint is not traversable";
    return false;
  }
  if (same_position(from.center, to.center)) {
    if (from.heading != to.heading) {
      waypoints.push_back(PathWaypoint{
        WaypointType::TurnInPlace, to.center, from.heading, false, std::nullopt,
        rotation_between_headings_deg(from.heading, to.heading)});
    }
    return true;
  }

  const auto from_index = to_global(from.center, cell_model);
  const auto to_index   = to_global(to.center, cell_model);
  const std::vector<GlobalIndex> corners{
    {from_index.row, to_index.col}, {to_index.row, from_index.col}};

  std::vector<PathWaypoint> best_trial;
  std::string last_error;
  bool found        = false;
  size_t best_turns = std::numeric_limits<size_t>::max();
  size_t best_size  = std::numeric_limits<size_t>::max();

  for (const auto &corner : corners) {
    std::vector<PathWaypoint> trial;
    std::string trial_error;
    if (!make_l_connection_trial(
          from, to, cell_model, center_grid, from_index, corner, to_index,
          trial, trial_error)) {
      last_error = trial_error;
      continue;
    }
    const auto turns = turn_count(trial);
    if (
      !found || turns < best_turns ||
      (turns == best_turns && trial.size() < best_size)) {
      found      = true;
      best_turns = turns;
      best_size  = trial.size();
      best_trial = std::move(trial);
    }
  }

  if (!found) {
    error = last_error.empty() ? "no orthogonal deadhead connection found"
                               : last_error;
    return false;
  }

  waypoints.insert(waypoints.end(), best_trial.begin(), best_trial.end());
  return true;
}

}  // namespace map_planner
