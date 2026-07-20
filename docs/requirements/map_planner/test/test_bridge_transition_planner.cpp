#include <gtest/gtest.h>

#include "map_planner/planning/bridge_transition_planner.hpp"
#include "map_planner/planning/center_grid.hpp"

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

map_planner::PvMap make_two_block_bridge_map() {
  map_planner::PvMap map;
  map.map_id                = 1;
  map.version               = 1;
  map.cell_model.inner_rows = 3;
  map.cell_model.inner_cols = 6;

  map_planner::Block block1;
  block1.block_id = 1;
  block1.rows     = 1;
  block1.cols     = 2;
  block1.grid     = {{1, 1}};
  block1.cell_ids = {1, 2};

  map_planner::Block block2;
  block2.block_id = 2;
  block2.rows     = 1;
  block2.cols     = 2;
  block2.grid     = {{1, 1}};
  block2.cell_ids = {3, 4};

  map.blocks = {block1, block2};
  map.cells  = {
    make_cell(1, 1, 0, 0, 0.0), make_cell(2, 1, 0, 1, 0.0),
    make_cell(3, 2, 0, 0, 520.0), make_cell(4, 2, 0, 1, 520.0)};

  map_planner::Bridge bridge;
  bridge.bridge_id  = 7;
  bridge.endpoints  = {{1, 0, 1, "u_max", 1, 5}, {2, 0, 0, "u_min", 1, 0}};
  bridge.centerline = {{420.0, 52.5}, {520.0, 52.5}};
  map.bridges       = {bridge};
  return map;
}

}  // namespace

TEST(
  BridgeTransitionPlanner,
  AllowsBoundaryBridgeEdgeWithoutMarkingCenterGridFree) {
  auto map = make_two_block_bridge_map();
  map_planner::MapRepository repository;
  repository.set_map(map);

  map_planner::RobotPlanningConfig config;
  config.robot_length_cm  = 120.0;
  config.robot_width_cm   = 70.0;
  config.safety_margin_cm = 10.0;

  const auto center_grid = map_planner::CenterGridBuilder().build(
    repository.map(), repository, config);
  const map_planner::CenterInnerCell edge_center{1, 0, 1, 1, 5};
  EXPECT_EQ(
    center_grid.status(edge_center, map_planner::Heading::BlockUPositive),
    map_planner::TraversabilityStatus::BlockedBoundary);

  const auto transitions =
    map_planner::BridgeTransitionPlanner().make_transitions(
      repository.map(), repository, center_grid, config);
  ASSERT_EQ(transitions.size(), 1U);
  EXPECT_TRUE(transitions[0].usable) << transitions[0].message;
  ASSERT_EQ(transitions[0].sides.size(), 2U);
  EXPECT_TRUE(transitions[0].sides[0].usable)
    << transitions[0].sides[0].message;
  EXPECT_TRUE(transitions[0].sides[0].uses_bridge_edge_exception);
  EXPECT_TRUE(transitions[0].sides[0].staging_pose.has_value());
}

TEST(BridgeTransitionPlanner, EnablesConfiguredBridgeTransitions) {
  auto map = make_two_block_bridge_map();
  map_planner::MapRepository repository;
  repository.set_map(map);

  map_planner::RobotPlanningConfig config;

  const auto center_grid = map_planner::CenterGridBuilder().build(
    repository.map(), repository, config);
  const auto transitions =
    map_planner::BridgeTransitionPlanner().make_transitions(
      repository.map(), repository, center_grid, config);
  ASSERT_EQ(transitions.size(), 1U);
  EXPECT_TRUE(transitions[0].usable) << transitions[0].message;
}
