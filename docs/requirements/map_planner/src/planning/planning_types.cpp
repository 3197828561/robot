#include "map_planner/planning/planning_types.hpp"

#include <algorithm>

namespace map_planner {

namespace {
double non_negative_cm(double value) { return std::max(0.0, value); }
}  // namespace

double effective_chassis_length_cm(const RobotPlanningConfig &config) {
  return std::max(
    0.0, non_negative_cm(config.robot_length_cm) -
           non_negative_cm(config.front_roller_width_cm) -
           non_negative_cm(config.rear_roller_width_cm));
}

std::string to_string(WaypointType type) {
  switch (type) {
    case WaypointType::Clean:
      return "clean";
    case WaypointType::Deadhead:
      return "deadhead";
    case WaypointType::TurnInPlace:
      return "turn_in_place";
    case WaypointType::ApproachBridge:
      return "approach_bridge";
    case WaypointType::BridgeCrossing:
      return "bridge_crossing";
    case WaypointType::ReinitVision:
      return "reinit_vision";
  }
  return "unknown";
}

std::string to_string(Heading heading) {
  switch (heading) {
    case Heading::BlockUPositive:
      return "block_u_positive";
    case Heading::BlockUNegative:
      return "block_u_negative";
    case Heading::BlockVPositive:
      return "block_v_positive";
    case Heading::BlockVNegative:
      return "block_v_negative";
  }
  return "unknown";
}

std::string to_string(SweepAxis axis) {
  switch (axis) {
    case SweepAxis::BlockU:
      return "block_u";
    case SweepAxis::BlockV:
      return "block_v";
  }
  return "unknown";
}

int heading_angle_deg(Heading h) {
  // vision heading_error convention: positive = right turn (FRD)
  // U+=forward(0°)  V-=right(+90°)  U-=back(±180°)  V+=left(-90°)
  switch (h) {
    case Heading::BlockUPositive:
      return 0;
    case Heading::BlockVNegative:
      return 90;
    case Heading::BlockUNegative:
      return 180;
    case Heading::BlockVPositive:
      return -90;
  }
  return 0;
}

int rotation_between_headings_deg(Heading from, Heading to) {
  if (from == to) {
    return 0;
  }
  const int from_deg = heading_angle_deg(from);
  const int to_deg   = heading_angle_deg(to);
  // shortest angle, positive = right turn (FRD, matches vision heading_error)
  return (to_deg - from_deg + 540) % 360 - 180;
}

void fill_rotation_angles(std::vector<PathWaypoint> &waypoints) {
  if (waypoints.empty()) {
    return;
  }
  for (size_t i = 0; i + 1U < waypoints.size(); ++i) {
    if (
      waypoints[i].heading.has_value() &&
      waypoints[i + 1].heading.has_value()) {
      waypoints[i].rotation_angle_deg = rotation_between_headings_deg(
        *waypoints[i].heading, *waypoints[i + 1].heading);
    }
  }
}

}  // namespace map_planner
