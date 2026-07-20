#include "map_planner/planning/transit_utils.hpp"

#include <algorithm>
#include <cstdlib>

namespace map_planner {
namespace {

struct InnerIndex {
  int row{};
  int col{};
};

std::vector<CenterInnerCell> make_straight_centers(
  const CenterInnerCell &from, const CenterInnerCell &to,
  const CellModel &cell_model) {
  std::vector<CenterInnerCell> centers;
  const int from_row = global_row(from, cell_model);
  const int from_col = global_col(from, cell_model);
  const int to_row   = global_row(to, cell_model);
  const int to_col   = global_col(to, cell_model);
  if (from_row == to_row) {
    const int step = to_col >= from_col ? 1 : -1;
    for (int col = from_col + step; col != to_col + step; col += step) {
      centers.push_back(from_global(from.block_id, cell_model, from_row, col));
    }
  } else if (from_col == to_col) {
    const int step = to_row >= from_row ? 1 : -1;
    for (int row = from_row + step; row != to_row + step; row += step) {
      centers.push_back(from_global(from.block_id, cell_model, row, from_col));
    }
  }
  return centers;
}

InnerIndex to_inner_index(
  const CenterInnerCell &center, const CellModel &cell_model) {
  return {global_row(center, cell_model), global_col(center, cell_model)};
}

Heading heading_between_indices(const InnerIndex &from, const InnerIndex &to) {
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

bool bridge_edge_traversable(
  const CenterGrid &center_grid, const BridgeSideTransition &side,
  const CenterInnerCell &center, Heading heading) {
  const auto status = center_grid.status(center, heading);
  if (status == TraversabilityStatus::Free) {
    return true;
  }
  const bool bridge_heading =
    heading == side.approach_heading ||
    heading == opposite_heading(side.approach_heading);
  return status == TraversabilityStatus::BlockedBoundary && bridge_heading;
}

bool append_bridge_approach_sequence(
  std::vector<PathWaypoint> &waypoints, const GridPose &from,
  const BridgeSideTransition &side, const CenterGrid &center_grid,
  const CellModel &cell_model, std::string &error) {
  if (from.heading != side.approach_heading) {
    error = "source normal heading does not match bridge approach heading";
    return false;
  }

  const auto centers =
    make_straight_centers(from.center, side.bridge_edge_center, cell_model);
  if (centers.empty() && !same_center(from.center, side.bridge_edge_center)) {
    error = "bridge approach path is not straight";
    return false;
  }
  for (const auto &center : centers) {
    if (!bridge_edge_traversable(
          center_grid, side, center, side.approach_heading)) {
      error = "bridge approach path contains blocked center";
      return false;
    }
    PathWaypoint waypoint;
    waypoint.type              = WaypointType::ApproachBridge;
    waypoint.center_inner_cell = center;
    waypoint.heading           = side.approach_heading;
    waypoint.bridge_id         = side.bridge_id;
    append_unique(waypoints, waypoint);
  }
  if (
    centers.empty() || !same_center(centers.back(), side.bridge_edge_center)) {
    if (!bridge_edge_traversable(
          center_grid, side, side.bridge_edge_center, side.approach_heading)) {
      error = "bridge edge center is not traversable for approach";
      return false;
    }
    PathWaypoint waypoint;
    waypoint.type              = WaypointType::ApproachBridge;
    waypoint.center_inner_cell = side.bridge_edge_center;
    waypoint.heading           = side.approach_heading;
    waypoint.bridge_id         = side.bridge_id;
    append_unique(waypoints, waypoint);
  }
  return true;
}

bool append_bridge_departure_sequence(
  std::vector<PathWaypoint> &waypoints, const BridgeSideTransition &side,
  const GridPose &to, const CenterGrid &center_grid,
  const CellModel &cell_model, std::string &error) {
  const auto departure_heading = opposite_heading(side.approach_heading);
  if (to.heading != departure_heading) {
    error = "target normal heading does not match bridge departure heading";
    return false;
  }
  if (!bridge_edge_traversable(
        center_grid, side, side.bridge_edge_center, departure_heading)) {
    error = "bridge edge center is not traversable for departure";
    return false;
  }

  PathWaypoint reinit;
  reinit.type              = WaypointType::ReinitVision;
  reinit.center_inner_cell = side.bridge_edge_center;
  reinit.heading           = departure_heading;
  reinit.bridge_id         = side.bridge_id;
  append_unique(waypoints, reinit);

  const auto centers =
    make_straight_centers(side.bridge_edge_center, to.center, cell_model);
  if (centers.empty() && !same_center(side.bridge_edge_center, to.center)) {
    error = "bridge departure path is not straight";
    return false;
  }
  for (const auto &center : centers) {
    if (!bridge_edge_traversable(
          center_grid, side, center, departure_heading)) {
      error = "bridge departure path contains blocked center";
      return false;
    }
    PathWaypoint waypoint;
    waypoint.type              = WaypointType::Deadhead;
    waypoint.center_inner_cell = center;
    waypoint.heading           = departure_heading;
    waypoint.bridge_id         = side.bridge_id;
    append_unique(waypoints, waypoint);
  }
  return true;
}

}  // namespace

bool same_center(const CenterInnerCell &a, const CenterInnerCell &b) {
  return a.block_id == b.block_id && a.cell_row == b.cell_row &&
         a.cell_col == b.cell_col && a.inner_row == b.inner_row &&
         a.inner_col == b.inner_col;
}

bool same_pose(const GridPose &a, const GridPose &b) {
  return same_center(a.center, b.center) && a.heading == b.heading;
}

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

int global_row(const CenterInnerCell &center, const CellModel &cell_model) {
  return center.cell_row * cell_model.inner_rows + center.inner_row;
}

int global_col(const CenterInnerCell &center, const CellModel &cell_model) {
  return center.cell_col * cell_model.inner_cols + center.inner_col;
}

CenterInnerCell from_global(
  uint32_t block_id, const CellModel &cell_model, int row, int col) {
  return CenterInnerCell{
    block_id, row / cell_model.inner_rows, col / cell_model.inner_cols,
    row % cell_model.inner_rows, col % cell_model.inner_cols};
}

int manhattan_distance(
  const CenterInnerCell &a, const CenterInnerCell &b,
  const CellModel &cell_model) {
  return std::abs(global_row(a, cell_model) - global_row(b, cell_model)) +
         std::abs(global_col(a, cell_model) - global_col(b, cell_model));
}

size_t turn_count(const std::vector<PathWaypoint> &waypoints) {
  return static_cast<size_t>(
    std::count_if(waypoints.begin(), waypoints.end(), [](const auto &waypoint) {
      return waypoint.type == WaypointType::TurnInPlace;
    }));
}

bool append_unique(
  std::vector<PathWaypoint> &waypoints, const PathWaypoint &waypoint) {
  if (
    !waypoints.empty() && waypoints.back().center_inner_cell.has_value() &&
    waypoint.center_inner_cell.has_value() &&
    same_center(
      *waypoints.back().center_inner_cell, *waypoint.center_inner_cell) &&
    waypoints.back().heading == waypoint.heading &&
    waypoints.back().type == waypoint.type) {
    return false;
  }
  waypoints.push_back(waypoint);
  return true;
}

std::optional<GridPose> normal_pose_for_side(
  const BridgeSideTransition &side, bool departing_from_bridge) {
  if (side.staging_pose.has_value()) {
    auto pose = *side.staging_pose;
    if (departing_from_bridge) {
      pose.heading = opposite_heading(side.approach_heading);
    }
    return pose;
  }
  if (!side.uses_bridge_edge_exception) {
    auto pose = side.bridge_edge_pose;
    if (departing_from_bridge) {
      pose.heading = opposite_heading(side.approach_heading);
    }
    return pose;
  }
  return std::nullopt;
}

TransitionPlan build_bridge_transfer_plan(
  const GridPose &from_pose, const GridPose &to_pose,
  const BridgeTransition &bridge, const BridgeSideTransition &source_side,
  const BridgeSideTransition &target_side, const CenterGrid &center_grid,
  const CellModel &cell_model) {
  TransitionPlan plan;
  const auto source_normal = normal_pose_for_side(source_side, false);
  const auto target_normal = normal_pose_for_side(target_side, true);
  if (!source_normal.has_value()) {
    plan.message = "source bridge side has no normal staging pose";
    return plan;
  }
  if (!target_normal.has_value()) {
    plan.message = "target bridge side has no normal staging pose";
    return plan;
  }
  if (!same_pose(from_pose, *source_normal)) {
    plan.message = "bridge source pose is not the source normal";
    return plan;
  }
  if (!same_pose(to_pose, *target_normal)) {
    plan.message = "bridge target pose is not the target normal";
    return plan;
  }
  if (!center_grid.is_traversable(from_pose)) {
    plan.message = "source normal is not traversable";
    return plan;
  }
  if (!center_grid.is_traversable(to_pose)) {
    plan.message = "target normal is not traversable";
    return plan;
  }

  std::string error;
  if (!append_bridge_approach_sequence(
        plan.waypoints, from_pose, source_side, center_grid, cell_model,
        error)) {
    plan.message = "bridge approach failed: " + error;
    return plan;
  }

  PathWaypoint bridge_crossing;
  bridge_crossing.type      = WaypointType::BridgeCrossing;
  bridge_crossing.bridge_id = bridge.bridge_id;
  append_unique(plan.waypoints, bridge_crossing);

  if (!append_bridge_departure_sequence(
        plan.waypoints, target_side, to_pose, center_grid, cell_model, error)) {
    plan.message = "bridge departure failed: " + error;
    return plan;
  }

  plan.cost = static_cast<double>(plan.waypoints.size()) +
              static_cast<double>(turn_count(plan.waypoints)) * 10.0 +
              bridge.cost / 100.0;
  plan.success = true;
  plan.message = "ok";
  return plan;
}

}  // namespace map_planner
