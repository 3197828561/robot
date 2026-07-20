#include "map_planner/map/map_geometry.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <vector>

namespace map_planner::map_geometry {

namespace {

double median(std::vector<double> values) {
  if (values.empty()) {
    return 0.0;
  }
  std::sort(values.begin(), values.end());
  const auto mid = values.size() / 2;
  if (values.size() % 2 == 1) {
    return values[mid];
  }
  return (values[mid - 1] + values[mid]) / 2.0;
}

void validate_cell_polygon(const Cell &cell) {
  if (cell.polygon.size() < 4) {
    throw std::runtime_error("cell polygon must contain at least 4 points");
  }
}

}  // namespace

double distance_cm(const Point2D &a, const Point2D &b) {
  const double du = a.u_cm - b.u_cm;
  const double dv = a.v_cm - b.v_cm;
  return std::sqrt(du * du + dv * dv);
}

Point2D interpolate_cell_point(
  const Cell &cell, double u_ratio, double v_ratio) {
  validate_cell_polygon(cell);

  const auto &p00 = cell.polygon[0];
  const auto &p10 = cell.polygon[1];
  const auto &p11 = cell.polygon[2];
  const auto &p01 = cell.polygon[3];

  const Point2D bottom{
    p00.u_cm + (p10.u_cm - p00.u_cm) * u_ratio,
    p00.v_cm + (p10.v_cm - p00.v_cm) * u_ratio};
  const Point2D top{
    p01.u_cm + (p11.u_cm - p01.u_cm) * u_ratio,
    p01.v_cm + (p11.v_cm - p01.v_cm) * u_ratio};

  return Point2D{
    bottom.u_cm + (top.u_cm - bottom.u_cm) * v_ratio,
    bottom.v_cm + (top.v_cm - bottom.v_cm) * v_ratio};
}

InnerCellGeometry make_inner_cell_geometry(
  const Cell &cell, int inner_rows, int inner_cols, int inner_row,
  int inner_col) {
  if (inner_rows <= 0 || inner_cols <= 0) {
    throw std::runtime_error("inner_rows and inner_cols must be positive");
  }
  if (
    inner_row < 0 || inner_row >= inner_rows || inner_col < 0 ||
    inner_col >= inner_cols) {
    throw std::runtime_error("inner cell index is out of range");
  }

  const double u0 =
    static_cast<double>(inner_col) / static_cast<double>(inner_cols);
  const double u1 =
    static_cast<double>(inner_col + 1) / static_cast<double>(inner_cols);
  const double v0 =
    static_cast<double>(inner_row) / static_cast<double>(inner_rows);
  const double v1 =
    static_cast<double>(inner_row + 1) / static_cast<double>(inner_rows);

  InnerCellGeometry geometry;
  geometry.corner00 = interpolate_cell_point(cell, u0, v0);
  geometry.corner10 = interpolate_cell_point(cell, u1, v0);
  geometry.corner11 = interpolate_cell_point(cell, u1, v1);
  geometry.corner01 = interpolate_cell_point(cell, u0, v1);
  geometry.center =
    interpolate_cell_point(cell, (u0 + u1) / 2.0, (v0 + v1) / 2.0);
  geometry.inner_u_size_cm =
    (distance_cm(geometry.corner10, geometry.corner00) +
     distance_cm(geometry.corner11, geometry.corner01)) /
    2.0;
  geometry.inner_v_size_cm =
    (distance_cm(geometry.corner01, geometry.corner00) +
     distance_cm(geometry.corner11, geometry.corner10)) /
    2.0;
  return geometry;
}

Point2D derive_inner_cell_center(
  const Cell &cell, int inner_rows, int inner_cols, int inner_row,
  int inner_col) {
  return make_inner_cell_geometry(
           cell, inner_rows, inner_cols, inner_row, inner_col)
    .center;
}

Point2D derive_bridge_endpoint_anchor(
  const Cell &cell, const BridgeEndpoint &endpoint, int inner_rows,
  int inner_cols) {
  if (inner_rows <= 0 || inner_cols <= 0) {
    throw std::runtime_error("inner_rows and inner_cols must be positive");
  }
  if (
    endpoint.inner_row < 0 || endpoint.inner_row >= inner_rows ||
    endpoint.inner_col < 0 || endpoint.inner_col >= inner_cols) {
    throw std::runtime_error("bridge endpoint inner index is out of range");
  }

  double u_ratio = (static_cast<double>(endpoint.inner_col) + 0.5) /
                   static_cast<double>(inner_cols);
  double v_ratio = (static_cast<double>(endpoint.inner_row) + 0.5) /
                   static_cast<double>(inner_rows);

  if (endpoint.edge == "u_min") {
    u_ratio = 0.0;
  } else if (endpoint.edge == "u_max") {
    u_ratio = 1.0;
  } else if (endpoint.edge == "v_min") {
    v_ratio = 0.0;
  } else if (endpoint.edge == "v_max") {
    v_ratio = 1.0;
  } else {
    throw std::runtime_error(
      "unsupported bridge endpoint edge: " + endpoint.edge);
  }

  return interpolate_cell_point(cell, u_ratio, v_ratio);
}

BlockInnerCellSizeStats estimate_block_inner_cell_size_stats(
  const PvMap &map, const MapRepository &repository, const Block &block) {
  std::vector<double> u_sizes;
  std::vector<double> v_sizes;

  for (int row = 0; row < block.rows; ++row) {
    for (int col = 0; col < block.cols; ++col) {
      const auto *cell = repository.find_cell(block.block_id, row, col);
      if (cell == nullptr) {
        continue;
      }
      for (int inner_row = 0; inner_row < map.cell_model.inner_rows;
           ++inner_row) {
        for (int inner_col = 0; inner_col < map.cell_model.inner_cols;
             ++inner_col) {
          const auto geometry = make_inner_cell_geometry(
            *cell, map.cell_model.inner_rows, map.cell_model.inner_cols,
            inner_row, inner_col);
          if (geometry.inner_u_size_cm > 0.0) {
            u_sizes.push_back(geometry.inner_u_size_cm);
          }
          if (geometry.inner_v_size_cm > 0.0) {
            v_sizes.push_back(geometry.inner_v_size_cm);
          }
        }
      }
    }
  }

  return {median(u_sizes), median(v_sizes)};
}

}  // namespace map_planner::map_geometry
