#include <gtest/gtest.h>

#include <algorithm>
#include <filesystem>
#include <vector>

#include "map_planner/map/yaml_map_importer.hpp"
#include "map_planner/planning/center_grid.hpp"
#include "map_planner/planning/transit_path_planner.hpp"
#include "map_planner/planning/transit_utils.hpp"

namespace {

map_planner::Cell make_cell(
  uint32_t cell_id, uint32_t block_id, int row, int col) {
  map_planner::Cell cell;
  cell.cell_id    = cell_id;
  cell.block_id   = block_id;
  cell.row        = row;
  cell.col        = col;
  const double u0 = static_cast<double>(col) * 100.0;
  const double v0 = static_cast<double>(row) * 100.0;
  cell.polygon    = {
    {u0, v0}, {u0 + 100.0, v0}, {u0 + 100.0, v0 + 100.0}, {u0, v0 + 100.0}};
  return cell;
}

map_planner::PvMap make_grid_map() {
  map_planner::PvMap map;
  map.map_id                = 1;
  map.version               = 1;
  map.cell_model.inner_rows = 3;
  map.cell_model.inner_cols = 3;

  map_planner::Block block;
  block.block_id   = 1;
  block.rows       = 3;
  block.cols       = 3;
  block.grid       = {{1, 1, 1}, {1, 1, 1}, {1, 1, 1}};
  uint32_t cell_id = 1;
  for (int row = 0; row < 3; ++row) {
    for (int col = 0; col < 3; ++col) {
      block.cell_ids.push_back(cell_id);
      map.cells.push_back(make_cell(cell_id, 1, row, col));
      ++cell_id;
    }
  }
  map.blocks.push_back(block);
  return map;
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
    map_planner::Block block;
    block.block_id = block_id;
    block.rows     = 1;
    block.cols     = 2;
    block.grid     = {{1, 1}};
    for (int col = 0; col < 2; ++col) {
      block.cell_ids.push_back(cell_id);
      map.cells.push_back(make_cell(cell_id, block_id, 0, col));
      ++cell_id;
    }
    map.blocks.push_back(block);
  }

  for (size_t index = 0; index + 1 < block_count; ++index) {
    map_planner::Bridge bridge;
    bridge.bridge_id = static_cast<uint32_t>(index + 1);
    bridge.endpoints = {
      {static_cast<uint32_t>(index + 1), 0, 1, "u_max", 1, 5},
      {static_cast<uint32_t>(index + 2), 0, 0, "u_min", 1, 0}};
    bridge.centerline = {{0.0, 0.0}, {100.0, 0.0}};
    map.bridges.push_back(bridge);
  }
  return map;
}

std::filesystem::path repo_root() {
  return std::filesystem::path(__FILE__).parent_path().parent_path();
}

map_planner::PvMap import_example_complex_map() {
  map_planner::YamlMapImporter importer;
  return importer.import_from_file(
    (repo_root() / "config" / "example_map_complex.yaml").string());
}

map_planner::TransitPlanningRequest make_request() {
  map_planner::TransitPlanningRequest request;
  request.map_id                  = 1;
  request.map_version             = 1;
  request.start_pose.center       = {1, 1, 0, 1, 1};
  request.start_pose.heading      = map_planner::Heading::BlockUPositive;
  request.goal_pose.center        = {1, 1, 2, 1, 1};
  request.goal_pose.heading       = map_planner::Heading::BlockUPositive;
  request.require_goal_heading    = true;
  request.config.robot_length_cm  = 60.0;
  request.config.robot_width_cm   = 20.0;
  request.config.safety_margin_cm = 0.0;
  return request;
}

bool has_waypoint_type(
  const std::vector<map_planner::PathWaypoint> &waypoints,
  map_planner::WaypointType type) {
  return std::any_of(waypoints.begin(), waypoints.end(), [&](const auto &wp) {
    return wp.type == type;
  });
}

