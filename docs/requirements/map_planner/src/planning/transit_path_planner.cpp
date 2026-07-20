#include "map_planner/planning/transit_path_planner.hpp"

#include <algorithm>
#include <cstdint>
#include <functional>
#include <limits>
#include <map>
#include <optional>
#include <queue>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "map_planner/planning/bridge_transition_planner.hpp"
#include "map_planner/planning/center_grid.hpp"
#include "map_planner/planning/transit_utils.hpp"

namespace map_planner {
namespace {

struct NodeKey {
  GridPose pose;

  bool operator==(const NodeKey &other) const {
    return same_pose(pose, other.pose);
  }
};

void hash_combine(size_t &seed, size_t value) {
  seed ^= value + 0x9e3779b97f4a7c15ULL + (seed << 6U) + (seed >> 2U);
}

struct NodeKeyHash {
  size_t operator()(const NodeKey &key) const {
    size_t seed        = 0;
    const auto &center = key.pose.center;
    hash_combine(seed, std::hash<uint32_t>{}(center.block_id));
    hash_combine(seed, std::hash<int>{}(center.cell_row));
    hash_combine(seed, std::hash<int>{}(center.cell_col));
    hash_combine(seed, std::hash<int>{}(center.inner_row));
    hash_combine(seed, std::hash<int>{}(center.inner_col));
    hash_combine(
      seed, std::hash<uint8_t>{}(static_cast<uint8_t>(key.pose.heading)));
    return seed;
  }
};

struct TransitCost {
  size_t turns{};
  size_t bridges{};
  double distance{};

  bool operator<(const TransitCost &other) const {
    if (turns != other.turns) {
      return turns < other.turns;
    }
    if (bridges != other.bridges) {
      return bridges < other.bridges;
    }
    return distance < other.distance;
  }

  bool operator==(const TransitCost &other) const {
    return turns == other.turns && bridges == other.bridges &&
           distance == other.distance;
  }
};

TransitCost operator+(const TransitCost &lhs, const TransitCost &rhs) {
  return TransitCost{
    lhs.turns + rhs.turns, lhs.bridges + rhs.bridges,
    lhs.distance + rhs.distance};
}

struct QueueItem {
  TransitCost cost;
  NodeKey key;

