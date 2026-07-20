#include "map_planner/map/yaml_map_importer.hpp"

#include <yaml-cpp/yaml.h>

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace map_planner {
namespace {

std::string lower_extension(const std::string &path) {
  std::string ext = std::filesystem::path(path).extension().string();
  std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) {
    return static_cast<char>(std::tolower(c));
  });
  return ext;
}

std::string key(uint32_t block_id, int row, int col) {
  return std::to_string(block_id) + ":" + std::to_string(row) + ":" +
         std::to_string(col);
}

template <typename T>
T required_as(const YAML::Node &node, const std::string &field) {
  if (!node[field]) {
    throw std::runtime_error("missing required field: " + field);
  }
  return node[field].as<T>();
}

Point2D parse_point2d(const YAML::Node &node, const std::string &field_name) {
  if (!node || !node.IsSequence() || node.size() != 2) {
    throw std::runtime_error(field_name + " must be a [u, v] point");
  }
  return Point2D{node[0].as<double>(), node[1].as<double>()};
}

std::vector<Point2D> parse_points(
  const YAML::Node &node, const std::string &field_name) {
  std::vector<Point2D> points;
  if (!node) {
    return points;
  }
  if (!node.IsSequence()) {
    throw std::runtime_error(field_name + " must be a point sequence");
  }
  for (size_t i = 0; i < node.size(); ++i) {
    points.push_back(parse_point2d(node[i], field_name + "[]"));
  }
  return points;
}

bool valid_edge(const std::string &edge) {
  return edge == "u_min" || edge == "u_max" || edge == "v_min" ||
         edge == "v_max";
}

const Block *find_block(const PvMap &map, uint32_t block_id) {
  const auto it = std::find_if(
    map.blocks.begin(), map.blocks.end(), [block_id](const Block &block) {
      return block.block_id == block_id;
    });
  return it == map.blocks.end() ? nullptr : &(*it);
}

