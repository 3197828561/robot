#include <gtest/gtest.h>

#include <algorithm>
#include <string>

#include "map_planner/planning/global_coverage_planner.hpp"

namespace {

map_planner::Cell make_cell(
  uint32_t cell_id, uint32_t block_id, int row, int col, double origin_u) {
  map_planner::Cell cell;
  cell.cell_id    = cell_id;
  cell.block_id   = block_id;
  cell.row        = row;
  cell.col        = col;
  const double u0 = origin_u + static_cast<double>(col) * 210.0;
  const double u1 = u0 + 210.0;
  cell.polygon    = {{u0, 0.0}, {u1, 0.0}, {u1, 105.0}, {u0, 105.0}};
  return cell;
}

map_planner::PvMap make_line_bridge_map(size_t block_count) {
  map_planner::PvMap map;
  map.map_id                = 1;
  map.version               = 1;
  map.cell_model.inner_rows = 3;
  map.cell_model.inner_cols = 6;

  uint32_t cell_id = 1;
  for (size_t index = 0; index < block_count; ++index) {
    const uint32_t block_id = static_cast<uint32_t>(index + 1);
    const double origin_u   = static_cast<double>(index) * 520.0;

    map_planner::Block block;
    block.block_id = block_id;
    block.rows     = 1;
    block.cols     = 2;
    block.grid     = {{1, 1}};
    block.cell_ids = {cell_id, static_cast<uint32_t>(cell_id + 1)};
    map.blocks.push_back(block);

    map.cells.push_back(make_cell(cell_id, block_id, 0, 0, origin_u));
    ++cell_id;
    map.cells.push_back(make_cell(cell_id, block_id, 0, 1, origin_u));
    ++cell_id;
  }

  for (size_t index = 0; index + 1 < block_count; ++index) {
    const uint32_t bridge_id       = static_cast<uint32_t>(index + 1);
    const uint32_t source_block_id = static_cast<uint32_t>(index + 1);
    const uint32_t target_block_id = static_cast<uint32_t>(index + 2);
    const double source_u          = static_cast<double>(index) * 520.0 + 420.0;
    const double target_u          = static_cast<double>(index + 1) * 520.0;

    map_planner::Bridge bridge;
    bridge.bridge_id = bridge_id;
    bridge.endpoints = {
      {source_block_id, 0, 1, "u_max", 1, 5},
      {target_block_id, 0, 0, "u_min", 1, 0}};
    bridge.centerline = {{source_u, 52.5}, {target_u, 52.5}};
    map.bridges.push_back(bridge);
  }
  return map;
}

map_planner::PlanningRequest make_request(size_t block_count = 2) {
  map_planner::PlanningRequest request;
  request.map_id                        = 1;
  request.map_version                   = 1;
  request.start_pose.center             = {1, 0, 0, 1, 1};
  request.start_pose.heading            = map_planner::Heading::BlockUPositive;
  request.global_plan                   = true;
  request.config.planning_search_effort = "quality";
  request.config.robot_length_cm        = 120.0;
  request.config.robot_width_cm         = 70.0;
  request.config.safety_margin_cm       = 10.0;
  request.config.cleaning_width_cm      = 70.0;
  request.config.overlap_ratio          = 0.2;
  request.config.enable_tail_coverage   = true;
  request.target_block_ids.reserve(block_count);
  for (size_t index = 0; index < block_count; ++index) {
    request.target_block_ids.push_back(static_cast<uint32_t>(index + 1));
  }
  return request;
}

bool has_waypoint_type(
  const std::vector<map_planner::PathWaypoint> &waypoints,
  map_planner::WaypointType type) {
  return std::any_of(waypoints.begin(), waypoints.end(), [&](const auto &wp) {
    return wp.type == type;
  });
}

}  // namespace

TEST(GlobalCoveragePlanner, CoversTwoBlocksThroughBridge) {
  map_planner::MapRepository repository;
  repository.set_map(make_line_bridge_map(2));

  const auto result =
    map_planner::GlobalCoveragePlanner().plan(repository, make_request(2));

  EXPECT_TRUE(result.success) << result.message;
  EXPECT_FALSE(result.waypoints.empty());
  EXPECT_TRUE(has_waypoint_type(
    result.waypoints, map_planner::WaypointType::BridgeCrossing));
  EXPECT_TRUE(result.debug.coverage_complete);
}

TEST(GlobalCoveragePlanner, EmptyTargetsCoverAllCleanableBlocks) {
  map_planner::MapRepository repository;
  repository.set_map(make_line_bridge_map(2));

  auto request = make_request(2);
  request.target_block_ids.clear();
  const auto result =
    map_planner::GlobalCoveragePlanner().plan(repository, request);

  EXPECT_TRUE(result.success) << result.message;
  EXPECT_TRUE(result.debug.coverage_complete);
  EXPECT_TRUE(
    has_waypoint_type(result.waypoints, map_planner::WaypointType::Clean));
}

TEST(GlobalCoveragePlanner, TransitBeforeFirstCleanIsNotClean) {
  map_planner::MapRepository repository;
  repository.set_map(make_line_bridge_map(2));

  auto request             = make_request(1);
  request.target_block_ids = {2};
  const auto result =
    map_planner::GlobalCoveragePlanner().plan(repository, request);

  EXPECT_TRUE(result.success) << result.message;
  const auto first_clean = std::find_if(
    result.waypoints.begin(), result.waypoints.end(), [](const auto &waypoint) {
      return waypoint.type == map_planner::WaypointType::Clean;
    });
  ASSERT_NE(first_clean, result.waypoints.end());
  EXPECT_TRUE(std::none_of(
    result.waypoints.begin(), first_clean, [](const auto &waypoint) {
      return waypoint.type == map_planner::WaypointType::Clean ||
             waypoint.brush_on;
    }));
  EXPECT_EQ(result.debug.selected_block_id, 2U);
}
