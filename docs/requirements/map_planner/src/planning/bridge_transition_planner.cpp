#include "map_planner/planning/bridge_transition_planner.hpp"

#include <algorithm>
#include <cmath>
#include <string>

namespace map_planner {
namespace {

Heading approach_heading_for_edge(const std::string &edge) {
  if (edge == "u_min") {
    return Heading::BlockUNegative;
  }
  if (edge == "u_max") {
    return Heading::BlockUPositive;
  }
  if (edge == "v_min") {
    return Heading::BlockVNegative;
  }
  if (edge == "v_max") {
    return Heading::BlockVPositive;
  }
  return Heading::BlockUPositive;
}

CenterInnerCell bridge_edge_center_for_endpoint(
  const BridgeEndpoint &endpoint, const CellModel &cell_model) {
  CenterInnerCell center{
    endpoint.block_id, endpoint.cell_row, endpoint.cell_col, endpoint.inner_row,
    endpoint.inner_col};
  if (endpoint.edge == "u_min") {
    center.inner_col = 0;
  } else if (endpoint.edge == "u_max") {
    center.inner_col = cell_model.inner_cols - 1;
  } else if (endpoint.edge == "v_min") {
    center.inner_row = 0;
  } else if (endpoint.edge == "v_max") {
    center.inner_row = cell_model.inner_rows - 1;
  }
  return center;
}

bool can_use_bridge_edge_exception(
  const CenterGrid &center_grid, const CenterInnerCell &center,
  Heading heading) {
  const auto status = center_grid.status(center, heading);
  return status == TraversabilityStatus::Free ||
         status == TraversabilityStatus::BlockedBoundary;
}

std::optional<GridPose> find_staging_pose(
  const BridgeEndpoint &endpoint, const Block &block,
  const CellModel &cell_model, const CenterGrid &center_grid, Heading heading) {
  const auto edge_center =
    bridge_edge_center_for_endpoint(endpoint, cell_model);
  const int edge_global_row =
    edge_center.cell_row * cell_model.inner_rows + edge_center.inner_row;
  const int edge_global_col =
    edge_center.cell_col * cell_model.inner_cols + edge_center.inner_col;
  const int total_rows = block.rows * cell_model.inner_rows;
  const int total_cols = block.cols * cell_model.inner_cols;
  const int max_steps  = endpoint.edge == "u_min" || endpoint.edge == "u_max"
                           ? total_cols
                           : total_rows;

  for (int step = 1; step < max_steps; ++step) {
    int global_row = edge_global_row;
    int global_col = edge_global_col;
    if (endpoint.edge == "u_min") {
      global_col = edge_global_col + step;
    } else if (endpoint.edge == "u_max") {
      global_col = edge_global_col - step;
    } else if (endpoint.edge == "v_min") {
      global_row = edge_global_row + step;
    } else if (endpoint.edge == "v_max") {
      global_row = edge_global_row - step;
    }

    if (
      global_row < 0 || global_col < 0 || global_row >= total_rows ||
      global_col >= total_cols) {
      break;
    }

    const CenterInnerCell candidate{
      endpoint.block_id, global_row / cell_model.inner_rows,
      global_col / cell_model.inner_cols, global_row % cell_model.inner_rows,
      global_col % cell_model.inner_cols};
    if (center_grid.is_traversable(candidate, heading)) {
      return GridPose{candidate, heading};
    }
  }
  return std::nullopt;
}

double reference_length_cm(
  const Bridge &bridge, const std::vector<BridgeSideTransition> &sides) {
  if (bridge.centerline.size() >= 2) {
    double length = 0.0;
    for (size_t index = 1; index < bridge.centerline.size(); ++index) {
      length += map_geometry::distance_cm(
        bridge.centerline[index - 1], bridge.centerline[index]);
    }
    return length;
  }
  if (sides.size() == 2) {
    return map_geometry::distance_cm(
      sides[0].anchor_point, sides[1].anchor_point);
  }
  return 0.0;
}

}  // namespace

std::vector<BridgeTransition> BridgeTransitionPlanner::make_transitions(
  const PvMap &map, const MapRepository &repository,
  const CenterGrid &center_grid, const RobotPlanningConfig &config) const {
  std::vector<BridgeTransition> transitions;

  for (const auto &bridge : map.bridges) {
    BridgeTransition transition;
    transition.bridge_id = bridge.bridge_id;
    transition.usable    = true;
    transition.message   = "ok";
    if (bridge.endpoints.size() != 2) {
      transition.usable  = false;
      transition.message = "bridge must have exactly two endpoints";
    }

    for (const auto &endpoint : bridge.endpoints) {
      BridgeSideTransition side;
      side.bridge_id        = bridge.bridge_id;
      side.block_id         = endpoint.block_id;
      side.endpoint         = endpoint;
      side.approach_heading = approach_heading_for_edge(endpoint.edge);
      side.bridge_edge_center =
        bridge_edge_center_for_endpoint(endpoint, map.cell_model);
      side.safe_center = side.bridge_edge_center;
      side.bridge_edge_pose =
        GridPose{side.bridge_edge_center, side.approach_heading};

      const auto *block = repository.find_block(endpoint.block_id);
      const auto *cell  = repository.find_cell(
        endpoint.block_id, endpoint.cell_row, endpoint.cell_col);
      if (block == nullptr) {
        side.message      = "endpoint block does not exist";
        transition.usable = false;
      } else if (
        cell == nullptr ||
        !repository.is_cell_present(
          endpoint.block_id, endpoint.cell_row, endpoint.cell_col)) {
        side.message      = "endpoint cell does not exist or is missing";
        transition.usable = false;
      } else {
        try {
          side.anchor_point = map_geometry::derive_bridge_endpoint_anchor(
            *cell, endpoint, map.cell_model.inner_rows,
            map.cell_model.inner_cols);
          side.uses_bridge_edge_exception =
            center_grid.status(
              side.bridge_edge_center, side.approach_heading) ==
            TraversabilityStatus::BlockedBoundary;
          if (!can_use_bridge_edge_exception(
                center_grid, side.bridge_edge_center, side.approach_heading)) {
            side.message =
              "bridge edge center is blocked by non-boundary reason";
            transition.usable = false;
          } else {
            side.staging_pose = find_staging_pose(
              endpoint, *block, map.cell_model, center_grid,
              side.approach_heading);
            side.usable  = true;
            side.message = "ok";
          }
        } catch (const std::exception &ex) {
          side.message      = ex.what();
          transition.usable = false;
        }
      }
      transition.sides.push_back(side);
    }

    if (
      transition.sides.size() == 2 &&
      transition.sides[0].block_id == transition.sides[1].block_id) {
      transition.usable  = false;
      transition.message = "bridge endpoints must reference different blocks";
    }
    if (!bridge.centerline.empty() && bridge.centerline.size() < 2) {
      transition.usable  = false;
      transition.message = "bridge centerline must have at least two points";
    }
    if (!bridge.polygon.empty() && bridge.polygon.size() < 3) {
      transition.usable  = false;
      transition.message = "bridge polygon must have at least three points";
    }

    transition.reference_length_cm =
      reference_length_cm(bridge, transition.sides);
    transition.cost = transition.reference_length_cm;
    if (transition.usable && transition.reference_length_cm <= 0.0) {
      transition.usable  = false;
      transition.message = "bridge reference length is zero";
    }
    if (transition.usable) {
      for (const auto &side : transition.sides) {
        if (!side.usable) {
          transition.usable  = false;
          transition.message = side.message;
          break;
        }
      }
    }

    transitions.push_back(std::move(transition));
  }

  return transitions;
}

}  // namespace map_planner
