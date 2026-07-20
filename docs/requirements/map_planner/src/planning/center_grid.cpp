#include "map_planner/planning/center_grid.hpp"

#include <algorithm>
#include <cmath>

#include "map_planner/map/map_geometry.hpp"

namespace map_planner {

namespace {

void hash_combine(size_t &seed, size_t value) {
  seed ^= value + 0x9e3779b97f4a7c15ULL + (seed << 6U) + (seed >> 2U);
}

}  // namespace

bool CenterGrid::Key::operator==(const Key &other) const {
  return block_id == other.block_id && cell_row == other.cell_row &&
         cell_col == other.cell_col && inner_row == other.inner_row &&
         inner_col == other.inner_col && heading == other.heading;
}

size_t CenterGrid::KeyHash::operator()(const Key &key) const {
  size_t seed = 0;
  hash_combine(seed, std::hash<uint32_t>{}(key.block_id));
  hash_combine(seed, std::hash<int>{}(key.cell_row));
  hash_combine(seed, std::hash<int>{}(key.cell_col));
  hash_combine(seed, std::hash<int>{}(key.inner_row));
  hash_combine(seed, std::hash<int>{}(key.inner_col));
  hash_combine(seed, std::hash<uint8_t>{}(static_cast<uint8_t>(key.heading)));
  return seed;
}

CenterGrid::Key CenterGrid::make_key(
  const CenterInnerCell &center, Heading heading) {
  return {center.block_id,  center.cell_row,  center.cell_col,
          center.inner_row, center.inner_col, heading};
}

void CenterGrid::set_status(
  const CenterInnerCell &center, Heading heading, TraversabilityStatus status) {
  statuses_[make_key(center, heading)] = status;
}

TraversabilityStatus CenterGrid::status(
  const CenterInnerCell &center, Heading heading) const {
  const auto it = statuses_.find(make_key(center, heading));
  if (it == statuses_.end()) {
    return TraversabilityStatus::Unknown;
  }
  return it->second;
}

bool CenterGrid::is_traversable(
  const CenterInnerCell &center, Heading heading) const {
  return status(center, heading) == TraversabilityStatus::Free;
}

bool CenterGrid::is_traversable(const GridPose &pose) const {
  return is_traversable(pose.center, pose.heading);
}

std::vector<Heading> CenterGridBuilder::headings() {
  return {
    Heading::BlockUPositive, Heading::BlockUNegative, Heading::BlockVPositive,
    Heading::BlockVNegative};
}

namespace {

void populate_block(
  CenterGrid &grid, const PvMap &map, const MapRepository &repository,
  const Block &block, const RobotPlanningConfig &config,
  const std::vector<Heading> &headings) {
  const auto stats =
    map_geometry::estimate_block_inner_cell_size_stats(map, repository, block);
  const double inner_u_size =
    stats.median_inner_u_size_cm > 0.0 ? stats.median_inner_u_size_cm : 1.0;
  const double inner_v_size =
    stats.median_inner_v_size_cm > 0.0 ? stats.median_inner_v_size_cm : 1.0;

  const double footprint_length_cm = effective_chassis_length_cm(config);
  const double footprint_width_cm  = std::max(0.0, config.robot_width_cm);
  const double safety_margin_cm    = std::max(0.0, config.safety_margin_cm);

  for (const auto heading : headings) {
    const bool heading_along_u =
      heading == Heading::BlockUPositive || heading == Heading::BlockUNegative;
    const double safe_half_u_cm =
      (heading_along_u ? footprint_length_cm : footprint_width_cm) / 2.0 +
      safety_margin_cm;
    const double safe_half_v_cm =
      (heading_along_u ? footprint_width_cm : footprint_length_cm) / 2.0 +
      safety_margin_cm;
    const int inflate_u_cells = std::max(
      0, static_cast<int>(std::ceil(safe_half_u_cm / inner_u_size)) - 1);
    const int inflate_v_cells = std::max(
      0, static_cast<int>(std::ceil(safe_half_v_cm / inner_v_size)) - 1);

    for (int cell_row = 0; cell_row < block.rows; ++cell_row) {
      for (int cell_col = 0; cell_col < block.cols; ++cell_col) {
        const bool present =
          repository.is_cell_present(block.block_id, cell_row, cell_col);

        for (int inner_row = 0; inner_row < map.cell_model.inner_rows;
             ++inner_row) {
          for (int inner_col = 0; inner_col < map.cell_model.inner_cols;
               ++inner_col) {
            const CenterInnerCell center{
              block.block_id, cell_row, cell_col, inner_row, inner_col};

            if (!block.cleanable) {
              grid.set_status(
                center, heading, TraversabilityStatus::BlockedObstacle);
              continue;
            }

            if (!present) {
              grid.set_status(
                center, heading, TraversabilityStatus::BlockedMissingCell);
              continue;
            }

            const int global_inner_row =
              cell_row * map.cell_model.inner_rows + inner_row;
            const int global_inner_col =
              cell_col * map.cell_model.inner_cols + inner_col;
            const int total_inner_rows = block.rows * map.cell_model.inner_rows;
            const int total_inner_cols = block.cols * map.cell_model.inner_cols;

            if (
              global_inner_col < inflate_u_cells ||
              global_inner_col >= total_inner_cols - inflate_u_cells ||
              global_inner_row < inflate_v_cells ||
              global_inner_row >= total_inner_rows - inflate_v_cells) {
              grid.set_status(
                center, heading, TraversabilityStatus::BlockedBoundary);
              continue;
            }

            bool near_missing = false;
            for (int row_delta = -inflate_v_cells;
                 row_delta <= inflate_v_cells && !near_missing; ++row_delta) {
              for (int col_delta = -inflate_u_cells;
                   col_delta <= inflate_u_cells; ++col_delta) {
                const int neighbor_global_row = global_inner_row + row_delta;
                const int neighbor_global_col = global_inner_col + col_delta;
                if (
                  neighbor_global_row < 0 || neighbor_global_col < 0 ||
                  neighbor_global_row >= total_inner_rows ||
                  neighbor_global_col >= total_inner_cols) {
                  continue;
                }
                const int neighbor_cell_row =
                  neighbor_global_row / map.cell_model.inner_rows;
                const int neighbor_cell_col =
                  neighbor_global_col / map.cell_model.inner_cols;
                if (!repository.is_cell_present(
                      block.block_id, neighbor_cell_row, neighbor_cell_col)) {
                  near_missing = true;
                  break;
                }
              }
            }

            grid.set_status(
              center, heading,
              near_missing ? TraversabilityStatus::BlockedMissingInflation
                           : TraversabilityStatus::Free);
          }
        }
      }
    }
  }
}

}  // namespace

CenterGrid CenterGridBuilder::build(
  const PvMap &map, const MapRepository &repository,
  const RobotPlanningConfig &config) const {
  CenterGrid grid;
  const auto grid_headings = headings();
  for (const auto &block : map.blocks) {
    populate_block(grid, map, repository, block, config, grid_headings);
  }
  return grid;
}

CenterGrid CenterGridBuilder::build_block(
  const PvMap &map, const MapRepository &repository, const Block &block,
  const RobotPlanningConfig &config) const {
  CenterGrid grid;
  populate_block(grid, map, repository, block, config, headings());
  return grid;
}

}  // namespace map_planner
