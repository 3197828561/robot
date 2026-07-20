#include "map_planner/planning/planning_visualizer.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <optional>
#include <string>
#include <tuple>

#include "map_planner/map/map_geometry.hpp"
#include "rclcpp/rclcpp.hpp"
#include "std_msgs/msg/color_rgba.hpp"
#include "visualization_msgs/msg/marker.hpp"

namespace map_planner {
namespace {

constexpr double kDuplicateSegmentOffsetStepM = 0.05;
constexpr int kMaxVisibleDuplicateLanes       = 5;
constexpr double kDuplicatePointOffsetRadiusM = 0.045;
constexpr int kMaxVisiblePointLanes           = 6;
constexpr double kPi                          = 3.14159265358979323846;

enum class VisualPathLayer : uint8_t {
  Clean    = 0,
  Deadhead = 1,
};

struct CenterKey {
  uint32_t block_id{};
  int cell_row{};
  int cell_col{};
  int inner_row{};
  int inner_col{};

  bool operator<(const CenterKey &other) const {
    return std::tie(block_id, cell_row, cell_col, inner_row, inner_col) <
           std::tie(
             other.block_id, other.cell_row, other.cell_col, other.inner_row,
             other.inner_col);
  }
};

struct SegmentKey {
  VisualPathLayer layer{VisualPathLayer::Clean};
  CenterKey a;
  CenterKey b;

  bool operator<(const SegmentKey &other) const {
    return std::tie(layer, a, b) < std::tie(other.layer, other.a, other.b);
  }
};

struct PointKey {
  WaypointType type{WaypointType::Clean};
  CenterKey center;

