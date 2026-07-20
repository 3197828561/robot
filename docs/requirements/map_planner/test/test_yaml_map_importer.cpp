#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <limits>
#include <string>

#include "map_planner/map/json_map_importer.hpp"
#include "map_planner/map/map_repository.hpp"
#include "map_planner/map/yaml_map_importer.hpp"

namespace {

constexpr double kEpsilon           = 1e-6;
constexpr double kGeometryTolerance = 0.2;

std::filesystem::path write_temp_file(
  const std::string &content, const std::string &extension) {
  const auto path = std::filesystem::temp_directory_path() /
                    ("map_planner_test_map" + extension);
  std::ofstream out(path);
  out << content;
  return path;
}

std::filesystem::path write_temp_yaml(const std::string &content) {
  return write_temp_file(content, ".yaml");
}

std::filesystem::path write_temp_json(const std::string &content) {
  return write_temp_file(content, ".json");
}

std::filesystem::path repo_root() {
  return std::filesystem::path(__FILE__).parent_path().parent_path();
}

std::string valid_yaml() {
  return R"yaml(
map_id: 1
version: 1
frame:
  unit: centimeter
cell_model:
  inner_rows: 3
  inner_cols: 6
blocks:
  - block_id: 1
    block_frame:
      block_origin: [0, 0]
      u_axis: [1.0, 0.0]
      v_axis: [0.0, 1.0]
    rows: 2
    cols: 3
    grid:
      - [1, 1, 1]
      - [1, 1, 0]
    cell_ids: [1, 2, 3, 4, 5]
    cleanable: true
  - block_id: 2
    block_frame:
      block_origin: [800, 0]
      u_axis: [1.0, 0.0]
      v_axis: [0.0, 1.0]
    rows: 1
    cols: 1
    grid:
      - [1]
    cell_ids: [6]
    cleanable: true
bridges:
  - bridge_id: 1
    source: cad
    endpoints:
      - block_id: 1
        cell_row: 0
        cell_col: 2
        edge: u_max
        inner_row: 1
        inner_col: 5
      - block_id: 2
        cell_row: 0
        cell_col: 0
        edge: u_min
        inner_row: 1
        inner_col: 0
    centerline:
      - [664, 55]
      - [800, 55]
    polygon:
      - [664, 15]
      - [800, 15]
      - [800, 95]
      - [664, 95]
cells:
  - cell_id: 1
    block_id: 1
    row: 0
    col: 0
    polygon: [[0, 0], [220, 0], [220, 110], [0, 110]]
  - cell_id: 2
    block_id: 1
    row: 0
    col: 1
    polygon: [[222, 0], [442, 0], [442, 110], [222, 110]]
  - cell_id: 3
    block_id: 1
    row: 0
    col: 2
    polygon: [[444, 0], [664, 0], [664, 110], [444, 110]]
  - cell_id: 4
    block_id: 1
    row: 1
    col: 0
    polygon: [[0, 112], [220, 112], [220, 222], [0, 222]]
  - cell_id: 5
    block_id: 1
    row: 1
    col: 1
    polygon: [[222, 112], [442, 112], [442, 222], [222, 222]]
  - cell_id: 6
    block_id: 2
    row: 0
    col: 0
    polygon: [[800, 0], [1020, 0], [1020, 110], [800, 110]]
)yaml";
}

std::string valid_json() {
  return R"json(
{
  "map_id": 1,
  "version": 1,
  "frame": {"unit": "centimeter"},
  "cell_model": {"inner_rows": 3, "inner_cols": 6},
  "blocks": [
    {
      "block_id": 1,
      "block_frame": {
        "block_origin": [0, 0],
        "u_axis": [1.0, 0.0],
        "v_axis": [0.0, 1.0]
      },
      "rows": 1,
      "cols": 1,
      "grid": [[1]],
      "cell_ids": [1],
      "cleanable": true
    }
  ],
  "bridges": [],
  "cells": [
    {
      "cell_id": 1,
      "block_id": 1,
      "row": 0,
      "col": 0,
      "polygon": [[0, 0], [220, 0], [220, 110], [0, 110]]
    }
  ]
}
)json";
}

map_planner::PvMap import_yaml(const std::string &yaml) {
  const auto path = write_temp_yaml(yaml);
  map_planner::YamlMapImporter importer;
  return importer.import_from_file(path.string());
}

map_planner::PvMap import_json(const std::string &json) {
  const auto path = write_temp_json(json);
  map_planner::JsonMapImporter importer;
  return importer.import_from_file(path.string());
}

