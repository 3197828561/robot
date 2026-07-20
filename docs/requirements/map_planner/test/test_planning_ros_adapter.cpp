#include <gtest/gtest.h>

#include <vector>

#include "map_planner/planning_ros_adapter.hpp"

TEST(PlanningRosAdapter, ConvertsPlanCoveragePathRequest) {
  map_planner::srv::PlanCoveragePath::Request request;
  request.map_id                = 1;
  request.map_version           = 7;
  request.start_block_id        = 2;
  request.start_cell_row        = 3;
  request.start_cell_col        = 4;
  request.start_inner_row       = 1;
  request.start_inner_col       = 5;
  request.start_heading         = 2;
  request.target_block_ids      = {2, 4};
  request.global_plan           = true;
  request.robot_length_cm       = 120.0;
  request.front_roller_width_cm = 18.0;
  request.rear_roller_width_cm  = 22.0;
  request.robot_width_cm        = 70.0;
  request.safety_margin_cm      = 10.0;
  request.cleaning_width_cm     = 55.0;
  request.overlap_ratio         = 0.2;
  request.enable_tail_coverage  = true;

  const auto planning_request =
    map_planner::PlanningRosAdapter::from_request(request);

  EXPECT_EQ(planning_request.map_id, 1U);
  EXPECT_EQ(planning_request.map_version, 7U);
  EXPECT_EQ(planning_request.start_pose.center.block_id, 2U);
  EXPECT_EQ(planning_request.start_pose.center.cell_row, 3);
  EXPECT_EQ(planning_request.start_pose.center.cell_col, 4);
  EXPECT_EQ(planning_request.start_pose.center.inner_row, 1);
  EXPECT_EQ(planning_request.start_pose.center.inner_col, 5);
  EXPECT_EQ(
    planning_request.start_pose.heading, map_planner::Heading::BlockVPositive);
  EXPECT_EQ(planning_request.target_block_ids.size(), 2U);
  EXPECT_TRUE(planning_request.global_plan);
  EXPECT_DOUBLE_EQ(planning_request.config.robot_length_cm, 120.0);
  EXPECT_DOUBLE_EQ(planning_request.config.front_roller_width_cm, 18.0);
  EXPECT_DOUBLE_EQ(planning_request.config.rear_roller_width_cm, 22.0);
}

TEST(PlanningRosAdapter, ConvertsPlanTransitPathRequest) {
  map_planner::srv::PlanTransitPath::Request request;
  request.map_id                = 1;
  request.map_version           = 8;
  request.start_block_id        = 2;
  request.start_cell_row        = 0;
  request.start_cell_col        = 1;
  request.start_inner_row       = 1;
  request.start_inner_col       = 2;
  request.start_heading         = 0;
  request.goal_block_id         = 3;
  request.goal_cell_row         = 1;
  request.goal_cell_col         = 2;
  request.goal_inner_row        = 0;
  request.goal_inner_col        = 5;
  request.goal_heading          = 1;
  request.require_goal_heading  = true;
  request.allowed_block_ids     = {2, 3};
  request.robot_length_cm       = 130.0;
  request.front_roller_width_cm = 20.0;
  request.rear_roller_width_cm  = 24.0;
  request.robot_width_cm        = 70.0;
  request.safety_margin_cm      = 10.0;

  const auto planning_request =
    map_planner::PlanningRosAdapter::from_request(request);

  EXPECT_EQ(planning_request.map_id, 1U);
  EXPECT_EQ(planning_request.map_version, 8U);
  EXPECT_EQ(planning_request.start_pose.center.block_id, 2U);
  EXPECT_EQ(planning_request.goal_pose.center.block_id, 3U);
  EXPECT_EQ(
    planning_request.goal_pose.heading, map_planner::Heading::BlockUNegative);
  EXPECT_TRUE(planning_request.require_goal_heading);
  EXPECT_EQ(planning_request.allowed_block_ids.size(), 2U);
  EXPECT_DOUBLE_EQ(planning_request.config.robot_length_cm, 130.0);
  EXPECT_DOUBLE_EQ(planning_request.config.front_roller_width_cm, 20.0);
  EXPECT_DOUBLE_EQ(planning_request.config.rear_roller_width_cm, 24.0);
}

TEST(PlanningTypes, RotationAngleIsCarriedByTurnWaypoint) {
  const map_planner::CenterInnerCell center{1, 0, 0, 1, 1};
  std::vector<map_planner::PathWaypoint> waypoints{
    {map_planner::WaypointType::Deadhead, center,
     map_planner::Heading::BlockUPositive, false, std::nullopt},
    {map_planner::WaypointType::TurnInPlace, center,
     map_planner::Heading::BlockUPositive, false, std::nullopt},
    {map_planner::WaypointType::Deadhead, center,
     map_planner::Heading::BlockVPositive, false, std::nullopt}};

  map_planner::fill_rotation_angles(waypoints);

  EXPECT_EQ(waypoints[0].rotation_angle_deg, 0);
  EXPECT_EQ(
    waypoints[1].rotation_angle_deg, map_planner::rotation_between_headings_deg(
                                       map_planner::Heading::BlockUPositive,
                                       map_planner::Heading::BlockVPositive));
  EXPECT_EQ(waypoints[2].rotation_angle_deg, 0);
}

TEST(PlanningTypes, FinalTurnKeepsGeneratedRotationAngle) {
  const map_planner::CenterInnerCell center{1, 0, 0, 1, 1};
  const int turn_angle = map_planner::rotation_between_headings_deg(
    map_planner::Heading::BlockUPositive, map_planner::Heading::BlockUNegative);
  std::vector<map_planner::PathWaypoint> waypoints{
    {map_planner::WaypointType::Deadhead, center,
     map_planner::Heading::BlockUPositive, false, std::nullopt},
    {map_planner::WaypointType::TurnInPlace, center,
     map_planner::Heading::BlockUPositive, false, std::nullopt, turn_angle}};

  map_planner::fill_rotation_angles(waypoints);

  EXPECT_EQ(waypoints[0].rotation_angle_deg, 0);
  EXPECT_EQ(waypoints[1].rotation_angle_deg, turn_angle);
}