void validate_map(const PvMap &map) {
  if (map.cell_model.inner_rows <= 0 || map.cell_model.inner_cols <= 0) {
    throw std::runtime_error(
      "cell_model.inner_rows and inner_cols must be positive");
  }

  std::unordered_set<uint32_t> block_ids;
  std::unordered_set<uint32_t> cell_ids;
  std::unordered_set<uint32_t> bridge_ids;
  std::unordered_map<uint32_t, std::unordered_set<uint32_t>>
    expected_cell_ids_by_block;
  std::unordered_set<std::string> cell_positions;

  for (const auto &block : map.blocks) {
    if (!block_ids.insert(block.block_id).second) {
      throw std::runtime_error(
        "duplicate block_id: " + std::to_string(block.block_id));
    }
    if (block.rows <= 0 || block.cols <= 0) {
      throw std::runtime_error(
        "block rows/cols must be positive: " + std::to_string(block.block_id));
    }
    if (block.grid.size() != static_cast<size_t>(block.rows)) {
      throw std::runtime_error(
        "block grid row count mismatch: " + std::to_string(block.block_id));
    }
    for (int row = 0; row < block.rows; ++row) {
      const auto &grid_row = block.grid[static_cast<size_t>(row)];
      if (grid_row.size() != static_cast<size_t>(block.cols)) {
        throw std::runtime_error(
          "block grid col count mismatch: " + std::to_string(block.block_id));
      }
      for (int col = 0; col < block.cols; ++col) {
        const int value = grid_row[static_cast<size_t>(col)];
        if (value != 0 && value != 1) {
          throw std::runtime_error(
            "block grid values must be 0 or 1: " +
            std::to_string(block.block_id));
        }
      }
    }
    for (const auto cell_id : block.cell_ids) {
      expected_cell_ids_by_block[block.block_id].insert(cell_id);
    }
  }

  for (const auto &cell : map.cells) {
    if (!cell_ids.insert(cell.cell_id).second) {
      throw std::runtime_error(
        "duplicate cell_id: " + std::to_string(cell.cell_id));
    }
    const auto *block = find_block(map, cell.block_id);
    if (block == nullptr) {
      throw std::runtime_error(
        "cell references missing block_id: " + std::to_string(cell.cell_id));
    }
    if (
      cell.row < 0 || cell.col < 0 || cell.row >= block->rows ||
      cell.col >= block->cols) {
      throw std::runtime_error(
        "cell row/col out of range: " + std::to_string(cell.cell_id));
    }
    if (
      block->grid[static_cast<size_t>(cell.row)]
                 [static_cast<size_t>(cell.col)] != 1) {
      throw std::runtime_error(
        "cell points to missing panel grid slot: " +
        std::to_string(cell.cell_id));
    }
    if (cell.polygon.size() < 3) {
      throw std::runtime_error(
        "cell polygon must have at least 3 points: " +
        std::to_string(cell.cell_id));
    }
    const auto position_key = key(cell.block_id, cell.row, cell.col);
    if (!cell_positions.insert(position_key).second) {
      throw std::runtime_error(
        "duplicate cell row/col in block: " + position_key);
    }
  }

  for (const auto &block : map.blocks) {
    for (const auto cell_id : block.cell_ids) {
      if (cell_ids.find(cell_id) == cell_ids.end()) {
        throw std::runtime_error(
          "block cell_ids references missing cell: " + std::to_string(cell_id));
      }
    }
  }

  for (const auto &bridge : map.bridges) {
    if (!bridge_ids.insert(bridge.bridge_id).second) {
      throw std::runtime_error(
        "duplicate bridge_id: " + std::to_string(bridge.bridge_id));
    }
    if (bridge.endpoints.size() != 2) {
      throw std::runtime_error(
        "bridge endpoints must have exactly 2 items: " +
        std::to_string(bridge.bridge_id));
    }
    if (bridge.endpoints[0].block_id == bridge.endpoints[1].block_id) {
      throw std::runtime_error(
        "bridge endpoints must reference different blocks: " +
        std::to_string(bridge.bridge_id));
    }
    for (const auto &endpoint : bridge.endpoints) {
      const auto *block = find_block(map, endpoint.block_id);
      if (block == nullptr) {
        throw std::runtime_error(
          "bridge endpoint references missing block: " +
          std::to_string(bridge.bridge_id));
      }
      if (
        endpoint.cell_row < 0 || endpoint.cell_col < 0 ||
        endpoint.cell_row >= block->rows || endpoint.cell_col >= block->cols) {
        throw std::runtime_error(
          "bridge endpoint cell row/col out of range: " +
          std::to_string(bridge.bridge_id));
      }
      if (
        block->grid[static_cast<size_t>(endpoint.cell_row)]
                   [static_cast<size_t>(endpoint.cell_col)] != 1) {
        throw std::runtime_error(
          "bridge endpoint points to missing panel grid slot: " +
          std::to_string(bridge.bridge_id));
      }
      if (
        cell_positions.find(
          key(endpoint.block_id, endpoint.cell_row, endpoint.cell_col)) ==
        cell_positions.end()) {
        throw std::runtime_error(
          "bridge endpoint cell is not present in cells[]: " +
          std::to_string(bridge.bridge_id));
      }
      if (
        endpoint.inner_row < 0 || endpoint.inner_col < 0 ||
        endpoint.inner_row >= map.cell_model.inner_rows ||
        endpoint.inner_col >= map.cell_model.inner_cols) {
        throw std::runtime_error(
          "bridge endpoint inner row/col out of range: " +
          std::to_string(bridge.bridge_id));
      }
      if (!valid_edge(endpoint.edge)) {
        throw std::runtime_error(
          "bridge endpoint edge is invalid: " + endpoint.edge);
      }
    }
    if (!bridge.centerline.empty() && bridge.centerline.size() < 2) {
      throw std::runtime_error(
        "bridge centerline must have at least 2 points: " +
        std::to_string(bridge.bridge_id));
    }
    if (!bridge.polygon.empty() && bridge.polygon.size() < 3) {
      throw std::runtime_error(
        "bridge polygon must have at least 3 points: " +
        std::to_string(bridge.bridge_id));
    }
  }
}

}  // namespace

bool YamlMapImporter::can_import(const std::string &path) const {
  const auto ext = lower_extension(path);
  return ext == ".yaml" || ext == ".yml";
}

PvMap YamlMapImporter::import_from_file(const std::string &path) const {
  if (!can_import(path)) {
    throw std::runtime_error("unsupported map file extension: " + path);
  }

  return import_yaml_like_file(path);
}