std::vector<std::filesystem::path> example_map_paths() {
  std::vector<std::filesystem::path> paths;
  const auto config_dir = repo_root() / "config";
  for (const auto &entry : std::filesystem::directory_iterator(config_dir)) {
    if (!entry.is_regular_file()) {
      continue;
    }
    const auto extension = entry.path().extension().string();
    if (extension == ".yaml" || extension == ".yml" || extension == ".json") {
      paths.push_back(entry.path());
    }
  }
  std::sort(paths.begin(), paths.end());
  return paths;
}

map_planner::PvMap import_map_file(const std::filesystem::path &path) {
  map_planner::YamlMapImporter yaml_importer;
  if (yaml_importer.can_import(path.string())) {
    return yaml_importer.import_from_file(path.string());
  }

  map_planner::JsonMapImporter json_importer;
  if (json_importer.can_import(path.string())) {
    return json_importer.import_from_file(path.string());
  }

  throw std::runtime_error("unsupported map file extension: " + path.string());
}

const map_planner::Cell *find_cell(
  const map_planner::PvMap &map, uint32_t block_id, int row, int col) {
  const auto it =
    std::find_if(map.cells.begin(), map.cells.end(), [=](const auto &cell) {
      return cell.block_id == block_id && cell.row == row && cell.col == col;
    });
  return it == map.cells.end() ? nullptr : &(*it);
}

const map_planner::Block *find_block(
  const map_planner::PvMap &map, uint32_t block_id) {
  const auto it =
    std::find_if(map.blocks.begin(), map.blocks.end(), [=](const auto &block) {
      return block.block_id == block_id;
    });
  return it == map.blocks.end() ? nullptr : &(*it);
}

map_planner::Point2D add(
  const map_planner::Point2D &a, const map_planner::Point2D &b) {
  return {a.u_cm + b.u_cm, a.v_cm + b.v_cm};
}

map_planner::Point2D subtract(
  const map_planner::Point2D &a, const map_planner::Point2D &b) {
  return {a.u_cm - b.u_cm, a.v_cm - b.v_cm};
}

map_planner::Point2D scale(const map_planner::Point2D &a, double s) {
  return {a.u_cm * s, a.v_cm * s};
}

double dot(const map_planner::Point2D &a, const map_planner::Point2D &b) {
  return a.u_cm * b.u_cm + a.v_cm * b.v_cm;
}

double norm(const map_planner::Point2D &a) {
  return std::hypot(a.u_cm, a.v_cm);
}

double point_line_distance(
  const map_planner::Point2D &point, const map_planner::Point2D &line_a,
  const map_planner::Point2D &line_b) {
  const auto line   = subtract(line_b, line_a);
  const auto offset = subtract(point, line_a);
  return std::abs(line.u_cm * offset.v_cm - line.v_cm * offset.u_cm) /
         norm(line);
}

bool point_projects_on_segment(
  const map_planner::Point2D &point, const map_planner::Point2D &segment_a,
  const map_planner::Point2D &segment_b) {
  const auto segment      = subtract(segment_b, segment_a);
  const double projection = dot(subtract(point, segment_a), segment);
  const double segment_length_squared = dot(segment, segment);
  return projection >= -kGeometryTolerance &&
         projection <= segment_length_squared + kGeometryTolerance;
}

std::pair<map_planner::Point2D, map_planner::Point2D> edge_segment(
  const map_planner::Cell &cell, const std::string &edge) {
  if (edge == "u_min") {
    return {cell.polygon[0], cell.polygon[3]};
  }
  if (edge == "u_max") {
    return {cell.polygon[1], cell.polygon[2]};
  }
  if (edge == "v_min") {
    return {cell.polygon[0], cell.polygon[1]};
  }
  return {cell.polygon[2], cell.polygon[3]};
}

void expect_bridge_end_touches_cell_edge(
  const map_planner::Point2D &polygon_a, const map_planner::Point2D &polygon_b,
  const map_planner::Cell &cell, const std::string &edge) {
  const auto [edge_a, edge_b] = edge_segment(cell, edge);
  EXPECT_NEAR(
    point_line_distance(polygon_a, edge_a, edge_b), 0.0, kGeometryTolerance);
  EXPECT_NEAR(
    point_line_distance(polygon_b, edge_a, edge_b), 0.0, kGeometryTolerance);
  EXPECT_TRUE(point_projects_on_segment(polygon_a, edge_a, edge_b));
  EXPECT_TRUE(point_projects_on_segment(polygon_b, edge_a, edge_b));
}

