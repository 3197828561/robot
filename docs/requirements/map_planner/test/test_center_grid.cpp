#include <gtest/gtest.h>

#include <vector>

#include "map_planner/planning/center_grid.hpp"

namespace {

map_planner::Cell make_cell(
  uint32_t cell_id, uint32_t block_id, int row, int col) {
  map_planner::Cell cell;
  cell.cell_id  = cell_id;
  cell.block_id = block_id;
  cell.row      = row;
  cell.col      = col;
  cell.polygon  = {{0.0, 0.0}, {100.0, 0.0}, {100.0, 100.0}, {0.0, 100.0}};
  return cell;
}

map_planner::PvMap make_single_cell_map() {
  map_planner::PvMap map;
  map.map_id                = 1;
  map.version               = 1;
  map.cell_model.inner_rows = 5;
  map.cell_model.inner_cols = 5;

  map_planner::Block block;
  block.block_id = 1;
  block.rows     = 1;
  block.cols     = 1;
  block.grid     = {{1}};
  block.cell_ids = {1};

  map.blocks = {block};
  map.cells  = {make_cell(1, 1, 0, 0)};
  return map;
}

map_planner::CenterGrid build_grid(
  const map_planner::RobotPlanningConfig &config) {
  map_planner::MapRepository repository;
  repository.set_map(make_single_cell_map());
  return map_planner::CenterGridBuilder().build(
    repository.map(), repository, config);
}

}  // namespace

TEST(CenterGrid, SubtractsFrontAndRearRollersForBoundaryInflationAlongU) {
  map_planner::RobotPlanningConfig config;
  config.robot_length_cm  = 120.0;
  config.robot_width_cm   = 20.0;
  config.safety_margin_cm = 0.0;

  const map_planner::CenterInnerCell near_u_boundary{1, 0, 0, 2, 1};
  const auto full_length_grid = build_grid(config);
  EXPECT_EQ(
    full_length_grid.status(
      near_u_boundary, map_planner::Heading::BlockUPositive),
    map_planner::TraversabilityStatus::BlockedBoundary);

  config.front_roller_width_cm = 40.0;
  config.rear_roller_width_cm  = 40.0;
  const auto chassis_grid      = build_grid(config);
  EXPECT_EQ(
    chassis_grid.status(near_u_boundary, map_planner::Heading::BlockUPositive),
    map_planner::TraversabilityStatus::Free);
}

TEST(CenterGrid, SubtractsFrontAndRearRollersForBoundaryInflationAlongV) {
  map_planner::RobotPlanningConfig config;
  config.robot_length_cm       = 120.0;
  config.front_roller_width_cm = 40.0;
  config.rear_roller_width_cm  = 40.0;
  config.robot_width_cm        = 20.0;
  config.safety_margin_cm      = 0.0;

  const map_planner::CenterInnerCell near_v_boundary{1, 0, 0, 1, 2};
  const auto chassis_grid = build_grid(config);
  EXPECT_EQ(
    chassis_grid.status(near_v_boundary, map_planner::Heading::BlockVPositive),
    map_planner::TraversabilityStatus::Free);

  config.front_roller_width_cm = 0.0;
  config.rear_roller_width_cm  = 0.0;
  const auto full_length_grid  = build_grid(config);
  EXPECT_EQ(
    full_length_grid.status(
      near_v_boundary, map_planner::Heading::BlockVPositive),
    map_planner::TraversabilityStatus::BlockedBoundary);
}

TEST(CenterGrid, ClampsChassisLengthWhenRollersExceedRobotLength) {
  map_planner::RobotPlanningConfig config;
  config.robot_length_cm       = 50.0;
  config.front_roller_width_cm = 40.0;
  config.rear_roller_width_cm  = 40.0;
  config.robot_width_cm        = 20.0;
  config.safety_margin_cm      = 0.0;

  EXPECT_DOUBLE_EQ(map_planner::effective_chassis_length_cm(config), 0.0);

  const auto grid = build_grid(config);
  const map_planner::CenterInnerCell near_u_boundary{1, 0, 0, 2, 0};
  EXPECT_EQ(
    grid.status(near_u_boundary, map_planner::Heading::BlockUPositive),
    map_planner::TraversabilityStatus::Free);
}