std::vector<size_t> waypoint_indices_of_type(
  const std::vector<map_planner::PathWaypoint> &waypoints,
  map_planner::WaypointType type) {
  std::vector<size_t> indices;
  for (size_t index = 0; index < waypoints.size(); ++index) {
    if (waypoints[index].type == type) {
      indices.push_back(index);
    }
  }
  return indices;
}

map_planner::TransitPlanningRequest make_bridge_request(
  size_t block_count = 2) {
  map_planner::TransitPlanningRequest request;
  request.map_id             = 1;
  request.map_version        = 1;
  request.start_pose.center  = {1, 0, 0, 1, 1};
  request.start_pose.heading = map_planner::Heading::BlockUPositive;
  request.goal_pose.center   = {static_cast<uint32_t>(block_count), 0, 1, 1, 4};
  request.goal_pose.heading  = map_planner::Heading::BlockUPositive;
  request.require_goal_heading    = true;
  request.config.robot_length_cm  = 40.0;
  request.config.robot_width_cm   = 20.0;
  request.config.safety_margin_cm = 0.0;
  return request;
}

void fill_and_expect_only_turn_waypoints_have_rotation(
  std::vector<map_planner::PathWaypoint> waypoints) {
  map_planner::fill_rotation_angles(waypoints);

  size_t non_zero_turn_count = 0;
  for (const auto &waypoint : waypoints) {
    if (waypoint.rotation_angle_deg != 0) {
      EXPECT_EQ(waypoint.type, map_planner::WaypointType::TurnInPlace)
        << "non-turn waypoint carried rotation";
      if (waypoint.type == map_planner::WaypointType::TurnInPlace) {
        ++non_zero_turn_count;
      }
    } else {
      EXPECT_NE(waypoint.type, map_planner::WaypointType::TurnInPlace)
        << "turn waypoint had zero rotation";
    }
  }
  EXPECT_GT(non_zero_turn_count, 0U);
}

}  // namespace

TEST(TransitPathPlanner, SameBlockStraightPathHasNoCleanWaypoints) {
  map_planner::MapRepository repository;
  repository.set_map(make_grid_map());

  const auto result =
    map_planner::TransitPathPlanner().plan(repository, make_request());

  EXPECT_TRUE(result.success) << result.message;
  EXPECT_FALSE(result.waypoints.empty());
  EXPECT_TRUE(std::none_of(
    result.waypoints.begin(), result.waypoints.end(), [](const auto &waypoint) {
      return waypoint.type == map_planner::WaypointType::Clean ||
             waypoint.brush_on;
    }));
  EXPECT_EQ(result.waypoints.back().center_inner_cell->cell_col, 2);
}

TEST(TransitPathPlanner, RequiresGoalHeadingWhenRequested) {
  map_planner::MapRepository repository;
  repository.set_map(make_grid_map());

  auto request                 = make_request();
  request.goal_pose.heading    = map_planner::Heading::BlockUNegative;
  request.require_goal_heading = true;
  const auto result =
    map_planner::TransitPathPlanner().plan(repository, request);

  EXPECT_TRUE(result.success) << result.message;
  ASSERT_TRUE(result.waypoints.back().heading.has_value());
  EXPECT_EQ(
    result.waypoints.back().type, map_planner::WaypointType::TurnInPlace);
  EXPECT_EQ(
    *result.waypoints.back().heading, map_planner::Heading::BlockUPositive);
  EXPECT_EQ(
    result.waypoints.back().rotation_angle_deg,
    map_planner::rotation_between_headings_deg(
      map_planner::Heading::BlockUPositive,
      map_planner::Heading::BlockUNegative));
  EXPECT_TRUE(has_waypoint_type(
    result.waypoints, map_planner::WaypointType::TurnInPlace));
}

TEST(TransitPathPlanner, PreferFewerTurnsWhenGoalHeadingIsFlexible) {
  map_planner::MapRepository repository;
  repository.set_map(make_grid_map());

  auto request                 = make_request();
  request.goal_pose.heading    = map_planner::Heading::BlockUNegative;
  request.require_goal_heading = false;
  const auto result =
    map_planner::TransitPathPlanner().plan(repository, request);

  EXPECT_TRUE(result.success) << result.message;
  ASSERT_TRUE(result.waypoints.back().heading.has_value());
  EXPECT_EQ(
    *result.waypoints.back().heading, map_planner::Heading::BlockUPositive);
  EXPECT_EQ(map_planner::turn_count(result.waypoints), 0U);
}