map_planner::Point2D edge_midpoint(
  const map_planner::Cell &cell, const std::string &edge) {
  const auto [edge_a, edge_b] = edge_segment(cell, edge);
  return scale(add(edge_a, edge_b), 0.5);
}

void expect_bridge_sides_parallel_to_centerline(
  const map_planner::Bridge &bridge) {
  const auto centerline = subtract(bridge.centerline[1], bridge.centerline[0]);
  const auto side_a     = subtract(bridge.polygon[1], bridge.polygon[0]);
  const auto side_b     = subtract(bridge.polygon[2], bridge.polygon[3]);
  const double cross_a =
    centerline.u_cm * side_a.v_cm - centerline.v_cm * side_a.u_cm;
  const double cross_b =
    centerline.u_cm * side_b.v_cm - centerline.v_cm * side_b.u_cm;
  EXPECT_NEAR(
    cross_a, 0.0,
    kGeometryTolerance * std::max(norm(centerline), norm(side_a)));
  EXPECT_NEAR(
    cross_b, 0.0,
    kGeometryTolerance * std::max(norm(centerline), norm(side_b)));
}

void expect_centerline_in_bridge_middle(const map_planner::Bridge &bridge) {
  const auto expected_start =
    scale(add(bridge.polygon[0], bridge.polygon[3]), 0.5);
  const auto expected_end =
    scale(add(bridge.polygon[1], bridge.polygon[2]), 0.5);
  EXPECT_NEAR(
    bridge.centerline[0].u_cm, expected_start.u_cm, kGeometryTolerance);
  EXPECT_NEAR(
    bridge.centerline[0].v_cm, expected_start.v_cm, kGeometryTolerance);
  EXPECT_NEAR(bridge.centerline[1].u_cm, expected_end.u_cm, kGeometryTolerance);
  EXPECT_NEAR(bridge.centerline[1].v_cm, expected_end.v_cm, kGeometryTolerance);
}

map_planner::Point2D interpolate_rect_cell_point(
  const map_planner::Cell &cell, double col_ratio, double row_ratio) {
  const auto &p00 = cell.polygon[0];
  const auto &p10 = cell.polygon[1];
  const auto &p11 = cell.polygon[2];
  const auto &p01 = cell.polygon[3];

  const map_planner::Point2D bottom{
    p00.u_cm + (p10.u_cm - p00.u_cm) * col_ratio,
    p00.v_cm + (p10.v_cm - p00.v_cm) * col_ratio};
  const map_planner::Point2D top{
    p01.u_cm + (p11.u_cm - p01.u_cm) * col_ratio,
    p01.v_cm + (p11.v_cm - p01.v_cm) * col_ratio};

  return {
    bottom.u_cm + (top.u_cm - bottom.u_cm) * row_ratio,
    bottom.v_cm + (top.v_cm - bottom.v_cm) * row_ratio};
}

map_planner::Point2D bridge_edge_anchor_point(
  const map_planner::BridgeEndpoint &endpoint, const map_planner::Cell &cell,
  const map_planner::CellModel &cell_model) {
  double col_ratio = (static_cast<double>(endpoint.inner_col) + 0.5) /
                     static_cast<double>(cell_model.inner_cols);
  double row_ratio = (static_cast<double>(endpoint.inner_row) + 0.5) /
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

  return interpolate_rect_cell_point(cell, col_ratio, row_ratio);
}

double distance(const map_planner::Point2D &a, const map_planner::Point2D &b) {
  return std::hypot(a.u_cm - b.u_cm, a.v_cm - b.v_cm);
}

void expect_near_point(
  const map_planner::Point2D &point, const map_planner::Point2D &expected) {
  EXPECT_NEAR(point.u_cm, expected.u_cm, kGeometryTolerance);
  EXPECT_NEAR(point.v_cm, expected.v_cm, kGeometryTolerance);
}

}  // namespace

TEST(YamlMapImporter, ImportsValidMap) {
  const auto map = import_yaml(valid_yaml());
  EXPECT_EQ(map.map_id, 1U);
  EXPECT_EQ(map.blocks.size(), 2U);
  EXPECT_EQ(map.cells.size(), 6U);
  EXPECT_EQ(map.bridges.size(), 1U);
}

TEST(JsonMapImporter, CanImportJson) {
  map_planner::JsonMapImporter importer;
  EXPECT_TRUE(importer.can_import("map.json"));
  EXPECT_TRUE(importer.can_import("map.JSON"));
  EXPECT_FALSE(importer.can_import("map.yaml"));
  EXPECT_FALSE(importer.can_import("map.yml"));
}

