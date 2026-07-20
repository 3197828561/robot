#include "map_planner/planning_ros_adapter.hpp"

namespace map_planner {
namespace {

RobotPlanningConfig coverage_config_from_request(
  const srv::PlanCoveragePath::Request &request) {
  RobotPlanningConfig config;
  config.robot_length_cm        = request.robot_length_cm;
  config.front_roller_width_cm  = request.front_roller_width_cm;
  config.rear_roller_width_cm   = request.rear_roller_width_cm;
  config.robot_width_cm         = request.robot_width_cm;
  config.safety_margin_cm       = request.safety_margin_cm;
  config.cleaning_width_cm      = request.cleaning_width_cm;
  config.overlap_ratio          = request.overlap_ratio;
  config.enable_tail_coverage   = request.enable_tail_coverage;
  config.planning_search_effort = request.planning_search_effort.empty()
                                    ? "balanced"
                                    : request.planning_search_effort;
  config.debug_score_breakdown  = request.debug_score_breakdown;
  return config;
}

RobotPlanningConfig transit_config_from_request(
  const srv::PlanTransitPath::Request &request) {
  RobotPlanningConfig config;
  config.robot_length_cm        = request.robot_length_cm;
  config.front_roller_width_cm  = request.front_roller_width_cm;
  config.rear_roller_width_cm   = request.rear_roller_width_cm;
  config.robot_width_cm         = request.robot_width_cm;
  config.safety_margin_cm       = request.safety_margin_cm;
  config.planning_search_effort = request.planning_search_effort.empty()
                                    ? "balanced"
                                    : request.planning_search_effort;
  config.debug_score_breakdown  = request.debug_score_breakdown;
  return config;
}

GridPose make_pose(
  uint32_t block_id, int cell_row, int cell_col, int inner_row, int inner_col,
  uint8_t heading) {
  return GridPose{
    CenterInnerCell{block_id, cell_row, cell_col, inner_row, inner_col},
    static_cast<Heading>(heading)};
}

template <typename ResponseT>
void fill_planned_path(
  const PlanningResult &result, const PvMap &map, const std::string &frame_id,
  ResponseT &response) {
  response.success              = result.success;
  response.message              = result.message;
  response.path.header.frame_id = frame_id;
  response.path.map_id          = map.map_id;
  response.path.map_version     = map.version;
  response.path.debug           = PlanningRosAdapter::to_msg(result.debug);

  // compute rotation angles before converting to ROS msgs
  auto waypoints = result.waypoints;
  fill_rotation_angles(waypoints);

  response.path.waypoints.clear();
  for (const auto &waypoint : waypoints) {
    response.path.waypoints.push_back(PlanningRosAdapter::to_msg(waypoint));
  }
}

}  // namespace

PlanningRequest PlanningRosAdapter::from_request(
  const srv::PlanCoveragePath::Request &request) {
  PlanningRequest planning_request;
  planning_request.map_id      = request.map_id;
  planning_request.map_version = request.map_version;
  planning_request.start_pose  = make_pose(
    request.start_block_id, request.start_cell_row, request.start_cell_col,
    request.start_inner_row, request.start_inner_col, request.start_heading);
  planning_request.target_block_ids = request.target_block_ids;
  planning_request.global_plan      = request.global_plan;
  planning_request.config           = coverage_config_from_request(request);
  return planning_request;
}

TransitPlanningRequest PlanningRosAdapter::from_request(
  const srv::PlanTransitPath::Request &request) {
  TransitPlanningRequest planning_request;
  planning_request.map_id      = request.map_id;
  planning_request.map_version = request.map_version;
  planning_request.start_pose  = make_pose(
    request.start_block_id, request.start_cell_row, request.start_cell_col,
    request.start_inner_row, request.start_inner_col, request.start_heading);
  planning_request.goal_pose = make_pose(
    request.goal_block_id, request.goal_cell_row, request.goal_cell_col,
    request.goal_inner_row, request.goal_inner_col, request.goal_heading);
  planning_request.require_goal_heading = request.require_goal_heading;
  planning_request.allowed_block_ids    = request.allowed_block_ids;
  planning_request.config               = transit_config_from_request(request);
  return planning_request;
}

void PlanningRosAdapter::fill_response(
  const PlanningResult &result, const PvMap &map, const std::string &frame_id,
  srv::PlanCoveragePath::Response &response) {
  fill_planned_path(result, map, frame_id, response);
}

void PlanningRosAdapter::fill_response(
  const PlanningResult &result, const PvMap &map, const std::string &frame_id,
  srv::PlanTransitPath::Response &response) {
  fill_planned_path(result, map, frame_id, response);
}

msg::PathWaypoint PlanningRosAdapter::to_msg(const PathWaypoint &waypoint) {
  msg::PathWaypoint msg;
  msg.type      = to_msg(waypoint.type);
  msg.brush_on  = waypoint.brush_on;
  msg.block_id  = waypoint.center_inner_cell.has_value()
                    ? waypoint.center_inner_cell->block_id
                    : 0;
  msg.cell_row  = waypoint.center_inner_cell.has_value()
                    ? waypoint.center_inner_cell->cell_row
                    : -1;
  msg.cell_col  = waypoint.center_inner_cell.has_value()
                    ? waypoint.center_inner_cell->cell_col
                    : -1;
  msg.inner_row = waypoint.center_inner_cell.has_value()
                    ? waypoint.center_inner_cell->inner_row
                    : -1;
  msg.inner_col = waypoint.center_inner_cell.has_value()
                    ? waypoint.center_inner_cell->inner_col
                    : -1;
  msg.heading   = waypoint.heading.has_value() ? to_msg(*waypoint.heading) : 0;
  msg.bridge_id = waypoint.bridge_id.value_or(0);
  msg.rotation_angle_deg = waypoint.rotation_angle_deg;
  return msg;
}

msg::PlanningDebug PlanningRosAdapter::to_msg(const PlanningDebug &debug) {
  msg::PlanningDebug msg;
  msg.coverage_complete               = debug.coverage_complete;
  msg.selected_block_id               = debug.selected_block_id;
  msg.selected_sweep_axis             = to_string(debug.selected_sweep_axis);
  msg.selected_lane_stride            = debug.selected_lane_stride;
  msg.lane_offset                     = debug.lane_offset;
  msg.total_cost                      = debug.total_cost;
  msg.score_breakdown                 = debug.score_breakdown;
  msg.invalid_reasons                 = debug.invalid_reasons;
  msg.unreachable_segments            = debug.unreachable_segments;
  msg.unusable_bridges                = debug.unusable_bridges;
  msg.blocked_boundary_count          = debug.blocked_boundary_count;
  msg.blocked_missing_cell_count      = debug.blocked_missing_cell_count;
  msg.blocked_missing_inflation_count = debug.blocked_missing_inflation_count;
  msg.blocked_obstacle_count          = debug.blocked_obstacle_count;
  return msg;
}

uint8_t PlanningRosAdapter::to_msg(WaypointType type) {
  return static_cast<uint8_t>(type);
}

uint8_t PlanningRosAdapter::to_msg(Heading heading) {
  return static_cast<uint8_t>(heading);
}

}  // namespace map_planner
