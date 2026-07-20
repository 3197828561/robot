#include <gtest/gtest.h>

#include <cmath>

#include "map_planner/map/map_geometry.hpp"

namespace {

constexpr double kTolerance = 1e-6;

map_planner::Cell make_cell() {
  map_planner::Cell cell;
  cell.cell_id  = 1;
  cell.block_id = 1;
  cell.row      = 0;
  cell.col      = 0;
  cell.polygon  = {{0.0, 0.0}, {240.0, 0.0}, {240.0, 120.0}, {0.0, 120.0}};
  return cell;
}

}  // namespace

TEST(MapGeometry, InterpolatesCellPoint) {
  const auto cell = make_cell();
  const auto point =
    map_planner::map_geometry::interpolate_cell_point(cell, 0.5, 0.5);
  EXPECT_NEAR(point.u_cm, 120.0, kTolerance);
  EXPECT_NEAR(point.v_cm, 60.0, kTolerance);
}

TEST(MapGeometry, DerivesInnerCellGeometry) {
  const auto cell = make_cell();
  const auto geometry =
    map_planner::map_geometry::make_inner_cell_geometry(cell, 3, 6, 1, 3);
  EXPECT_NEAR(geometry.center.u_cm, 140.0, kTolerance);
  EXPECT_NEAR(geometry.center.v_cm, 60.0, kTolerance);
  EXPECT_NEAR(geometry.inner_u_size_cm, 40.0, kTolerance);
  EXPECT_NEAR(geometry.inner_v_size_cm, 40.0, kTolerance);
}

TEST(MapGeometry, DerivesBridgeEndpointAnchors) {
  const auto cell = make_cell();

  map_planner::BridgeEndpoint endpoint;
  endpoint.block_id  = 1;
  endpoint.cell_row  = 0;
  endpoint.cell_col  = 0;
  endpoint.inner_row = 1;
  endpoint.inner_col = 3;

  endpoint.edge = "u_min";
  auto point    = map_planner::map_geometry::derive_bridge_endpoint_anchor(
    cell, endpoint, 3, 6);
  EXPECT_NEAR(point.u_cm, 0.0, kTolerance);
  EXPECT_NEAR(point.v_cm, 60.0, kTolerance);

  endpoint.edge = "u_max";
  point         = map_planner::map_geometry::derive_bridge_endpoint_anchor(
    cell, endpoint, 3, 6);
  EXPECT_NEAR(point.u_cm, 240.0, kTolerance);
  EXPECT_NEAR(point.v_cm, 60.0, kTolerance);

  endpoint.edge = "v_min";
  point         = map_planner::map_geometry::derive_bridge_endpoint_anchor(
    cell, endpoint, 3, 6);
  EXPECT_NEAR(point.u_cm, 140.0, kTolerance);
  EXPECT_NEAR(point.v_cm, 0.0, kTolerance);

  endpoint.edge = "v_max";
  point         = map_planner::map_geometry::derive_bridge_endpoint_anchor(
    cell, endpoint, 3, 6);
  EXPECT_NEAR(point.u_cm, 140.0, kTolerance);
  EXPECT_NEAR(point.v_cm, 120.0, kTolerance);
}