PvMap YamlMapImporter::import_yaml_like_file(const std::string &path) const {
  const YAML::Node root = YAML::LoadFile(path);
  PvMap map;
  map.map_id  = required_as<uint32_t>(root, "map_id");
  map.version = root["version"] ? root["version"].as<uint32_t>() : 1U;

  if (root["frame"]) {
    const auto frame = root["frame"];
    map.frame.unit =
      frame["unit"] ? frame["unit"].as<std::string>() : "centimeter";
    if (frame["origin"]) {
      const auto origin       = frame["origin"];
      map.frame.latitude_deg  = required_as<double>(origin, "latitude_deg");
      map.frame.longitude_deg = required_as<double>(origin, "longitude_deg");
      map.frame.yaw_deg       = required_as<double>(origin, "yaw_deg");
      map.frame.has_origin    = true;
    }
  }

  const auto cell_model = root["cell_model"];
  if (!cell_model) {
    throw std::runtime_error("missing required field: cell_model");
  }
  map.cell_model.inner_rows = required_as<int>(cell_model, "inner_rows");
  map.cell_model.inner_cols = required_as<int>(cell_model, "inner_cols");

  const auto blocks = root["blocks"];
  if (!blocks || !blocks.IsSequence()) {
    throw std::runtime_error("blocks must be a sequence");
  }
  for (const auto block_node : blocks) {
    Block block;
    block.block_id         = required_as<uint32_t>(block_node, "block_id");
    const auto block_frame = block_node["block_frame"];
    if (!block_frame) {
      throw std::runtime_error("missing required field: block_frame");
    }
    block.block_frame.block_origin =
      parse_point2d(block_frame["block_origin"], "block_frame.block_origin");
    const auto u_axis = block_frame["u_axis"];
    const auto v_axis = block_frame["v_axis"];
    if (!u_axis || !u_axis.IsSequence() || u_axis.size() != 2) {
      throw std::runtime_error("block_frame.u_axis must be [x, y]");
    }
    if (!v_axis || !v_axis.IsSequence() || v_axis.size() != 2) {
      throw std::runtime_error("block_frame.v_axis must be [x, y]");
    }
    block.block_frame.u_axis_x = u_axis[0].as<double>();
    block.block_frame.u_axis_y = u_axis[1].as<double>();
    block.block_frame.v_axis_x = v_axis[0].as<double>();
    block.block_frame.v_axis_y = v_axis[1].as<double>();
    block.rows                 = required_as<int>(block_node, "rows");
    block.cols                 = required_as<int>(block_node, "cols");
    block.cleanable =
      block_node["cleanable"] ? block_node["cleanable"].as<bool>() : true;

    const auto grid = block_node["grid"];
    if (!grid || !grid.IsSequence()) {
      throw std::runtime_error("block.grid must be a sequence");
    }
    for (const auto row_node : grid) {
      if (!row_node.IsSequence()) {
        throw std::runtime_error("block.grid rows must be sequences");
      }
      std::vector<int> row;
      for (const auto value_node : row_node) {
        row.push_back(value_node.as<int>());
      }
      block.grid.push_back(row);
    }

    const auto cell_ids = block_node["cell_ids"];
    if (cell_ids) {
      if (!cell_ids.IsSequence()) {
        throw std::runtime_error("block.cell_ids must be a sequence");
      }
      for (const auto cell_id_node : cell_ids) {
        block.cell_ids.push_back(cell_id_node.as<uint32_t>());
      }
    }
    map.blocks.push_back(block);
  }

  const auto cells = root["cells"];
  if (!cells || !cells.IsSequence()) {
    throw std::runtime_error("cells must be a sequence");
  }
  for (const auto cell_node : cells) {
    Cell cell;
    cell.cell_id  = required_as<uint32_t>(cell_node, "cell_id");
    cell.block_id = required_as<uint32_t>(cell_node, "block_id");
    cell.row      = required_as<int>(cell_node, "row");
    cell.col      = required_as<int>(cell_node, "col");
    cell.polygon  = parse_points(cell_node["polygon"], "cell.polygon");
    map.cells.push_back(cell);
  }

  const auto bridges = root["bridges"];
  if (bridges) {
    if (!bridges.IsSequence()) {
      throw std::runtime_error("bridges must be a sequence");
    }
    for (const auto bridge_node : bridges) {
      Bridge bridge;
      bridge.bridge_id = required_as<uint32_t>(bridge_node, "bridge_id");
      bridge.source =
        bridge_node["source"] ? bridge_node["source"].as<std::string>() : "";
      const auto endpoints = bridge_node["endpoints"];
      if (!endpoints || !endpoints.IsSequence()) {
        throw std::runtime_error("bridge.endpoints must be a sequence");
      }
      for (const auto endpoint_node : endpoints) {
        BridgeEndpoint endpoint;
        endpoint.block_id  = required_as<uint32_t>(endpoint_node, "block_id");
        endpoint.cell_row  = required_as<int>(endpoint_node, "cell_row");
        endpoint.cell_col  = required_as<int>(endpoint_node, "cell_col");
        endpoint.edge      = required_as<std::string>(endpoint_node, "edge");
        endpoint.inner_row = required_as<int>(endpoint_node, "inner_row");
        endpoint.inner_col = required_as<int>(endpoint_node, "inner_col");
        bridge.endpoints.push_back(endpoint);
      }
      bridge.centerline =
        parse_points(bridge_node["centerline"], "bridge.centerline");
      bridge.polygon = parse_points(bridge_node["polygon"], "bridge.polygon");
      map.bridges.push_back(bridge);
    }
  }

  validate_map(map);
  return map;
}

}  // namespace map_planner