TEST(TransitPathPlanner, CrossBlockBridgeTransitHasBridgeWaypointsOnly) {
  map_planner::MapRepository repository;
  repository.set_map(make_line_bridge_map(2));

  const auto result =
    map_planner::TransitPathPlanner().plan(repository, make_bridge_request(2));

  EXPECT_TRUE(result.success) << result.message;
  EXPECT_TRUE(has_waypoint_type(
    result.waypoints, map_planner::WaypointType::ApproachBridge));
  EXPECT_TRUE(has_waypoint_type(
    result.waypoints, map_planner::WaypointType::BridgeCrossing));
  EXPECT_TRUE(has_waypoint_type(
    result.waypoints, map_planner::WaypointType::ReinitVision));
  EXPECT_TRUE(std::none_of(
    result.waypoints.begin(), result.waypoints.end(), [](const auto &waypoint) {
      return waypoint.type == map_planner::WaypointType::Clean ||
             waypoint.brush_on;
    }));
}

TEST(TransitPathPlanner, ReachesSourceNormalBeforeEnteringBridge) {
  map_planner::MapRepository repository;
  repository.set_map(make_line_bridge_map(2));

  const auto request = make_bridge_request(2);
  const auto result =
    map_planner::TransitPathPlanner().plan(repository, request);

  EXPECT_TRUE(result.success) << result.message;
  const auto approach_indices = waypoint_indices_of_type(
    result.waypoints, map_planner::WaypointType::ApproachBridge);
  ASSERT_FALSE(approach_indices.empty());
  ASSERT_GT(approach_indices.front(), 0U);
  ASSERT_TRUE(result.waypoints[approach_indices.front() - 1]
                .center_inner_cell.has_value());
  EXPECT_FALSE(map_planner::same_center(
    *result.waypoints[approach_indices.front() - 1].center_inner_cell,
    request.start_pose.center));
  EXPECT_EQ(
    result.waypoints[approach_indices.front() - 1].type,
    map_planner::WaypointType::Deadhead);
}

TEST(TransitPathPlanner, BoundaryBridgeEdgeIsOnlyUsedInsideBridgeTransition) {
  map_planner::MapRepository repository;
  repository.set_map(make_line_bridge_map(2));

  auto request                         = make_bridge_request(2);
  request.start_pose.center            = {1, 0, 0, 1, 2};
  request.goal_pose.center             = {2, 0, 1, 1, 3};
  request.config.robot_length_cm       = 140.0;
  request.config.front_roller_width_cm = 30.0;
  request.config.rear_roller_width_cm  = 30.0;
  request.config.robot_width_cm        = 20.0;
  request.config.safety_margin_cm      = 0.0;
  const auto result =
    map_planner::TransitPathPlanner().plan(repository, request);

  EXPECT_TRUE(result.success) << result.message;
  const auto approach_indices = waypoint_indices_of_type(
    result.waypoints, map_planner::WaypointType::ApproachBridge);
  ASSERT_EQ(approach_indices.size(), 2U);
  const auto boundary_center = map_planner::CenterInnerCell{1, 0, 1, 1, 4};
  const auto bridge_edge     = map_planner::CenterInnerCell{1, 0, 1, 1, 5};
  EXPECT_TRUE(map_planner::same_center(
    *result.waypoints[approach_indices.front()].center_inner_cell,
    boundary_center));
  EXPECT_TRUE(map_planner::same_center(
    *result.waypoints[approach_indices.back()].center_inner_cell, bridge_edge));

  const auto map = repository.map();
  const auto center_grid =
    map_planner::CenterGridBuilder().build(map, repository, request.config);
  EXPECT_EQ(
    center_grid.status(boundary_center, map_planner::Heading::BlockUPositive),
    map_planner::TraversabilityStatus::BlockedBoundary);
  EXPECT_EQ(
    center_grid.status(bridge_edge, map_planner::Heading::BlockUPositive),
    map_planner::TraversabilityStatus::BlockedBoundary);
  EXPECT_FALSE(center_grid.is_traversable(
    boundary_center, map_planner::Heading::BlockUPositive));
  EXPECT_FALSE(center_grid.is_traversable(
    bridge_edge, map_planner::Heading::BlockUPositive));
}

