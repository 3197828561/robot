#include "map_planner/planning/snake_coverage_planner.hpp"

#include <algorithm>
#include <string>

#include "map_planner/planning/block_coverage_candidate_generator.hpp"
#include "map_planner/planning/center_grid.hpp"

namespace map_planner {
namespace {

struct SearchEffortConfig {
  size_t max_candidates{};
  size_t max_start_segments{};
};

SearchEffortConfig search_effort_config(const std::string &effort) {
  if (effort == "fast") {
    return {12, 5};
  }
  if (effort == "balanced") {
    return {16, 8};
  }
  if (effort == "quality") {
    return {16, 8};
  }
  if (effort == "exhaustive") {
    return {32, 0};
  }
  return {16, 10};
}

}  // namespace

PlanningResult SnakeCoveragePlanner::plan(
  const MapRepository &repository, const PlanningRequest &request) const {
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

  const uint32_t block_id = request.target_block_ids.empty()
                              ? request.start_pose.center.block_id
                              : request.target_block_ids.front();
  if (request.target_block_ids.size() > 1U) {
    result.message = "SnakeCoveragePlanner supports exactly one target block";
    return result;
  }

  const auto *block = repository.find_block(block_id);
  if (block == nullptr) {
    result.message = "target block_id does not exist";
    return result;
  }
  if (!block->cleanable) {
    result.message = "target block is not cleanable";
    return result;
  }

  const CenterGridBuilder center_grid_builder;
  const auto center_grid =
    center_grid_builder.build_block(map, repository, *block, request.config);

  const auto effort =
    search_effort_config(request.config.planning_search_effort);
  const BlockCoverageCandidateGenerator generator;
  auto candidates = generator.make_free_start_candidates(
    map, repository, *block, center_grid, request.config, effort.max_candidates,
    effort.max_start_segments);
  if (candidates.empty()) {
    result.message = "no feasible lane candidate";
    return result;
  }

  const auto best_it = std::min_element(
    candidates.begin(), candidates.end(),
    BlockCoverageCandidateGenerator::candidate_less);
  const auto &best = *best_it;

  result.waypoints = best.waypoints;
  result.success   = !result.waypoints.empty();
  result.message   = result.success ? "ok" : "no waypoints generated";
  result.debug.coverage_complete    = best.coverage_complete;
  result.debug.selected_block_id    = block->block_id;
  result.debug.selected_sweep_axis  = best.sweep_axis;
  result.debug.selected_lane_stride = best.lane_stride;
  result.debug.lane_offset          = best.lane_offset;
  result.debug.total_cost           = best.total_cost;
  if (request.config.debug_score_breakdown) {
    result.debug.score_breakdown.reserve(
      candidates.size() + best.debug_score_breakdowns.size() + 1U);
    result.debug.score_breakdown.push_back("selected: " + best.score_breakdown);
    result.debug.score_breakdown.insert(
      result.debug.score_breakdown.end(), best.debug_score_breakdowns.begin(),
      best.debug_score_breakdowns.end());
    for (size_t index = 0; index < candidates.size(); ++index) {
      result.debug.score_breakdown.push_back(
        "rank " + std::to_string(index) + ": " +
        candidates[index].score_breakdown);
    }
  }
  result.debug.invalid_reasons      = best.invalid_reasons;
  result.debug.unreachable_segments = best.unreachable_segments;
  return result;
}

PathWaypoint SnakeCoveragePlanner::make_clean_waypoint(
  const CenterInnerCell &center, Heading heading) {
  PathWaypoint waypoint;
  waypoint.type              = WaypointType::Clean;
  waypoint.center_inner_cell = center;
  waypoint.heading           = heading;
  waypoint.brush_on          = true;
  return waypoint;
}

double SnakeCoveragePlanner::candidate_cost(
  const LanePlanCandidate &candidate) {
  double cost = static_cast<double>(candidate.segments.size());
  for (const auto &segment : candidate.segments) {
    cost += static_cast<double>(segment.centers.size());
  }
  cost += static_cast<double>(candidate.unreachable_segments.size()) * 1000.0;
  return cost;
}

}  // namespace map_planner
