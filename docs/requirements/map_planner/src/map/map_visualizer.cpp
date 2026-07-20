#include "map_planner/map/map_visualizer.hpp"

#include <algorithm>
#include <cmath>
#include <string>

#include "geometry_msgs/msg/point.hpp"
#include "rclcpp/rclcpp.hpp"
#include "std_msgs/msg/color_rgba.hpp"
#include "visualization_msgs/msg/marker.hpp"

namespace map_planner {
namespace {

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

}  // namespace

visualization_msgs::msg::MarkerArray MapVisualizer::make_marker_array(
  const PvMap &map, const MapRepository &repository,
  const std::string &frame_id, bool show_cell_labels) const {
  visualization_msgs::msg::MarkerArray markers;
  int id = 0;

  for (const auto &cell : map.cells) {
    auto marker = base_marker(
      frame_id, "pv_cells", id++, visualization_msgs::msg::Marker::LINE_STRIP);
    marker.scale.x = 0.02;
    marker.color   = color(0.35F, 0.75F, 1.0F, 1.0F);
    for (const auto &point : cell.polygon) {
      marker.points.push_back(to_marker_point(point, 0.01));
    }
    if (!cell.polygon.empty()) {
      marker.points.push_back(to_marker_point(cell.polygon.front(), 0.01));
    }
    markers.markers.push_back(marker);

    add_inner_grid_marker(markers, cell, map.cell_model, frame_id, id);

    if (show_cell_labels) {
      const auto center = polygon_center(cell.polygon);
      auto label        = base_marker(
        frame_id, "pv_cell_labels", id++,
        visualization_msgs::msg::Marker::TEXT_VIEW_FACING);
      label.pose.position = to_marker_point(center, 0.08);
      label.scale.z       = 0.18;
      label.color         = color(1.0F, 1.0F, 1.0F, 1.0F);
      label.text          = std::to_string(cell.block_id) + ":(" +
                   std::to_string(cell.row) + "," + std::to_string(cell.col) +
                   ")";
      markers.markers.push_back(label);
    }
  }

  for (const auto &block : map.blocks) {
    Point2D sum;
    size_t count = 0;
    for (const auto cell_id : block.cell_ids) {
      const auto *cell = repository.find_cell(cell_id);
      if (cell == nullptr) {
        continue;
      }
      const auto center = polygon_center(cell->polygon);
      sum.u_cm += center.u_cm;
      sum.v_cm += center.v_cm;
      ++count;
    }
    if (count == 0) {
      continue;
    }
    Point2D center{
      sum.u_cm / static_cast<double>(count),
      sum.v_cm / static_cast<double>(count)};
    auto label = base_marker(
      frame_id, "pv_blocks", id++,
      visualization_msgs::msg::Marker::TEXT_VIEW_FACING);
    label.pose.position = to_marker_point(center, 0.18);
    label.scale.z       = 0.28;
    label.color         = color(0.2F, 1.0F, 0.2F, 1.0F);
    label.text          = "block " + std::to_string(block.block_id);
    markers.markers.push_back(label);
  }

  for (const auto &bridge : map.bridges) {
    if (bridge.centerline.size() >= 2) {
      auto centerline = base_marker(
        frame_id, "pv_bridges", id++,
        visualization_msgs::msg::Marker::LINE_STRIP);
      centerline.scale.x = 0.06;
      centerline.color   = color(1.0F, 0.85F, 0.1F, 1.0F);
      for (const auto &point : bridge.centerline) {
        centerline.points.push_back(to_marker_point(point, 0.04));
      }
      markers.markers.push_back(centerline);
    }

    if (bridge.polygon.size() >= 3) {
      auto polygon = base_marker(
        frame_id, "pv_bridge_polygons", id++,
        visualization_msgs::msg::Marker::LINE_STRIP);
      polygon.scale.x = 0.03;
      polygon.color   = color(1.0F, 0.65F, 0.05F, 0.9F);
      for (const auto &point : bridge.polygon) {
        polygon.points.push_back(to_marker_point(point, 0.035));
      }
      polygon.points.push_back(to_marker_point(bridge.polygon.front(), 0.035));
      markers.markers.push_back(polygon);
    }

    for (const auto &endpoint : bridge.endpoints) {
      const auto endpoint_point =
        derive_endpoint_point(endpoint, repository, map.cell_model);
      auto anchor = base_marker(
        frame_id, "pv_bridge_endpoints", id++,
        visualization_msgs::msg::Marker::SPHERE);
      anchor.pose.position = to_marker_point(endpoint_point, 0.08);
      anchor.scale.x       = 0.12;
      anchor.scale.y       = 0.12;
      anchor.scale.z       = 0.08;
      anchor.color         = color(1.0F, 0.25F, 0.25F, 1.0F);
      markers.markers.push_back(anchor);
    }
  }

  return markers;
}

geometry_msgs::msg::Point MapVisualizer::to_marker_point(
  const Point2D &point, double z_m) {
  geometry_msgs::msg::Point marker_point;
  marker_point.x = point.u_cm / 100.0;
  marker_point.y = point.v_cm / 100.0;
  marker_point.z = z_m;
  return marker_point;
}

Point2D MapVisualizer::polygon_center(const std::vector<Point2D> &polygon) {
  Point2D center;
  if (polygon.empty()) {
    return center;
  }
  for (const auto &point : polygon) {
    center.u_cm += point.u_cm;
    center.v_cm += point.v_cm;
  }
  center.u_cm /= static_cast<double>(polygon.size());
  center.v_cm /= static_cast<double>(polygon.size());
  return center;
}

void MapVisualizer::add_inner_grid_marker(
  visualization_msgs::msg::MarkerArray &markers, const Cell &cell,
  const CellModel &cell_model, const std::string &frame_id, int &id) {
  if (
    cell.polygon.size() < 4 || cell_model.inner_rows <= 0 ||
    cell_model.inner_cols <= 0) {
    return;
  }

  auto grid = base_marker(
    frame_id, "pv_inner_grids", id++,
    visualization_msgs::msg::Marker::LINE_LIST);
  grid.scale.x = 0.008;
  grid.color   = color(0.85F, 0.85F, 0.85F, 0.75F);

  for (int col = 1; col < cell_model.inner_cols; ++col) {
    const double ratio =
      static_cast<double>(col) / static_cast<double>(cell_model.inner_cols);
    grid.points.push_back(
      to_marker_point(interpolate_rect_cell_point(cell, ratio, 0.0), 0.018));
    grid.points.push_back(
      to_marker_point(interpolate_rect_cell_point(cell, ratio, 1.0), 0.018));
  }

  for (int row = 1; row < cell_model.inner_rows; ++row) {
    const double ratio =
      static_cast<double>(row) / static_cast<double>(cell_model.inner_rows);
    grid.points.push_back(
      to_marker_point(interpolate_rect_cell_point(cell, 0.0, ratio), 0.018));
    grid.points.push_back(
      to_marker_point(interpolate_rect_cell_point(cell, 1.0, ratio), 0.018));
  }

  if (!grid.points.empty()) {
    markers.markers.push_back(grid);
  }
}

Point2D MapVisualizer::interpolate_rect_cell_point(
  const Cell &cell, double col_ratio, double row_ratio) {
  const auto &p00 = cell.polygon[0];
  const auto &p10 = cell.polygon[1];
  const auto &p11 = cell.polygon[2];
  const auto &p01 = cell.polygon[3];

  const Point2D bottom{
    p00.u_cm + (p10.u_cm - p00.u_cm) * col_ratio,
    p00.v_cm + (p10.v_cm - p00.v_cm) * col_ratio};
  const Point2D top{
    p01.u_cm + (p11.u_cm - p01.u_cm) * col_ratio,
    p01.v_cm + (p11.v_cm - p01.v_cm) * col_ratio};

  return Point2D{
    bottom.u_cm + (top.u_cm - bottom.u_cm) * row_ratio,
    bottom.v_cm + (top.v_cm - bottom.v_cm) * row_ratio};
}

Point2D MapVisualizer::derive_endpoint_point(
  const BridgeEndpoint &endpoint, const MapRepository &repository,
  const CellModel &cell_model) {
  const auto *cell = repository.find_cell(
    endpoint.block_id, endpoint.cell_row, endpoint.cell_col);
  if (cell == nullptr || cell->polygon.empty()) {
    return {};
  }

  double col_ratio = cell_model.inner_cols <= 1
                       ? 0.5
                       : (static_cast<double>(endpoint.inner_col) + 0.5) /
                           static_cast<double>(cell_model.inner_cols);
  double row_ratio = cell_model.inner_rows <= 1
                       ? 0.5
                       : (static_cast<double>(endpoint.inner_row) + 0.5) /
                           static_cast<double>(cell_model.inner_rows);

  if (endpoint.edge == "u_min") {
    col_ratio = 0.0;
  } else if (endpoint.edge == "u_max") {
    col_ratio = 1.0;
  } else if (endpoint.edge == "v_min") {
    row_ratio = 0.0;
  } else if (endpoint.edge == "v_max") {
    row_ratio = 1.0;
  }

  return interpolate_rect_cell_point(*cell, col_ratio, row_ratio);
}

}  // namespace map_planner