  bool operator<(const PointKey &other) const {
    return std::tie(type, center) < std::tie(other.type, other.center);
  }
};

struct VisualWaypoint {
  size_t index{};
  WaypointType type{WaypointType::Clean};
  CenterInnerCell center;
  geometry_msgs::msg::Point point;
};

std_msgs::msg::ColorRGBA color(float r, float g, float b, float a) {
  std_msgs::msg::ColorRGBA c;
  c.r = r;
  c.g = g;
  c.b = b;
  c.a = a;
  return c;
}

visualization_msgs::msg::Marker base_marker(
  const std::string &frame_id, const std::string &ns, int id, int type) {
  visualization_msgs::msg::Marker marker;
  marker.header.frame_id    = frame_id;
  marker.header.stamp       = rclcpp::Clock().now();
  marker.ns                 = ns;
  marker.id                 = id;
  marker.type               = type;
  marker.action             = visualization_msgs::msg::Marker::ADD;
  marker.pose.orientation.w = 1.0;
  marker.lifetime           = rclcpp::Duration(0, 0);
  return marker;
}

geometry_msgs::msg::Point to_marker_point(const Point2D &point, double z_m) {
  geometry_msgs::msg::Point marker_point;
  marker_point.x = point.u_cm / 100.0;
  marker_point.y = point.v_cm / 100.0;
  marker_point.z = z_m;
  return marker_point;
}

double waypoint_z(WaypointType type) {
  switch (type) {
    case WaypointType::Clean:
      return 0.10;
    case WaypointType::Deadhead:
    case WaypointType::TurnInPlace:
    case WaypointType::ApproachBridge:
    case WaypointType::ReinitVision:
      return 0.24;
    case WaypointType::BridgeCrossing:
      return 0.32;
  }
  return 0.10;
}

bool is_deadhead_layer(WaypointType type) {
  return type == WaypointType::Deadhead || type == WaypointType::TurnInPlace ||
         type == WaypointType::ApproachBridge ||
         type == WaypointType::ReinitVision;
}

std::optional<VisualPathLayer> visual_layer(WaypointType type) {
  if (type == WaypointType::Clean) {
    return VisualPathLayer::Clean;
  }
  if (is_deadhead_layer(type)) {
    return VisualPathLayer::Deadhead;
  }
  return std::nullopt;
}

geometry_msgs::msg::Point to_label_point(
  const geometry_msgs::msg::Point &point, WaypointType type) {
  auto label_point = point;
  label_point.z    = waypoint_z(type) + 0.07;
  switch (type) {
    case WaypointType::Clean:
      label_point.x += 0.06;
      label_point.y += 0.06;
      break;
    case WaypointType::Deadhead:
      label_point.x -= 0.06;
      label_point.y -= 0.06;
      break;
    case WaypointType::TurnInPlace:
    case WaypointType::ApproachBridge:
    case WaypointType::BridgeCrossing:
    case WaypointType::ReinitVision:
      label_point.y += 0.08;
      break;
  }
  return label_point;
}

std_msgs::msg::ColorRGBA waypoint_label_color(WaypointType type) {
  switch (type) {
    case WaypointType::Clean:
      return color(0.45F, 1.0F, 0.45F, 1.0F);
    case WaypointType::Deadhead:
      return color(0.45F, 0.75F, 1.0F, 1.0F);
    case WaypointType::TurnInPlace:
      return color(1.0F, 0.9F, 0.1F, 1.0F);
    case WaypointType::ApproachBridge:
      return color(1.0F, 0.65F, 0.2F, 1.0F);
    case WaypointType::BridgeCrossing:
      return color(0.9F, 0.45F, 1.0F, 1.0F);
    case WaypointType::ReinitVision:
      return color(0.2F, 1.0F, 1.0F, 1.0F);
  }
  return color(1.0F, 1.0F, 1.0F, 1.0F);
}

std_msgs::msg::ColorRGBA waypoint_color(WaypointType type) {
  switch (type) {
    case WaypointType::Clean:
      return color(0.1F, 0.9F, 0.2F, 1.0F);
    case WaypointType::Deadhead:
      return color(0.2F, 0.45F, 1.0F, 1.0F);
    case WaypointType::TurnInPlace:
      return color(1.0F, 0.9F, 0.1F, 1.0F);
    case WaypointType::ApproachBridge:
      return color(1.0F, 0.55F, 0.1F, 1.0F);
    case WaypointType::BridgeCrossing:
      return color(0.8F, 0.2F, 1.0F, 1.0F);
    case WaypointType::ReinitVision:
      return color(0.1F, 1.0F, 1.0F, 1.0F);
  }
  return color(1.0F, 1.0F, 1.0F, 1.0F);
}

CenterKey center_key(const CenterInnerCell &center) {
  return {
    center.block_id, center.cell_row, center.cell_col, center.inner_row,
    center.inner_col};
}

bool same_center(const CenterInnerCell &a, const CenterInnerCell &b) {
  return a.block_id == b.block_id && a.cell_row == b.cell_row &&
         a.cell_col == b.cell_col && a.inner_row == b.inner_row &&
         a.inner_col == b.inner_col;
}

int manhattan_distance(
  const CenterInnerCell &a, const CenterInnerCell &b,
  const CellModel &cell_model) {
  if (a.block_id != b.block_id) {
    return std::numeric_limits<int>::max();
  }
  const int a_global_row = a.cell_row * cell_model.inner_rows + a.inner_row;
  const int a_global_col = a.cell_col * cell_model.inner_cols + a.inner_col;
  const int b_global_row = b.cell_row * cell_model.inner_rows + b.inner_row;
  const int b_global_col = b.cell_col * cell_model.inner_cols + b.inner_col;
  return std::abs(a_global_row - b_global_row) +
         std::abs(a_global_col - b_global_col);
}

bool is_adjacent(
  const CenterInnerCell &a, const CenterInnerCell &b,
  const CellModel &cell_model) {
  return manhattan_distance(a, b, cell_model) == 1;
}

bool is_same_or_adjacent(
  const CenterInnerCell &a, const CenterInnerCell &b,
  const CellModel &cell_model) {
  const auto distance = manhattan_distance(a, b, cell_model);
  return distance == 0 || distance == 1;
}

SegmentKey make_segment_key(
  VisualPathLayer layer, const CenterInnerCell &from,
  const CenterInnerCell &to) {
  auto a = center_key(from);
  auto b = center_key(to);
  if (b < a) {
    std::swap(a, b);
  }
  return {layer, a, b};
}

std::string layer_name(VisualPathLayer layer) {
  return layer == VisualPathLayer::Clean ? "clean" : "deadhead";
}

std::string point_type_name(WaypointType type) {
  switch (type) {
    case WaypointType::Clean:
      return "clean";
    case WaypointType::Deadhead:
      return "deadhead";
    case WaypointType::TurnInPlace:
      return "turn";
    case WaypointType::ApproachBridge:
      return "approach";
    case WaypointType::BridgeCrossing:
      return "bridge";
    case WaypointType::ReinitVision:
      return "reinit";
  }
  return "point";
}

double centered_lane_offset(
  size_t occurrence, size_t total, double step, int max_lanes) {
  if (total <= 1) {
    return 0.0;
  }
  const auto visible_lanes =
    static_cast<size_t>(std::min<int>(static_cast<int>(total), max_lanes));
  const auto lane            = occurrence % visible_lanes;
  const double centered_lane = static_cast<double>(lane) -
                               (static_cast<double>(visible_lanes) - 1.0) / 2.0;
  return centered_lane * step;
}

std::pair<geometry_msgs::msg::Point, geometry_msgs::msg::Point> offset_segment(
  geometry_msgs::msg::Point from, geometry_msgs::msg::Point to,
  size_t occurrence, size_t total) {
  const double offset = centered_lane_offset(
    occurrence, total, kDuplicateSegmentOffsetStepM, kMaxVisibleDuplicateLanes);
  if (offset == 0.0) {
    return {from, to};
  }

  const double dx     = to.x - from.x;
  const double dy     = to.y - from.y;
  const double length = std::sqrt(dx * dx + dy * dy);
  if (length <= 1e-6) {
    return {from, to};
  }

  const double normal_x = -dy / length;
  const double normal_y = dx / length;
  from.x += normal_x * offset;
  from.y += normal_y * offset;
  to.x += normal_x * offset;
  to.y += normal_y * offset;
  return {from, to};
}

geometry_msgs::msg::Point offset_point(
  geometry_msgs::msg::Point point, size_t occurrence, size_t total) {
  if (total <= 1) {
    return point;
  }
  const auto visible_lanes = static_cast<size_t>(
    std::min<int>(static_cast<int>(total), kMaxVisiblePointLanes));
  const auto lane = occurrence % visible_lanes;
  const double angle =
    2.0 * kPi * static_cast<double>(lane) / static_cast<double>(visible_lanes);
  point.x += kDuplicatePointOffsetRadiusM * std::cos(angle);
  point.y += kDuplicatePointOffsetRadiusM * std::sin(angle);
  return point;
}

void add_text_marker(
  std::vector<visualization_msgs::msg::Marker> &markers,
  const std::string &frame_id, const std::string &ns, int &id,
  const geometry_msgs::msg::Point &position,
  const std_msgs::msg::ColorRGBA &marker_color, const std::string &text,
  double scale_z) {
  auto label = base_marker(
    frame_id, ns, id++, visualization_msgs::msg::Marker::TEXT_VIEW_FACING);
  label.pose.position = position;
  label.scale.z       = scale_z;
  label.color         = marker_color;
  label.text          = text;
  markers.push_back(label);
}

std::optional<VisualWaypoint> make_visual_waypoint(
  size_t index, const PathWaypoint &waypoint, const PvMap &map,
  const MapRepository &repository) {
  if (!waypoint.center_inner_cell.has_value()) {
    return std::nullopt;
  }
  const auto &center = *waypoint.center_inner_cell;
  const auto *cell =
    repository.find_cell(center.block_id, center.cell_row, center.cell_col);
  if (cell == nullptr) {
    return std::nullopt;
  }
  const auto point = map_geometry::derive_inner_cell_center(
    *cell, map.cell_model.inner_rows, map.cell_model.inner_cols,
    center.inner_row, center.inner_col);
  return VisualWaypoint{
    index, waypoint.type, center,
    to_marker_point(point, waypoint_z(waypoint.type))};
}

void count_segment(
  std::map<SegmentKey, size_t> &counts, VisualPathLayer layer,
  const VisualWaypoint &from, const VisualWaypoint &to) {
  ++counts[make_segment_key(layer, from.center, to.center)];
}

}  // namespace

visualization_msgs::msg::MarkerArray PlanningVisualizer::make_marker_array(
  const PlanningResult &result, const PvMap &map,
  const MapRepository &repository, const std::string &frame_id) const {
  visualization_msgs::msg::MarkerArray markers;
  int id = 0;

  auto clear = base_marker(
    frame_id, "planned_path_clear", id++,
    visualization_msgs::msg::Marker::SPHERE);
  clear.action = visualization_msgs::msg::Marker::DELETEALL;
  markers.markers.push_back(clear);

  std::map<SegmentKey, size_t> segment_total_count;
  std::map<PointKey, size_t> point_total_count;
  std::optional<VisualWaypoint> previous_clean;
  std::optional<VisualWaypoint> previous_deadhead;

  for (size_t index = 0; index < result.waypoints.size(); ++index) {
    const auto &waypoint = result.waypoints[index];
    if (waypoint.type == WaypointType::BridgeCrossing) {
      previous_clean.reset();
      previous_deadhead.reset();
      continue;
    }

    const auto visual = make_visual_waypoint(index, waypoint, map, repository);
    if (!visual.has_value()) {
      previous_clean.reset();
      previous_deadhead.reset();
      continue;
    }

    ++point_total_count[PointKey{waypoint.type, center_key(visual->center)}];
    if (waypoint.type == WaypointType::Clean) {
      previous_deadhead.reset();
      if (
        previous_clean.has_value() &&
        is_adjacent(previous_clean->center, visual->center, map.cell_model)) {
        count_segment(
          segment_total_count, VisualPathLayer::Clean, *previous_clean,
          *visual);
      }
      previous_clean = *visual;
    } else if (is_deadhead_layer(waypoint.type)) {
      previous_clean.reset();
      if (
        previous_deadhead.has_value() &&
        is_same_or_adjacent(
          previous_deadhead->center, visual->center, map.cell_model) &&
        !same_center(previous_deadhead->center, visual->center)) {
        count_segment(
          segment_total_count, VisualPathLayer::Deadhead, *previous_deadhead,
          *visual);
      }
      previous_deadhead = *visual;
    } else {
      previous_clean.reset();
      previous_deadhead.reset();
    }
  }

  auto clean_lines = base_marker(
    frame_id, "planned_path_clean", id++,
    visualization_msgs::msg::Marker::LINE_LIST);
  clean_lines.scale.x = 0.045;
  clean_lines.color   = waypoint_color(WaypointType::Clean);
  auto deadhead_lines = base_marker(
    frame_id, "planned_path_deadhead", id++,
    visualization_msgs::msg::Marker::LINE_LIST);
  deadhead_lines.scale.x = 0.035;
  deadhead_lines.color   = waypoint_color(WaypointType::Deadhead);

  std::map<SegmentKey, size_t> segment_seen_count;
  std::map<PointKey, size_t> point_seen_count;
  std::optional<VisualWaypoint> last_clean;
  std::optional<VisualWaypoint> last_deadhead;

  for (size_t index = 0; index < result.waypoints.size(); ++index) {
    const auto &waypoint = result.waypoints[index];

    if (
      waypoint.type == WaypointType::BridgeCrossing &&
      waypoint.bridge_id.has_value()) {
      last_clean.reset();
      last_deadhead.reset();
      const auto bridge_it = std::find_if(
        map.bridges.begin(), map.bridges.end(), [&](const auto &bridge) {
          return bridge.bridge_id == *waypoint.bridge_id;
        });
      if (bridge_it != map.bridges.end() && bridge_it->centerline.size() >= 2) {
        auto bridge_line = base_marker(
          frame_id, "planned_path_bridge_crossing", id++,
          visualization_msgs::msg::Marker::LINE_STRIP);
        bridge_line.scale.x = 0.07;
        bridge_line.color   = waypoint_color(WaypointType::BridgeCrossing);
        for (const auto &point : bridge_it->centerline) {
          bridge_line.points.push_back(
            to_marker_point(point, waypoint_z(WaypointType::BridgeCrossing)));
        }
        markers.markers.push_back(bridge_line);
      }
      continue;
    }

    const auto visual = make_visual_waypoint(index, waypoint, map, repository);
    if (!visual.has_value()) {
      last_clean.reset();
      last_deadhead.reset();
      continue;
    }

    auto marker_point = visual->point;
    const PointKey point_key{waypoint.type, center_key(visual->center)};
    const auto point_total      = point_total_count[point_key];
    const auto point_occurrence = point_seen_count[point_key]++;
    marker_point = offset_point(marker_point, point_occurrence, point_total);

    if (waypoint.type == WaypointType::Clean) {
      last_deadhead.reset();
      if (
        last_clean.has_value() &&
        is_adjacent(last_clean->center, visual->center, map.cell_model)) {
        const auto key = make_segment_key(
          VisualPathLayer::Clean, last_clean->center, visual->center);
        const auto occurrence = segment_seen_count[key]++;
        const auto total      = segment_total_count[key];
        auto segment =
          offset_segment(last_clean->point, visual->point, occurrence, total);
        clean_lines.points.push_back(segment.first);
        clean_lines.points.push_back(segment.second);
      }
      last_clean = *visual;
    } else if (is_deadhead_layer(waypoint.type)) {
      last_clean.reset();
      if (
        last_deadhead.has_value() &&
        is_same_or_adjacent(
          last_deadhead->center, visual->center, map.cell_model) &&
        !same_center(last_deadhead->center, visual->center)) {
        const auto key = make_segment_key(
          VisualPathLayer::Deadhead, last_deadhead->center, visual->center);
        const auto occurrence = segment_seen_count[key]++;
        const auto total      = segment_total_count[key];
        auto segment          = offset_segment(
          last_deadhead->point, visual->point, occurrence, total);
        deadhead_lines.points.push_back(segment.first);
        deadhead_lines.points.push_back(segment.second);
      }
      last_deadhead = *visual;
    } else {
      last_clean.reset();
      last_deadhead.reset();
    }

    auto point_marker = base_marker(
      frame_id, "planned_path_points", id++,
      visualization_msgs::msg::Marker::SPHERE);
    point_marker.pose.position = marker_point;
    point_marker.scale.x       = 0.08;
    point_marker.scale.y       = 0.08;
    point_marker.scale.z       = 0.05;
    point_marker.color         = waypoint_color(waypoint.type);
    markers.markers.push_back(point_marker);

    add_text_marker(
      markers.markers, frame_id, "planned_path_labels", id,
      to_label_point(marker_point, waypoint.type),
      waypoint_label_color(waypoint.type), std::to_string(index), 0.10);
  }

  if (!clean_lines.points.empty()) {
    markers.markers.push_back(clean_lines);
  }
  if (!deadhead_lines.points.empty()) {
    markers.markers.push_back(deadhead_lines);
  }

  // Repeated segments/points are shown by visual offsets only. Keep text labels
  // limited to waypoint indices so the RViz view stays readable on dense paths.

  const auto first_planned = std::find_if(
    result.waypoints.begin(), result.waypoints.end(), [](const auto &waypoint) {
      return waypoint.center_inner_cell.has_value();
    });
  const auto last_planned = std::find_if(
    result.waypoints.rbegin(), result.waypoints.rend(),
    [](const auto &waypoint) {
      return waypoint.center_inner_cell.has_value();
    });

  auto add_endpoint_label = [&](
                              const CenterInnerCell &center,
                              const std::string &text,
                              const std_msgs::msg::ColorRGBA &endpoint_color) {
    const auto *cell =
      repository.find_cell(center.block_id, center.cell_row, center.cell_col);
    if (cell == nullptr) {
      return;
    }
    const auto point = map_geometry::derive_inner_cell_center(
      *cell, map.cell_model.inner_rows, map.cell_model.inner_cols,
      center.inner_row, center.inner_col);
    auto marker = base_marker(
      frame_id, "planned_path_endpoints", id++,
      visualization_msgs::msg::Marker::SPHERE);
    marker.pose.position = to_marker_point(point, 0.22);
    marker.scale.x       = 0.18;
    marker.scale.y       = 0.18;
    marker.scale.z       = 0.12;
    marker.color         = endpoint_color;
    markers.markers.push_back(marker);

    add_text_marker(
      markers.markers, frame_id, "planned_path_endpoint_labels", id,
      to_marker_point(point, 0.38), endpoint_color, text, 0.26);
  };

  if (first_planned != result.waypoints.end()) {
    add_endpoint_label(
      *first_planned->center_inner_cell, "START",
      color(0.1F, 1.0F, 0.1F, 1.0F));
  }
  if (last_planned != result.waypoints.rend()) {
    add_endpoint_label(
      *last_planned->center_inner_cell, "END", color(1.0F, 0.1F, 0.1F, 1.0F));
  }

  return markers;
}

}  // namespace map_planner