TEST(JsonMapImporter, ImportsValidMap) {
  const auto map = import_json(valid_json());
  EXPECT_EQ(map.map_id, 1U);
  ASSERT_EQ(map.blocks.size(), 1U);
  ASSERT_EQ(map.cells.size(), 1U);
  EXPECT_EQ(map.blocks[0].block_id, 1U);
  EXPECT_EQ(map.cells[0].cell_id, 1U);
  EXPECT_TRUE(map.bridges.empty());
}

TEST(YamlMapImporter, ImportsExampleMaps) {
  const auto paths = example_map_paths();
  ASSERT_GT(paths.size(), 0U);
  for (const auto &path : paths) {
    SCOPED_TRACE(path.string());
    const auto map = import_map_file(path);
    EXPECT_GT(map.map_id, 0U);
    EXPECT_GT(map.blocks.size(), 0U);
    EXPECT_GT(map.cells.size(), 0U);
  }
}

TEST(YamlMapImporter, RejectsGridDimensionMismatch) {
  auto yaml      = valid_yaml();
  const auto pos = yaml.find("      - [1, 1, 0]");
  ASSERT_NE(pos, std::string::npos);
  yaml.replace(pos, std::string("      - [1, 1, 0]").size(), "      - [1, 1]");
  EXPECT_THROW(import_yaml(yaml), std::runtime_error);
}

TEST(YamlMapImporter, RejectsInvalidGridValue) {
  auto yaml      = valid_yaml();
  const auto pos = yaml.find("      - [1, 1, 0]");
  ASSERT_NE(pos, std::string::npos);
  yaml.replace(
    pos, std::string("      - [1, 1, 0]").size(), "      - [1, 2, 0]");
  EXPECT_THROW(import_yaml(yaml), std::runtime_error);
}

TEST(YamlMapImporter, RejectsCellOnMissingPanel) {
  auto yaml      = valid_yaml();
  const auto pos = yaml.find("row: 1\n    col: 1");
  ASSERT_NE(pos, std::string::npos);
  yaml.replace(
    pos, std::string("row: 1\n    col: 1").size(), "row: 1\n    col: 2");
  EXPECT_THROW(import_yaml(yaml), std::runtime_error);
}

TEST(YamlMapImporter, RejectsCellOutOfRange) {
  auto yaml      = valid_yaml();
  const auto pos = yaml.find("row: 1\n    col: 1");
  ASSERT_NE(pos, std::string::npos);
  yaml.replace(
    pos, std::string("row: 1\n    col: 1").size(), "row: 3\n    col: 1");
  EXPECT_THROW(import_yaml(yaml), std::runtime_error);
}

TEST(YamlMapImporter, RejectsBridgeEndpointOnMissingPanel) {
  auto yaml      = valid_yaml();
  const auto pos = yaml.find("cell_row: 0\n        cell_col: 2");
  ASSERT_NE(pos, std::string::npos);
  yaml.replace(
    pos, std::string("cell_row: 0\n        cell_col: 2").size(),
    "cell_row: 1\n        cell_col: 2");
  EXPECT_THROW(import_yaml(yaml), std::runtime_error);
}

TEST(YamlMapImporter, RejectsBridgeEndpointInnerIndexOutOfRange) {
  auto yaml      = valid_yaml();
  const auto pos = yaml.find("inner_col: 5");
  ASSERT_NE(pos, std::string::npos);
  yaml.replace(pos, std::string("inner_col: 5").size(), "inner_col: 6");
  EXPECT_THROW(import_yaml(yaml), std::runtime_error);
}

TEST(MapRepository, QueriesCellIdsAndIndexes) {
  map_planner::MapRepository repository;
  repository.set_map(import_yaml(valid_yaml()));

  uint32_t cell_id = 0;
  EXPECT_TRUE(repository.get_cell_id(1, 0, 0, cell_id));
  EXPECT_EQ(cell_id, 1U);
  EXPECT_FALSE(repository.get_cell_id(1, 1, 2, cell_id));

  uint32_t block_id = 0;
  int row           = 0;
  int col           = 0;
  EXPECT_TRUE(repository.get_cell_index(1, block_id, row, col));
  EXPECT_EQ(block_id, 1U);
  EXPECT_EQ(row, 0);
  EXPECT_EQ(col, 0);
  EXPECT_FALSE(repository.get_cell_index(999, block_id, row, col));
}

