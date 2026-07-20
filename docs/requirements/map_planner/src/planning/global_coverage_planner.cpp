#include "map_planner/planning/global_coverage_planner.hpp"

#include <algorithm>
#include <chrono>
#include <iostream>
#include <limits>
#include <optional>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "map_planner/planning/center_grid.hpp"
#include "map_planner/planning/motion_connector.hpp"
#include "map_planner/planning/transit_path_planner.hpp"
#include "map_planner/planning/transit_utils.hpp"

namespace map_planner {
namespace {

struct SearchEffortConfig {
  size_t max_candidates{};
  size_t max_start_segments{};
  size_t max_candidate_blocks{};
  size_t max_transit_candidates_per_block{};
};

struct CandidateChoice {
  size_t block_index{};
  size_t candidate_index{};
  PlanningResult transit;
  double score{std::numeric_limits<double>::infinity()};
  size_t turns{std::numeric_limits<size_t>::max()};
  bool found{false};
};

using SteadyClock = std::chrono::steady_clock;

double elapsed_ms(SteadyClock::time_point start) {
  return std::chrono::duration<double, std::milli>(SteadyClock::now() - start)
    .count();
}

std::string pose_string(const GridPose &pose) {
  const auto &center = pose.center;
  return "block=" + std::to_string(center.block_id) + " cell=(" +
         std::to_string(center.cell_row) + "," +
         std::to_string(center.cell_col) + ") inner=(" +
         std::to_string(center.inner_row) + "," +
         std::to_string(center.inner_col) +
         ") heading=" + std::to_string(static_cast<uint8_t>(pose.heading));
}

void debug_log(const RobotPlanningConfig &config, const std::string &message) {
  if (!config.debug_score_breakdown) {
    return;
  }
  std::cerr << "[coverage_timing] " << message << std::endl;
}

struct GlobalIndex {
  int row{};
  int col{};
};

GlobalIndex to_global(
  const CenterInnerCell &center, const CellModel &cell_model) {
  return {
    center.cell_row * static_cast<int>(cell_model.inner_rows) +
      center.inner_row,
    center.cell_col * static_cast<int>(cell_model.inner_cols) +
      center.inner_col};
}

Heading heading_between(const GlobalIndex &from, const GlobalIndex &to) {
  if (to.row == from.row) {
    return to.col >= from.col ? Heading::BlockVPositive
                              : Heading::BlockVNegative;
  }
  return to.row >= from.row ? Heading::BlockUPositive : Heading::BlockUNegative;
}

SearchEffortConfig search_effort_config(const std::string &effort) {
  if (effort == "fast") {
    return {8, 4, 2, 3};
  }
  if (effort == "balanced") {
    return {12, 6, 3, 4};
  }
  if (effort == "quality") {
    return {16, 8, 5, 6};
  }
  if (effort == "exhaustive") {
    return {32, 0, 0, 0};
  }
  return {12, 6, 3, 4};
}

bool contains_id(const std::vector<uint32_t> &ids, uint32_t id) {
  return std::find(ids.begin(), ids.end(), id) != ids.end();
}

std::string join_ids(const std::vector<uint32_t> &ids) {
  std::string text;
  for (size_t index = 0; index < ids.size(); ++index) {
    if (index > 0) {
      text += ",";
    }
    text += std::to_string(ids[index]);
  }
  return text;
}

bool heading_is_valid(Heading heading) {
  return static_cast<uint8_t>(heading) <=
         static_cast<uint8_t>(Heading::BlockVNegative);
}

bool center_exists(
  const MapRepository &repository, const CenterInnerCell &center) {
  const auto *block = repository.find_block(center.block_id);
  if (block == nullptr || !block->cleanable) {
    return false;
  }
  const auto &cell_model = repository.map().cell_model;
  if (
    center.cell_row < 0 || center.cell_col < 0 ||
    center.cell_row >= block->rows || center.cell_col >= block->cols ||
    center.inner_row < 0 || center.inner_col < 0 ||
    center.inner_row >= cell_model.inner_rows ||
    center.inner_col >= cell_model.inner_cols) {
    return false;
  }
  return repository.is_cell_present(
    center.block_id, center.cell_row, center.cell_col);
}

std::vector<uint32_t> unique_target_block_ids(
  const PvMap &map, const MapRepository &repository,
  const std::vector<uint32_t> &requested, PlanningResult &result) {
  std::vector<uint32_t> targets;
  if (requested.empty()) {
    for (const auto &block : map.blocks) {
      if (block.cleanable) {
        targets.push_back(block.block_id);
      }
    }
    return targets;
  }

  for (const auto block_id : requested) {
    if (contains_id(targets, block_id)) {
      continue;
    }
    const auto *block = repository.find_block(block_id);
    if (block == nullptr) {
      result.message =
        "target block_id " + std::to_string(block_id) + " does not exist";
      return {};
    }
    if (!block->cleanable) {
      result.message =
        "target block_id " + std::to_string(block_id) + " is not cleanable";
      return {};
    }
    targets.push_back(block_id);
  }
  return targets;
}

std::vector<uint32_t> all_cleanable_block_ids(const PvMap &map) {
  std::vector<uint32_t> ids;
  for (const auto &block : map.blocks) {
    if (block.cleanable) {
      ids.push_back(block.block_id);
    }
  }
  return ids;
}

PlanningResult make_direct_transit(const GridPose &pose) {
  PlanningResult result;
  result.success = true;
  result.message = "ok";
  PathWaypoint waypoint;
  waypoint.type              = WaypointType::Deadhead;
  waypoint.center_inner_cell = pose.center;
  waypoint.heading           = pose.heading;
  waypoint.brush_on          = false;
  result.waypoints.push_back(waypoint);
  result.debug.coverage_complete = true;
  result.debug.score_breakdown.push_back(
    "transit direct: already at coverage entry");
  return result;
}

PlanningResult make_transit(
  const MapRepository &repository, const PlanningRequest &request,
  const GridPose &from, const GridPose &to,
  std::vector<uint32_t> allowed_block_ids) {
  if (same_pose(from, to)) {
    return make_direct_transit(from);
  }

  TransitPlanningRequest transit_request;
  transit_request.map_id               = request.map_id;
  transit_request.map_version          = request.map_version;
  transit_request.start_pose           = from;
  transit_request.goal_pose            = to;
  transit_request.require_goal_heading = true;
  transit_request.allowed_block_ids    = std::move(allowed_block_ids);
  transit_request.config               = request.config;
  return TransitPathPlanner().plan(repository, transit_request);
}

std::vector<size_t> ordered_candidate_blocks(
  const PvMap &map, const GridPose &current_pose,
  const std::vector<const Block *> &blocks,
  const std::unordered_set<size_t> &visited, size_t max_blocks) {
  std::unordered_map<uint32_t, std::vector<uint32_t>> adjacency;
  for (const auto &bridge : map.bridges) {
    if (bridge.endpoints.size() != 2) {
      continue;
    }
    const auto first  = bridge.endpoints[0].block_id;
    const auto second = bridge.endpoints[1].block_id;
    adjacency[first].push_back(second);
    adjacency[second].push_back(first);
  }

  std::unordered_map<uint32_t, int> distance_by_block;
  std::vector<uint32_t> queue{current_pose.center.block_id};
  distance_by_block[current_pose.center.block_id] = 0;
  for (size_t head = 0; head < queue.size(); ++head) {
    const auto block_id     = queue[head];
    const auto distance     = distance_by_block[block_id];
    const auto adjacency_it = adjacency.find(block_id);
    if (adjacency_it == adjacency.end()) {
      continue;
    }
    for (const auto neighbor : adjacency_it->second) {
      if (distance_by_block.find(neighbor) != distance_by_block.end()) {
        continue;
      }
      distance_by_block[neighbor] = distance + 1;
      queue.push_back(neighbor);
    }
  }

  std::vector<size_t> ordered;
  for (size_t block_index = 0; block_index < blocks.size(); ++block_index) {
    if (visited.find(block_index) == visited.end()) {
      ordered.push_back(block_index);
    }
  }
  std::sort(ordered.begin(), ordered.end(), [&](size_t lhs, size_t rhs) {
    const auto lhs_distance_it = distance_by_block.find(blocks[lhs]->block_id);
    const auto rhs_distance_it = distance_by_block.find(blocks[rhs]->block_id);
    const auto lhs_distance    = lhs_distance_it == distance_by_block.end()
                                   ? std::numeric_limits<int>::max()
                                   : lhs_distance_it->second;
    const auto rhs_distance    = rhs_distance_it == distance_by_block.end()
                                   ? std::numeric_limits<int>::max()
                                   : rhs_distance_it->second;
    if (lhs_distance != rhs_distance) {
      return lhs_distance < rhs_distance;
    }
    return blocks[lhs]->block_id < blocks[rhs]->block_id;
  });
  if (max_blocks > 0 && ordered.size() > max_blocks) {
    ordered.resize(max_blocks);
  }
  return ordered;
}

std::unordered_map<uint32_t, std::vector<uint32_t>> bridge_block_adjacency(
  const PvMap &map) {
  std::unordered_map<uint32_t, std::vector<uint32_t>> adjacency;
  for (const auto &bridge : map.bridges) {
    if (bridge.endpoints.size() != 2) {
      continue;
    }
    const auto first  = bridge.endpoints[0].block_id;
    const auto second = bridge.endpoints[1].block_id;
    adjacency[first].push_back(second);
    adjacency[second].push_back(first);
  }
  return adjacency;
}

std::vector<uint32_t> bridge_block_path(
  const PvMap &map, uint32_t start_block_id, uint32_t goal_block_id) {
  if (start_block_id == goal_block_id) {
    return {start_block_id};
  }

  const auto adjacency = bridge_block_adjacency(map);
  std::vector<uint32_t> queue{start_block_id};
  std::unordered_map<uint32_t, uint32_t> previous;
  previous[start_block_id] = start_block_id;
  for (size_t head = 0; head < queue.size(); ++head) {
    const auto block_id     = queue[head];
    const auto adjacency_it = adjacency.find(block_id);
    if (adjacency_it == adjacency.end()) {
      continue;
    }
    for (const auto neighbor : adjacency_it->second) {
      if (previous.find(neighbor) != previous.end()) {
        continue;
      }
      previous[neighbor] = block_id;
      if (neighbor == goal_block_id) {
        std::vector<uint32_t> path;
        for (uint32_t id = goal_block_id;; id = previous[id]) {
          path.push_back(id);
          if (id == start_block_id) {
            break;
          }
        }
        std::reverse(path.begin(), path.end());
        return path;
      }
      queue.push_back(neighbor);
    }
  }
  return all_cleanable_block_ids(map);
}

size_t clean_waypoint_count(const std::vector<PathWaypoint> &waypoints) {
  return static_cast<size_t>(
    std::count_if(waypoints.begin(), waypoints.end(), [](const auto &waypoint) {
      return waypoint.type == WaypointType::Clean;
    }));
}

double coverage_ratio(const BlockCoverageCandidate &candidate) {
  if (candidate.total_clean_center_count == 0U) {
    return 0.0;
  }
  return static_cast<double>(candidate.covered_clean_center_count) /
         static_cast<double>(candidate.total_clean_center_count);
}

double greedy_candidate_score(
  const BlockCoverageCandidate &candidate, const PlanningResult &transit) {
  const double incomplete_penalty =
    candidate.coverage_complete ? 0.0 : 1000000000.0;
  const double missed_penalty =
    candidate.total_clean_center_count > candidate.covered_clean_center_count
      ? static_cast<double>(
          candidate.total_clean_center_count -
          candidate.covered_clean_center_count) *
          1000000.0
      : 0.0;
  return incomplete_penalty + missed_penalty + transit.debug.total_cost +
         candidate.total_cost;
}

void append_non_clean_transit(
  std::vector<PathWaypoint> &target, const std::vector<PathWaypoint> &source) {
  for (const auto &waypoint : source) {
    if (waypoint.type == WaypointType::Clean) {
      continue;
    }
    target.push_back(waypoint);
    target.back().brush_on = false;
  }
}

const BridgeTransition *find_bridge_transition(
  const std::vector<BridgeTransition> &transitions, uint32_t from_block_id,
  uint32_t to_block_id, size_t &source_side_index, size_t &target_side_index) {
  for (const auto &transition : transitions) {
    if (!transition.usable) {
      continue;
    }
    std::optional<size_t> source_index;
    std::optional<size_t> target_index;
    for (size_t side_index = 0; side_index < transition.sides.size();
         ++side_index) {
      if (transition.sides[side_index].block_id == from_block_id) {
        source_index = side_index;
      }
      if (transition.sides[side_index].block_id == to_block_id) {
        target_index = side_index;
      }
    }
    if (source_index.has_value() && target_index.has_value()) {
      source_side_index = *source_index;
      target_side_index = *target_index;
      return &transition;
    }
  }
  return nullptr;
}

bool bridge_departure_turn_allowed(
  const CenterGrid &center_grid, const CenterInnerCell &center, Heading heading,
  bool allow_boundary_turn) {
  const auto status = center_grid.status(center, heading);
  return status == TraversabilityStatus::Free ||
         (allow_boundary_turn &&
          status == TraversabilityStatus::BlockedBoundary);
}

bool append_bridge_departure_leg(
  uint32_t block_id, const GlobalIndex &from, const GlobalIndex &to,
  const CellModel &cell_model, const CenterGrid &center_grid,
  Heading &current_heading, bool allow_boundary_turn,
  std::vector<PathWaypoint> &waypoints, std::string &error) {
  if (from.row == to.row && from.col == to.col) {
    return true;
  }
  if (from.row != to.row && from.col != to.col) {
    error = "bridge departure leg is not straight";
    return false;
  }

  const auto next_heading = heading_between(from, to);
  const auto turn_center =
    from_global(block_id, cell_model, from.row, from.col);
  if (!bridge_departure_turn_allowed(
        center_grid, turn_center, next_heading, allow_boundary_turn)) {
    error = "bridge departure turn center is not traversable";
    return false;
  }
  if (current_heading != next_heading) {
    waypoints.push_back(PathWaypoint{
      WaypointType::TurnInPlace, turn_center, current_heading, false,
      std::nullopt,
      rotation_between_headings_deg(current_heading, next_heading)});
    current_heading = next_heading;
  }

  const int row_step = to.row == from.row ? 0 : (to.row > from.row ? 1 : -1);
  const int col_step = to.col == from.col ? 0 : (to.col > from.col ? 1 : -1);
  int row            = from.row + row_step;
  int col            = from.col + col_step;
  while (row != to.row + row_step || col != to.col + col_step) {
    const auto center = from_global(block_id, cell_model, row, col);
    if (!center_grid.is_traversable(center, next_heading)) {
      error = "bridge departure path contains blocked center";
      return false;
    }
    waypoints.push_back(PathWaypoint{
      WaypointType::Deadhead, center, next_heading, false, std::nullopt});
    row += row_step;
    col += col_step;
  }
  return true;
}

bool append_bridge_departure_connection(
  const GridPose &from, const GridPose &to, const CenterGrid &center_grid,
  const CellModel &cell_model, std::vector<PathWaypoint> &waypoints,
  std::string &error) {
  const auto from_index = to_global(from.center, cell_model);
  const auto to_index   = to_global(to.center, cell_model);
  const std::array<GlobalIndex, 2> corners{
    GlobalIndex{from_index.row, to_index.col},
    GlobalIndex{to_index.row, from_index.col}};

  bool found        = false;
  size_t best_turns = std::numeric_limits<size_t>::max();
  size_t best_size  = std::numeric_limits<size_t>::max();
  std::vector<PathWaypoint> best_waypoints;
  std::string last_error;
  for (const auto &corner : corners) {
    std::vector<PathWaypoint> trial;
    std::string trial_error;
    Heading current_heading = from.heading;
    if (
      !append_bridge_departure_leg(
        from.center.block_id, from_index, corner, cell_model, center_grid,
        current_heading, true, trial, trial_error) ||
      !append_bridge_departure_leg(
        from.center.block_id, corner, to_index, cell_model, center_grid,
        current_heading, false, trial, trial_error)) {
      last_error = trial_error;
      continue;
    }
    if (current_heading != to.heading) {
      if (!center_grid.is_traversable(to.center, to.heading)) {
        last_error =
          "bridge departure target is not traversable for final heading";
        continue;
      }
      trial.push_back(PathWaypoint{
        WaypointType::TurnInPlace, to.center, current_heading, false,
        std::nullopt,
        rotation_between_headings_deg(current_heading, to.heading)});
    }
    const auto turns = turn_count(trial);
    if (
      !found || turns < best_turns ||
      (turns == best_turns && trial.size() < best_size)) {
      found          = true;
      best_turns     = turns;
      best_size      = trial.size();
      best_waypoints = std::move(trial);
    }
  }
  if (!found) {
    error =
      last_error.empty() ? "no bridge departure connection found" : last_error;
    return false;
  }
  waypoints.insert(
    waypoints.end(), best_waypoints.begin(), best_waypoints.end());
  return true;
}

PlanningResult make_local_bridge_graph_transit(
  const PvMap &map, const CenterGrid &center_grid,
  const std::vector<BridgeTransition> &bridge_transitions, const GridPose &from,
  const GridPose &to) {
  PlanningResult result;
  result.message   = "ok";
  GridPose current = from;
  MotionConnector connector;
  std::string error;

  const auto block_path =
    bridge_block_path(map, from.center.block_id, to.center.block_id);
  if (block_path.empty()) {
    result.message = "no bridge block path";
    return result;
  }

  if (block_path.size() == 1U) {
    if (!connector.append_connection(
          current, to, center_grid, map.cell_model, result.waypoints, error)) {
      result.message = "local same-block transit failed: " + error;
      return result;
    }
    result.success          = true;
    result.debug.total_cost = static_cast<double>(result.waypoints.size());
    return result;
  }

  std::optional<GridPose> final_target_normal;
  for (size_t path_index = 0; path_index + 1U < block_path.size();
       ++path_index) {
    size_t source_side_index = 0;
    size_t target_side_index = 0;
    const auto *transition   = find_bridge_transition(
      bridge_transitions, block_path[path_index], block_path[path_index + 1U],
      source_side_index, target_side_index);
    if (transition == nullptr) {
      result.message = "no usable bridge transition between block " +
                       std::to_string(block_path[path_index]) + " and " +
                       std::to_string(block_path[path_index + 1U]);
      return result;
    }

    const auto &source_side  = transition->sides[source_side_index];
    const auto &target_side  = transition->sides[target_side_index];
    const auto source_normal = normal_pose_for_side(source_side, false);
    const auto target_normal = normal_pose_for_side(target_side, true);
    if (!source_normal.has_value() || !target_normal.has_value()) {
      result.message = "bridge transition has no normal anchor";
      return result;
    }

    error.clear();
    if (!connector.append_connection(
          current, *source_normal, center_grid, map.cell_model,
          result.waypoints, error)) {
      result.message = "local transit to bridge anchor failed: " + error;
      return result;
    }

    const auto bridge_plan = build_bridge_transfer_plan(
      *source_normal, *target_normal, *transition, source_side, target_side,
      center_grid, map.cell_model);
    if (!bridge_plan.success) {
      result.message = "local bridge transfer failed: " + bridge_plan.message;
      return result;
    }
    result.waypoints.insert(
      result.waypoints.end(), bridge_plan.waypoints.begin(),
      bridge_plan.waypoints.end());
    result.debug.total_cost += bridge_plan.cost;
    current             = *target_normal;
    final_target_normal = target_normal;
  }

  // After crossing bridges we are at the target block bridge anchor.
  // Deadhead to the candidate entry pose using bridge departure rules:
  // the first turn at the bridge anchor allows BlockedBoundary.
  if (final_target_normal.has_value()) {
    error.clear();
    if (!append_bridge_departure_connection(
          *final_target_normal, to, center_grid, map.cell_model,
          result.waypoints, error)) {
      result.message =
        "local deadhead from bridge anchor to entry pose failed: " + error;
      return result;
    }
  }
  result.success = true;
  result.debug.total_cost += static_cast<double>(result.waypoints.size());
  return result;
}

struct GreedyRouteStep {
  size_t block_index{};
  size_t candidate_index{};
  PlanningResult transit;
};

struct GreedyRoute {
  bool success{false};
  std::string message;
  std::vector<GreedyRouteStep> steps;
  std::vector<std::string> timing_logs;
};

GreedyRoute make_greedy_large_map_route(
  const MapRepository &repository, const PlanningRequest &request,
  const CenterGrid &center_grid,
  const std::vector<BridgeTransition> &bridge_transitions,
  const GridPose &start_pose, const std::vector<const Block *> &blocks,
  const std::vector<std::vector<BlockCoverageCandidate>> &candidates_by_block) {
  const auto route_start = SteadyClock::now();
  GreedyRoute route;
  std::unordered_set<size_t> visited;
  GridPose current_pose = start_pose;

  while (visited.size() < blocks.size()) {
    const auto iteration_start = SteadyClock::now();
    const auto ordered_blocks  = ordered_candidate_blocks(
      repository.map(), current_pose, blocks, visited, 0U);
    bool found_next                 = false;
    size_t selected_block_index     = 0;
    size_t selected_candidate_index = 0;
    PlanningResult selected_transit;
    double selected_score    = std::numeric_limits<double>::infinity();
    size_t successful_trials = 0;
    std::string last_error;

    for (const auto block_index : ordered_blocks) {
      if (visited.find(block_index) != visited.end()) {
        continue;
      }
      const auto allowed_blocks = bridge_block_path(
        repository.map(), current_pose.center.block_id,
        blocks[block_index]->block_id);
      bool block_has_success = false;
      for (size_t candidate_index = 0;
           candidate_index < candidates_by_block[block_index].size();
           ++candidate_index) {
        const auto &candidate =
          candidates_by_block[block_index][candidate_index];
        const auto transit_start = SteadyClock::now();
        auto transit             = route.steps.empty()
                                     ? make_transit(
                             repository, request, current_pose,
                             candidate.entry_pose, allowed_blocks)
                                     : make_local_bridge_graph_transit(
                             repository.map(), center_grid, bridge_transitions,
                             current_pose, candidate.entry_pose);
        bool used_exact_retry    = false;
        if (!transit.success && !route.steps.empty()) {
          transit = make_transit(
            repository, request, current_pose, candidate.entry_pose,
            allowed_blocks);
          used_exact_retry = true;
        }
        const auto transit_ms = elapsed_ms(transit_start);
        std::ostringstream trial_log;
        trial_log << "large-map greedy trial step=" << route.steps.size()
                  << " from=" << pose_string(current_pose)
                  << " to_block=" << blocks[block_index]->block_id
                  << " candidate=" << candidate_index
                  << " allowed_path_len=" << allowed_blocks.size()
                  << " transit_success=" << (transit.success ? "true" : "false")
                  << " exact_retry=" << (used_exact_retry ? "true" : "false")
                  << " transit_ms=" << transit_ms << " coverage_complete="
                  << (candidate.coverage_complete ? "true" : "false")
                  << " covered=" << candidate.covered_clean_center_count << "/"
                  << candidate.total_clean_center_count
                  << " ratio=" << coverage_ratio(candidate)
                  << " candidate_cost=" << candidate.total_cost;
        if (!transit.success) {
          trial_log << " message=" << transit.message;
          last_error = transit.message;
          route.timing_logs.push_back(trial_log.str());
          debug_log(request.config, trial_log.str());
          continue;
        }

        const auto score = greedy_candidate_score(candidate, transit);
        trial_log << " score=" << score;
        route.timing_logs.push_back(trial_log.str());
        debug_log(request.config, trial_log.str());
        ++successful_trials;
        block_has_success = true;
        if (!found_next || score < selected_score) {
          found_next               = true;
          selected_block_index     = block_index;
          selected_candidate_index = candidate_index;
          selected_transit         = std::move(transit);
          selected_score           = score;
        }
      }
      if (block_has_success) {
        break;
      }
    }

    if (!found_next) {
      route.message = "no greedy coverage transit candidate";
      if (!last_error.empty()) {
        route.message += ": " + last_error;
      }
      return route;
    }

    const auto &selected_candidate =
      candidates_by_block[selected_block_index][selected_candidate_index];
    std::ostringstream step_log;
    step_log << "large-map greedy step=" << route.steps.size()
             << " selected_block=" << blocks[selected_block_index]->block_id
             << " selected_candidate=" << selected_candidate_index
             << " successful_trials=" << successful_trials
             << " selected_score=" << selected_score << " coverage_complete="
             << (selected_candidate.coverage_complete ? "true" : "false")
             << " covered=" << selected_candidate.covered_clean_center_count
             << "/" << selected_candidate.total_clean_center_count
             << " ratio=" << coverage_ratio(selected_candidate)
             << " candidate_cost=" << selected_candidate.total_cost
             << " iteration_ms=" << elapsed_ms(iteration_start);
    route.timing_logs.push_back(step_log.str());
    debug_log(request.config, step_log.str());

    route.steps.push_back(GreedyRouteStep{
      selected_block_index, selected_candidate_index,
      std::move(selected_transit)});
    visited.insert(selected_block_index);
    current_pose = selected_candidate.exit_pose;
  }

  route.success = true;
  route.message = "ok";
  route.timing_logs.push_back(
    "large-map greedy total ms=" + std::to_string(elapsed_ms(route_start)));
  debug_log(request.config, route.timing_logs.back());
  return route;
}

CandidateChoice choose_best_reachable_candidate(
  const MapRepository &repository, const PlanningRequest &request,
  const GridPose &current_pose, const std::vector<const Block *> &blocks,
  const std::vector<std::vector<BlockCoverageCandidate>> &candidates_by_block,
  const std::unordered_set<size_t> &visited, bool global_plan,
  const SearchEffortConfig &effort, std::vector<std::string> &timing_logs) {
  const auto choose_start = SteadyClock::now();
  CandidateChoice best;
  size_t transit_attempts     = 0;
  size_t transit_successes    = 0;
  const auto candidate_blocks = ordered_candidate_blocks(
    repository.map(), current_pose, blocks, visited,
    global_plan ? effort.max_candidate_blocks : 0U);
  const auto candidate_limit =
    global_plan ? effort.max_transit_candidates_per_block : 0U;
  debug_log(
    request.config,
    "choose begin visited=" + std::to_string(visited.size()) +
      " remaining=" + std::to_string(blocks.size() - visited.size()) +
      " current=" + pose_string(current_pose) +
      " candidate_blocks=" + std::to_string(candidate_blocks.size()) +
      " candidate_limit=" + std::to_string(candidate_limit));

  std::unordered_map<uint32_t, std::vector<uint32_t>> allowed_blocks_by_target;

  for (const auto block_index : candidate_blocks) {
    if (visited.find(block_index) != visited.end()) {
      continue;
    }
    if (!global_plan && !visited.empty()) {
      continue;
    }

    std::vector<size_t> candidate_indices;
    candidate_indices.reserve(candidates_by_block[block_index].size());
    for (size_t candidate_index = 0;
         candidate_index < candidates_by_block[block_index].size();
         ++candidate_index) {
      candidate_indices.push_back(candidate_index);
    }
    std::stable_sort(
      candidate_indices.begin(), candidate_indices.end(),
      [&](size_t lhs, size_t rhs) {
        const auto &left            = candidates_by_block[block_index][lhs];
        const auto &right           = candidates_by_block[block_index][rhs];
        const bool left_is_current  = same_pose(left.entry_pose, current_pose);
        const bool right_is_current = same_pose(right.entry_pose, current_pose);
        if (left_is_current != right_is_current) {
          return left_is_current;
        }
        const auto left_distance = manhattan_distance(
          current_pose.center, left.entry_pose.center,
          repository.map().cell_model);
        const auto right_distance = manhattan_distance(
          current_pose.center, right.entry_pose.center,
          repository.map().cell_model);
        if (left_distance != right_distance) {
          return left_distance < right_distance;
        }
        return left.total_cost < right.total_cost;
      });
    if (candidate_limit > 0 && candidate_indices.size() > candidate_limit) {
      candidate_indices.resize(candidate_limit);
    }
    auto &allowed_blocks =
      allowed_blocks_by_target[blocks[block_index]->block_id];
    if (allowed_blocks.empty()) {
      allowed_blocks = bridge_block_path(
        repository.map(), current_pose.center.block_id,
        blocks[block_index]->block_id);
    }
    debug_log(
      request.config,
      "try block=" + std::to_string(blocks[block_index]->block_id) +
        " selected_candidates=" + std::to_string(candidate_indices.size()) +
        " total_candidates=" +
        std::to_string(candidates_by_block[block_index].size()) +
        " allowed_path_len=" + std::to_string(allowed_blocks.size()));

    for (const auto candidate_index : candidate_indices) {
      const auto &candidate = candidates_by_block[block_index][candidate_index];
      const auto transit_start = SteadyClock::now();
      auto transit             = make_transit(
        repository, request, current_pose, candidate.entry_pose,
        allowed_blocks);
      const auto transit_ms = elapsed_ms(transit_start);
      ++transit_attempts;
      std::ostringstream transit_log;
      transit_log << "transit attempt=" << transit_attempts
                  << " to_block=" << blocks[block_index]->block_id
                  << " candidate=" << candidate_index
                  << " entry=" << pose_string(candidate.entry_pose)
                  << " success=" << (transit.success ? "true" : "false")
                  << " ms=" << transit_ms
                  << " waypoints=" << transit.waypoints.size();
      if (!transit.success) {
        transit_log << " message=" << transit.message;
        debug_log(request.config, transit_log.str());
        timing_logs.push_back(transit_log.str());
        continue;
      }
      ++transit_successes;
      debug_log(request.config, transit_log.str());
      timing_logs.push_back(transit_log.str());
      const auto turns = turn_count(transit.waypoints);
      const auto score = static_cast<double>(turns) * 100000.0 +
                         transit.debug.total_cost + candidate.total_cost;
      if (
        !best.found || turns < best.turns ||
        (turns == best.turns && score < best.score)) {
        best.block_index     = block_index;
        best.candidate_index = candidate_index;
        best.transit         = std::move(transit);
        best.score           = score;
        best.turns           = turns;
        best.found           = true;
      }
    }
  }
  std::ostringstream summary;
  summary << "choose summary visited=" << visited.size()
          << " attempts=" << transit_attempts
          << " successes=" << transit_successes
          << " found=" << (best.found ? "true" : "false")
          << " ms=" << elapsed_ms(choose_start);
  if (best.found) {
    summary << " selected_block=" << blocks[best.block_index]->block_id
            << " selected_candidate=" << best.candidate_index;
  }
  debug_log(request.config, summary.str());
  timing_logs.push_back(summary.str());
  return best;
}

}  // namespace

PlanningResult GlobalCoveragePlanner::plan(
  const MapRepository &repository, const PlanningRequest &request) const {
  const auto plan_start = SteadyClock::now();
  PlanningResult result;
  if (!repository.has_map()) {
    result.message = "map is not loaded";
    return result;
  }

  const auto &map = repository.map();
  if (request.map_id != map.map_id) {
    result.message = "map_id mismatch";
    return result;
  }
  if (request.map_version != map.version) {
    result.message = "map_version mismatch";
    return result;
  }
  if (!heading_is_valid(request.start_pose.heading)) {
    result.message = "start heading is out of range";
    return result;
  }
  if (!center_exists(repository, request.start_pose.center)) {
    result.message = "start pose center does not exist or is not cleanable";
    return result;
  }

  const auto target_ids =
    unique_target_block_ids(map, repository, request.target_block_ids, result);
  if (!result.message.empty()) {
    return result;
  }
  if (target_ids.empty()) {
    result.message = "no target cleanable blocks";
    return result;
  }

  std::vector<const Block *> blocks;
  for (const auto block_id : target_ids) {
    const auto *block = repository.find_block(block_id);
    if (block != nullptr && block->cleanable) {
      blocks.push_back(block);
    }
  }

  std::vector<std::string> timing_logs;
  timing_logs.push_back(
    "coverage plan begin targets=" + std::to_string(blocks.size()) +
    " global=" +
    (request.global_plan ? std::string("true") : std::string("false")) +
    " effort=" + request.config.planning_search_effort +
    " start=" + pose_string(request.start_pose));
  if (blocks.size() > 20U) {
    timing_logs.push_back(
      "coverage large map note: target_count>20; current planner enters repeated transit candidate selection");
  }
  debug_log(request.config, timing_logs.back());

  const auto center_grid_start = SteadyClock::now();
  const auto center_grid =
    CenterGridBuilder().build(map, repository, request.config);
  const auto center_grid_ms = elapsed_ms(center_grid_start);
  timing_logs.push_back(
    "center grid build ms=" + std::to_string(center_grid_ms));
  debug_log(request.config, timing_logs.back());
  if (!center_grid.is_traversable(request.start_pose)) {
    result.message = "start pose is not traversable";
    return result;
  }

  const auto bridge_transition_start = SteadyClock::now();
  const auto bridge_transitions = BridgeTransitionPlanner().make_transitions(
    map, repository, center_grid, request.config);
  for (const auto &transition : bridge_transitions) {
    if (!transition.usable) {
      result.debug.unusable_bridges.push_back(
        "bridge " + std::to_string(transition.bridge_id) + ": " +
        transition.message);
    }
  }
  timing_logs.push_back(
    "bridge transition build ms=" +
    std::to_string(elapsed_ms(bridge_transition_start)));
  debug_log(request.config, timing_logs.back());

  const auto effort =
    search_effort_config(request.config.planning_search_effort);
  const bool use_scalable_global_search =
    request.global_plan && blocks.size() > 20U;
  const BlockCoverageCandidateGenerator generator;
  std::vector<std::vector<BlockCoverageCandidate>> candidates_by_block(
    blocks.size());
  const auto all_candidates_start = SteadyClock::now();
  for (size_t block_index = 0; block_index < blocks.size(); ++block_index) {
    const auto block_candidate_start = SteadyClock::now();
    const auto *block                = blocks[block_index];
    auto entries                     = generator.make_corner_entry_poses(
      map, *block, center_grid, use_scalable_global_search ? 1 : 2);
    if (
      !use_scalable_global_search &&
      block->block_id == request.start_pose.center.block_id) {
      entries.push_back(request.start_pose);
    }
    for (const auto &entry : entries) {
      auto entry_candidates = generator.make_entry_constrained_candidates(
        map, repository, *block, center_grid, request.config, entry,
        use_scalable_global_search ? 1U : effort.max_candidates);
      candidates_by_block[block_index].insert(
        candidates_by_block[block_index].end(),
        std::make_move_iterator(entry_candidates.begin()),
        std::make_move_iterator(entry_candidates.end()));
    }
    auto free_candidates = generator.make_free_start_candidates(
      map, repository, *block, center_grid, request.config,
      use_scalable_global_search ? 1U : effort.max_candidates,
      use_scalable_global_search ? 1U : effort.max_start_segments);
    candidates_by_block[block_index].insert(
      candidates_by_block[block_index].end(),
      std::make_move_iterator(free_candidates.begin()),
      std::make_move_iterator(free_candidates.end()));
    std::sort(
      candidates_by_block[block_index].begin(),
      candidates_by_block[block_index].end(),
      BlockCoverageCandidateGenerator::candidate_less);
    const auto max_block_candidates =
      use_scalable_global_search ? 6U : effort.max_candidates;
    if (candidates_by_block[block_index].size() > max_block_candidates) {
      candidates_by_block[block_index].resize(max_block_candidates);
    }
    std::ostringstream block_log;
    block_log << "candidate generation block=" << block->block_id
              << " candidates=" << candidates_by_block[block_index].size()
              << " ms=" << elapsed_ms(block_candidate_start);
    timing_logs.push_back(block_log.str());
    debug_log(request.config, block_log.str());
    if (candidates_by_block[block_index].empty()) {
      result.message =
        "no feasible candidate for block " + std::to_string(block->block_id);
      result.debug.score_breakdown.insert(
        result.debug.score_breakdown.end(), timing_logs.begin(),
        timing_logs.end());
      return result;
    }
  }
  timing_logs.push_back(
    "candidate generation total ms=" +
    std::to_string(elapsed_ms(all_candidates_start)));
  debug_log(request.config, timing_logs.back());

  auto greedy_route = make_greedy_large_map_route(
    repository, request, center_grid, bridge_transitions, request.start_pose,
    blocks, candidates_by_block);
  timing_logs.insert(
    timing_logs.end(), greedy_route.timing_logs.begin(),
    greedy_route.timing_logs.end());
  if (!greedy_route.success) {
    result.message                 = greedy_route.message;
    result.debug.coverage_complete = false;
    result.debug.score_breakdown.insert(
      result.debug.score_breakdown.end(), timing_logs.begin(),
      timing_logs.end());
    return result;
  }

  std::vector<uint32_t> block_order;
  for (const auto &step : greedy_route.steps) {
    const auto &candidate =
      candidates_by_block[step.block_index][step.candidate_index];
    append_non_clean_transit(result.waypoints, step.transit.waypoints);
    result.waypoints.insert(
      result.waypoints.end(), candidate.waypoints.begin(),
      candidate.waypoints.end());
    result.debug.total_cost +=
      step.transit.debug.total_cost + candidate.total_cost;
    block_order.push_back(blocks[step.block_index]->block_id);
    result.debug.selected_block_id = blocks[step.block_index]->block_id;
    if (!candidate.coverage_complete) {
      result.debug.invalid_reasons.push_back(
        "block " + std::to_string(blocks[step.block_index]->block_id) +
        " selected incomplete coverage candidate");
    }
  }
  result.success =
    !result.waypoints.empty() && clean_waypoint_count(result.waypoints) > 0;
  result.message = result.success ? "ok" : "no waypoints generated";
  result.debug.coverage_complete = result.success;
  result.debug.invalid_reasons.push_back(
    std::string("coverage block order: ") + join_ids(block_order));
  result.debug.invalid_reasons.push_back("coverage mode: unified greedy");
  timing_logs.push_back(
    "coverage plan total ms=" + std::to_string(elapsed_ms(plan_start)));
  result.debug.score_breakdown.insert(
    result.debug.score_breakdown.end(), timing_logs.begin(), timing_logs.end());
  return result;
}

}  // namespace map_planner