  bool operator>(const QueueItem &other) const { return other.cost < cost; }
};

struct SearchRecord {
  TransitCost cost{
    std::numeric_limits<size_t>::max(), std::numeric_limits<size_t>::max(),
    std::numeric_limits<double>::infinity()};
  std::optional<NodeKey> previous;
  std::vector<PathWaypoint> edge_waypoints;
};

struct DebugCounter {
  size_t total{};
  std::unordered_map<std::string, size_t> by_reason;
  std::vector<std::string> samples;
};

void record_debug(
  DebugCounter &counter, const std::string &reason, const std::string &sample) {
  ++counter.total;
  ++counter.by_reason[reason];
  if (counter.samples.size() < 40U) {
    counter.samples.push_back(reason + ": " + sample);
  }
}

void append_debug_counter(
  PlanningDebug &debug, const std::string &label, const DebugCounter &counter) {
  debug.score_breakdown.push_back(
    label + " total=" + std::to_string(counter.total));
  for (const auto &entry : counter.by_reason) {
    debug.score_breakdown.push_back(
      label + " " + entry.first + "=" + std::to_string(entry.second));
  }
  for (const auto &sample : counter.samples) {
    debug.score_breakdown.push_back(label + " sample " + sample);
  }
}

std::vector<Heading> headings() {
  return {
    Heading::BlockUPositive, Heading::BlockUNegative, Heading::BlockVPositive,
    Heading::BlockVNegative};
}

bool heading_is_valid(Heading heading) {
  return static_cast<uint8_t>(heading) <=
         static_cast<uint8_t>(Heading::BlockVNegative);
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

std::vector<uint32_t> allowed_blocks(
  const PvMap &map, const MapRepository &repository,
  const std::vector<uint32_t> &requested, PlanningResult &result) {
  std::vector<uint32_t> allowed;
  if (requested.empty()) {
    for (const auto &block : map.blocks) {
      if (block.cleanable) {
        allowed.push_back(block.block_id);
      }
    }
    return allowed;
  }

  for (const auto block_id : requested) {
    if (std::find(allowed.begin(), allowed.end(), block_id) != allowed.end()) {
      continue;
    }
    const auto *block = repository.find_block(block_id);
    if (block == nullptr) {
      result.message =
        "allowed block_id " + std::to_string(block_id) + " does not exist";
      return {};
    }
    if (!block->cleanable) {
      result.message =
        "allowed block_id " + std::to_string(block_id) + " is not cleanable";
      return {};
    }
    allowed.push_back(block_id);
  }
  return allowed;
}

bool contains_id(const std::vector<uint32_t> &ids, uint32_t id) {
  return std::find(ids.begin(), ids.end(), id) != ids.end();
}

CenterInnerCell neighbor_center(
  const CenterInnerCell &center, Heading heading, const CellModel &cell_model) {
  auto row = global_row(center, cell_model);
  auto col = global_col(center, cell_model);
  switch (heading) {
    case Heading::BlockUPositive:
      ++col;
      break;
    case Heading::BlockUNegative:
      --col;
      break;
    case Heading::BlockVPositive:
      ++row;
      break;
    case Heading::BlockVNegative:
      --row;
      break;
  }
  return from_global(center.block_id, cell_model, row, col);
}

PathWaypoint make_waypoint(
  WaypointType type, const GridPose &pose,
  std::optional<uint32_t> bridge_id = std::nullopt) {
  PathWaypoint waypoint;
  waypoint.type              = type;
  waypoint.center_inner_cell = pose.center;
  waypoint.heading           = pose.heading;
  waypoint.brush_on          = false;
  waypoint.bridge_id         = bridge_id;
  return waypoint;
}

PathWaypoint make_turn_waypoint(const GridPose &from, Heading to_heading) {
  PathWaypoint waypoint;
  waypoint.type              = WaypointType::TurnInPlace;
  waypoint.center_inner_cell = from.center;
  waypoint.heading           = from.heading;
  waypoint.brush_on          = false;
  waypoint.rotation_angle_deg =
    rotation_between_headings_deg(from.heading, to_heading);
  return waypoint;
}

TransitCost edge_cost(
  const std::vector<PathWaypoint> &waypoints, bool bridge_edge) {
  return TransitCost{
    turn_count(waypoints), bridge_edge ? 1U : 0U,
    static_cast<double>(waypoints.size())};
}

}  // namespace

struct SearchOutcome {
  bool success{false};
  std::string message;
  std::vector<PathWaypoint> waypoints;
  PlanningDebug debug;
  TransitCost cost;
  size_t expanded_count{};
  std::optional<GridPose> final_pose;
};

void normalize_transit_waypoints(std::vector<PathWaypoint> &waypoints) {
  for (auto &waypoint : waypoints) {
    waypoint.brush_on = false;
    if (waypoint.type == WaypointType::Clean) {
      waypoint.type = WaypointType::Deadhead;
    }
  }
}

SearchOutcome make_search_result(
  const TransitPlanningRequest &request,
  const std::unordered_map<NodeKey, SearchRecord, NodeKeyHash> &records,
  const NodeKey &goal_key, size_t expanded_count,
  const DebugCounter &bridge_attempt_debug, const std::string &label,
  bool include_debug_counter) {
  SearchOutcome outcome;
  std::vector<NodeKey> route;
  for (std::optional<NodeKey> key = goal_key; key.has_value();) {
    route.push_back(*key);
    const auto record_it = records.find(*key);
    key =
      record_it == records.end() ? std::nullopt : record_it->second.previous;
  }
  std::reverse(route.begin(), route.end());

  outcome.waypoints.push_back(
    make_waypoint(WaypointType::Deadhead, request.start_pose));
  for (size_t index = 1; index < route.size(); ++index) {
    const auto record_it = records.find(route[index]);
    if (record_it == records.end()) {
      continue;
    }
    outcome.waypoints.insert(
      outcome.waypoints.end(), record_it->second.edge_waypoints.begin(),
      record_it->second.edge_waypoints.end());
  }

  normalize_transit_waypoints(outcome.waypoints);
  const auto final_record = records.at(goal_key);
  outcome.success         = !outcome.waypoints.empty();
  outcome.message         = outcome.success ? "ok" : "no waypoints generated";
  outcome.cost            = final_record.cost;
  outcome.expanded_count  = expanded_count;
  outcome.final_pose      = route.empty()
                              ? std::optional<GridPose>{}
                              : std::optional<GridPose>{route.back().pose};
  outcome.debug.coverage_complete = outcome.success;
  outcome.debug.total_cost        = final_record.cost.distance;
  outcome.debug.score_breakdown.push_back(
    label + " cost: turns=" + std::to_string(final_record.cost.turns) +
    ", bridges=" + std::to_string(final_record.cost.bridges) +
    ", distance=" + std::to_string(final_record.cost.distance));
  outcome.debug.score_breakdown.push_back(
    label + " expanded poses: " + std::to_string(expanded_count));
  if (include_debug_counter) {
    append_debug_counter(
      outcome.debug, label + " bridge attempts", bridge_attempt_debug);
  }
  outcome.debug.invalid_reasons.push_back(
    label + " start: " + pose_string(request.start_pose));
  if (!route.empty()) {
    outcome.debug.invalid_reasons.push_back(
      label + " goal: " + pose_string(route.back().pose));
  }
  return outcome;
}

SearchOutcome run_exact_search(
  const PvMap &map, const MapRepository &repository,
  const TransitPlanningRequest &request, const CenterGrid &center_grid,
  const std::vector<uint32_t> &allowed,
  const std::vector<const BridgeTransition *> &usable_bridges) {
  const NodeKey start_key{request.start_pose};
  std::unordered_map<NodeKey, SearchRecord, NodeKeyHash> records;
  std::priority_queue<
    QueueItem, std::vector<QueueItem>, std::greater<QueueItem>>
    queue;
  records[start_key].cost = TransitCost{};
  queue.push(QueueItem{TransitCost{}, start_key});

  DebugCounter bridge_attempt_debug;
  std::optional<NodeKey> goal_key;
  size_t expanded_count = 0;
  while (!queue.empty()) {
    const auto item = queue.top();
    queue.pop();
    const auto record_it = records.find(item.key);
    if (record_it == records.end() || !(item.cost == record_it->second.cost)) {
      continue;
    }
    ++expanded_count;

    const auto &pose   = item.key.pose;
    const bool at_goal = request.require_goal_heading
                           ? same_pose(pose, request.goal_pose)
                           : same_center(pose.center, request.goal_pose.center);
    if (at_goal) {
      goal_key = item.key;
      break;
    }

    auto relax = [&](
                   const NodeKey &next_key,
                   const std::vector<PathWaypoint> &edge_waypoints,
                   const TransitCost &extra_cost) {
      const auto next_cost = item.cost + extra_cost;
      auto &next_record    = records[next_key];
      if (next_cost < next_record.cost) {
        next_record.cost           = next_cost;
        next_record.previous       = item.key;
        next_record.edge_waypoints = edge_waypoints;
        queue.push(QueueItem{next_cost, next_key});
      }
    };

    for (const auto heading : headings()) {
      if (heading == pose.heading) {
        continue;
      }
      if (!center_grid.is_traversable(pose.center, heading)) {
        continue;
      }
      const GridPose next_pose{pose.center, heading};
      const std::vector<PathWaypoint> waypoints{
        make_turn_waypoint(pose, next_pose.heading)};
      relax(NodeKey{next_pose}, waypoints, TransitCost{1, 0, 0.0});
    }

    const auto next_center =
      neighbor_center(pose.center, pose.heading, map.cell_model);
    if (
      contains_id(allowed, next_center.block_id) &&
      center_exists(repository, next_center) &&
      center_grid.is_traversable(next_center, pose.heading)) {
      const GridPose next_pose{next_center, pose.heading};
      const std::vector<PathWaypoint> waypoints{
        make_waypoint(WaypointType::Deadhead, next_pose)};
      relax(NodeKey{next_pose}, waypoints, TransitCost{0, 0, 1.0});
    }

    for (const auto *transition : usable_bridges) {
      for (size_t source_index = 0; source_index < transition->sides.size();
           ++source_index) {
        const auto &source_side = transition->sides[source_index];
        if (source_side.block_id != pose.center.block_id) {
          continue;
        }
        const auto source_normal = normal_pose_for_side(source_side, false);
        if (!source_normal.has_value()) {
          if (request.config.debug_score_breakdown) {
            record_debug(
              bridge_attempt_debug, "source normal missing",
              "bridge=" + std::to_string(transition->bridge_id) +
                " pose=" + pose_string(pose));
          }
          continue;
        }
        if (!same_pose(pose, *source_normal)) {
          continue;
        }
        for (size_t target_index = 0; target_index < transition->sides.size();
             ++target_index) {
          if (target_index == source_index) {
            continue;
          }
          const auto &target_side  = transition->sides[target_index];
          const auto target_normal = normal_pose_for_side(target_side, true);
          if (!target_normal.has_value()) {
            if (request.config.debug_score_breakdown) {
              record_debug(
                bridge_attempt_debug, "target normal missing",
                "bridge=" + std::to_string(transition->bridge_id) +
                  " pose=" + pose_string(pose));
            }
            continue;
          }
          const auto bridge_plan = build_bridge_transfer_plan(
            *source_normal, *target_normal, *transition, source_side,
            target_side, center_grid, map.cell_model);
          if (!bridge_plan.success) {
            if (request.config.debug_score_breakdown) {
              record_debug(
                bridge_attempt_debug, bridge_plan.message,
                "bridge=" + std::to_string(transition->bridge_id) +
                  " from=" + pose_string(pose) +
                  " source_normal=" + pose_string(*source_normal) +
                  " target_normal=" + pose_string(*target_normal));
            }
            continue;
          }
          if (request.config.debug_score_breakdown) {
            record_debug(
              bridge_attempt_debug, "success",
              "bridge=" + std::to_string(transition->bridge_id) +
                " from=" + pose_string(pose) +
                " target_normal=" + pose_string(*target_normal));
          }
          relax(
            NodeKey{*target_normal}, bridge_plan.waypoints,
            edge_cost(bridge_plan.waypoints, true));
        }
      }
    }
  }

  if (!goal_key.has_value()) {
    SearchOutcome outcome;
    outcome.message        = "goal is unreachable";
    outcome.expanded_count = expanded_count;
    outcome.debug.unreachable_segments.push_back(
      "transit search exhausted " + std::to_string(expanded_count) + " poses");
    if (request.config.debug_score_breakdown) {
      append_debug_counter(
        outcome.debug, "transit bridge attempts", bridge_attempt_debug);
    }
    return outcome;
  }

  return make_search_result(
    request, records, *goal_key, expanded_count, bridge_attempt_debug,
    "transit", request.config.debug_score_breakdown);
}

PlanningResult to_planning_result(const SearchOutcome &outcome) {
  PlanningResult result;
  result.success   = outcome.success;
  result.message   = outcome.message;
  result.waypoints = outcome.waypoints;
  result.debug     = outcome.debug;
  return result;
}

PlanningResult TransitPathPlanner::plan(
  const MapRepository &repository,
  const TransitPlanningRequest &request) const {
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
  if (
    !heading_is_valid(request.start_pose.heading) ||
    !heading_is_valid(request.goal_pose.heading)) {
    result.message = "heading is out of range";
    return result;
  }

  const auto allowed =
    allowed_blocks(map, repository, request.allowed_block_ids, result);
  if (!result.message.empty()) {
    return result;
  }
  if (allowed.empty()) {
    result.message = "no allowed cleanable blocks";
    return result;
  }
  if (!contains_id(allowed, request.start_pose.center.block_id)) {
    result.message = "start block is not allowed";
    return result;
  }
  if (!contains_id(allowed, request.goal_pose.center.block_id)) {
    result.message = "goal block is not allowed";
    return result;
  }
  if (!center_exists(repository, request.start_pose.center)) {
    result.message = "start pose center does not exist or is not cleanable";
    return result;
  }
  if (!center_exists(repository, request.goal_pose.center)) {
    result.message = "goal pose center does not exist or is not cleanable";
    return result;
  }

  const auto center_grid =
    CenterGridBuilder().build(map, repository, request.config);
  if (!center_grid.is_traversable(request.start_pose)) {
    result.message = "start pose is not traversable";
    return result;
  }
  if (
    request.require_goal_heading &&
    !center_grid.is_traversable(request.goal_pose)) {
    result.message = "goal pose is not traversable";
    return result;
  }
  if (!request.require_goal_heading) {
    bool any_goal_heading = false;
    for (const auto heading : headings()) {
      if (center_grid.is_traversable(request.goal_pose.center, heading)) {
        any_goal_heading = true;
        break;
      }
    }
    if (!any_goal_heading) {
      result.message = "goal center is not traversable for any heading";
      return result;
    }
  }

  const auto bridge_transitions = BridgeTransitionPlanner().make_transitions(
    map, repository, center_grid, request.config);
  std::vector<const BridgeTransition *> usable_bridges;
  for (const auto &transition : bridge_transitions) {
    if (!transition.usable) {
      result.debug.unusable_bridges.push_back(
        "bridge " + std::to_string(transition.bridge_id) + ": " +
        transition.message);
      continue;
    }
    if (transition.sides.size() != 2) {
      continue;
    }
    if (
      !contains_id(allowed, transition.sides[0].block_id) ||
      !contains_id(allowed, transition.sides[1].block_id)) {
      continue;
    }
    usable_bridges.push_back(&transition);
  }

  auto exact = run_exact_search(
    map, repository, request, center_grid, allowed, usable_bridges);
  auto exact_result = to_planning_result(exact);
  exact_result.debug.unusable_bridges.insert(
    exact_result.debug.unusable_bridges.end(),
    result.debug.unusable_bridges.begin(), result.debug.unusable_bridges.end());
  return exact_result;
}

}  // namespace map_planner