void expect_cells_match_block_grid_geometry(const map_planner::PvMap &map) {
  for (const auto &block : map.blocks) {
    const double u_axis_norm =
      std::hypot(block.block_frame.u_axis_x, block.block_frame.u_axis_y);
    const double v_axis_norm =
      std::hypot(block.block_frame.v_axis_x, block.block_frame.v_axis_y);
    const double dot = block.block_frame.u_axis_x * block.block_frame.v_axis_x +
                       block.block_frame.u_axis_y * block.block_frame.v_axis_y;
    EXPECT_NEAR(u_axis_norm, 1.0, 1e-5);
    EXPECT_NEAR(v_axis_norm, 1.0, 1e-5);
    EXPECT_NEAR(dot, 0.0, 1e-5);

    for (int row = 0; row < block.rows; ++row) {
      for (int col = 0; col < block.cols; ++col) {
        const auto *cell = find_cell(map, block.block_id, row, col);
        if (
          block.grid[static_cast<size_t>(row)][static_cast<size_t>(col)] == 0) {
          EXPECT_EQ(cell, nullptr)
            << "missing panel should not have a cell at block "
            << block.block_id;
          continue;
        }

        ASSERT_NE(cell, nullptr)
          << "existing panel should have a cell at block " << block.block_id;
        ASSERT_EQ(cell->polygon.size(), 4U);
        EXPECT_GT(distance(cell->polygon[0], cell->polygon[1]), 0.0);
        EXPECT_GT(distance(cell->polygon[1], cell->polygon[2]), 0.0);
        EXPECT_GT(distance(cell->polygon[2], cell->polygon[3]), 0.0);
        EXPECT_GT(distance(cell->polygon[3], cell->polygon[0]), 0.0);
      }
    }
  }
}

void expect_bridge_geometry_matches_endpoint_edges(
  const map_planner::PvMap &map) {
  for (const auto &bridge : map.bridges) {
    ASSERT_EQ(bridge.endpoints.size(), 2U);
    ASSERT_EQ(bridge.centerline.size(), 2U);
    ASSERT_EQ(bridge.polygon.size(), 4U);

    const auto *source_cell = find_cell(
      map, bridge.endpoints[0].block_id, bridge.endpoints[0].cell_row,
      bridge.endpoints[0].cell_col);
    const auto *target_cell = find_cell(
      map, bridge.endpoints[1].block_id, bridge.endpoints[1].cell_row,
      bridge.endpoints[1].cell_col);
    ASSERT_NE(source_cell, nullptr);
    ASSERT_NE(target_cell, nullptr);

    expect_near_point(
      bridge.centerline[0],
      bridge_edge_anchor_point(
        bridge.endpoints[0], *source_cell, map.cell_model));
    expect_near_point(
      bridge.centerline[1],
      bridge_edge_anchor_point(
        bridge.endpoints[1], *target_cell, map.cell_model));

    const double centerline_length =
      distance(bridge.centerline[0], bridge.centerline[1]);
    EXPECT_GT(centerline_length, 0.0);
    EXPECT_GT(distance(bridge.polygon[0], bridge.polygon[3]), 0.0);
    EXPECT_GT(distance(bridge.polygon[1], bridge.polygon[2]), 0.0);

    expect_bridge_end_touches_cell_edge(
      bridge.polygon[3], bridge.polygon[0], *source_cell,
      bridge.endpoints[0].edge);
    expect_bridge_end_touches_cell_edge(
      bridge.polygon[1], bridge.polygon[2], *target_cell,
      bridge.endpoints[1].edge);
    expect_bridge_sides_parallel_to_centerline(bridge);
    expect_centerline_in_bridge_middle(bridge);

    if (bridge.endpoints[0].edge == "u_max") {
      EXPECT_EQ(bridge.endpoints[0].inner_col, map.cell_model.inner_cols - 1);
    }
    if (bridge.endpoints[1].edge == "u_min") {
      EXPECT_EQ(bridge.endpoints[1].inner_col, 0);
    }
  }
}

TEST(ExampleMapGeometry, CellsMatchBlockGridGeometry) {
  const auto paths = example_map_paths();
  ASSERT_GT(paths.size(), 0U);
  for (const auto &path : paths) {
    SCOPED_TRACE(path.string());
    expect_cells_match_block_grid_geometry(import_map_file(path));
  }
}

TEST(ExampleMapGeometry, BridgeGeometryMatchesEndpointEdges) {
  const auto paths = example_map_paths();
  ASSERT_GT(paths.size(), 0U);
  for (const auto &path : paths) {
    SCOPED_TRACE(path.string());
    expect_bridge_geometry_matches_endpoint_edges(import_map_file(path));
  }
}