TEST(TransitPathPlanner, BoundaryBridgeEdgeWithoutStagingHasNoNormalAnchor) {
  map_planner::BridgeSideTransition side;
  side.bridge_id                  = 1;
  side.block_id                   = 1;
  side.bridge_edge_center         = {1, 0, 1, 1, 5};
  side.bridge_edge_pose.center    = side.bridge_edge_center;
  side.bridge_edge_pose.heading   = map_planner::Heading::BlockUPositive;
  side.approach_heading           = map_planner::Heading::BlockUPositive;
  side.uses_bridge_edge_exception = true;
  side.staging_pose               = std::nullopt;

  EXPECT_FALSE(map_planner::normal_pose_for_side(side, false).has_value());
  EXPECT_FALSE(map_planner::normal_pose_for_side(side, true).has_value());
}

TEST(TransitPathPlanner, HonorsAllowedBlockRestriction) {
  map_planner::MapRepository repository;
  repository.set_map(make_line_bridge_map(3));

  auto request              = make_bridge_request(3);
  request.allowed_block_ids = {1, 3};
  const auto result =
    map_planner::TransitPathPlanner().plan(repository, request);

  EXPECT_FALSE(result.success);
  EXPECT_EQ(result.message, "goal is unreachable");
}

TEST(TransitPathPlanner, KeepsExactGoalWhenHeadingIsRequired) {
  map_planner::MapRepository repository;
  repository.set_map(make_line_bridge_map(2));

  auto request              = make_bridge_request(2);
  request.goal_pose.heading = map_planner::Heading::BlockUNegative;
  const auto result =
    map_planner::TransitPathPlanner().plan(repository, request);

  EXPECT_TRUE(result.success) << result.message;
  ASSERT_TRUE(result.waypoints.back().center_inner_cell.has_value());
  ASSERT_TRUE(result.waypoints.back().heading.has_value());
  EXPECT_TRUE(map_planner::same_center(
    *result.waypoints.back().center_inner_cell, request.goal_pose.center));
  EXPECT_EQ(
    result.waypoints.back().type, map_planner::WaypointType::TurnInPlace);
  EXPECT_NE(*result.waypoints.back().heading, request.goal_pose.heading);
  EXPECT_EQ(
    result.waypoints.back().rotation_angle_deg,
    map_planner::rotation_between_headings_deg(
      *result.waypoints.back().heading, request.goal_pose.heading));
}

TEST(TransitPathPlanner, ExampleComplexMapCarriesRotationOnlyOnTurns) {
  map_planner::MapRepository repository;
  repository.set_map(import_example_complex_map());

  map_planner::TransitPlanningRequest request;
  request.map_id                  = 2;
  request.map_version             = 1;
  request.start_pose.center       = {1, 0, 0, 1, 1};
  request.start_pose.heading      = map_planner::Heading::BlockUPositive;
  request.goal_pose.center        = {5, 0, 1, 1, 4};
  request.goal_pose.heading       = map_planner::Heading::BlockUNegative;
  request.require_goal_heading    = true;
  request.config.robot_length_cm  = 40.0;
  request.config.robot_width_cm   = 20.0;
  request.config.safety_margin_cm = 0.0;

  const auto result =
    map_planner::TransitPathPlanner().plan(repository, request);

  ASSERT_TRUE(result.success) << result.message;
  EXPECT_TRUE(has_waypoint_type(
    result.waypoints, map_planner::WaypointType::BridgeCrossing));
  fill_and_expect_only_turn_waypoints_have_rotation(result.waypoints);
}
