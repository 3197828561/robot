#include "map_planner/map/map_repository.hpp"

#include <stdexcept>

namespace map_planner {

void MapRepository::set_map(PvMap map) {
  map_     = std::move(map);
  has_map_ = true;
  rebuild_indexes();
}

const PvMap &MapRepository::map() const {
  if (!has_map_) {
    throw std::runtime_error("map is not loaded");
  }
  return map_;
}

bool MapRepository::has_map() const { return has_map_; }

const Block *MapRepository::find_block(uint32_t block_id) const {
  const auto it = block_by_id_.find(block_id);
  if (it == block_by_id_.end()) {
    return nullptr;
  }
  return &map_.blocks[it->second];
}

const Cell *MapRepository::find_cell(uint32_t cell_id) const {
  const auto it = cell_by_id_.find(cell_id);
  if (it == cell_by_id_.end()) {
    return nullptr;
  }
  return &map_.cells[it->second];
}

const Cell *MapRepository::find_cell(
  uint32_t block_id, int row, int col) const {
  const auto it = cell_by_block_row_col_.find(cell_key(block_id, row, col));
  if (it == cell_by_block_row_col_.end()) {
    return nullptr;
  }
  return &map_.cells[it->second];
}

bool MapRepository::is_cell_present(uint32_t block_id, int row, int col) const {
  const auto *block = find_block(block_id);
  if (block == nullptr) {
    return false;
  }
  if (row < 0 || col < 0 || row >= block->rows || col >= block->cols) {
    return false;
  }
  return block->grid[static_cast<size_t>(row)][static_cast<size_t>(col)] == 1;
}

bool MapRepository::get_cell_id(
  uint32_t block_id, int row, int col, uint32_t &cell_id) const {
  if (!is_cell_present(block_id, row, col)) {
    return false;
  }
  const auto *cell = find_cell(block_id, row, col);
  if (cell == nullptr) {
    return false;
  }
  cell_id = cell->cell_id;
  return true;
}

bool MapRepository::get_cell_index(
  uint32_t cell_id, uint32_t &block_id, int &row, int &col) const {
  const auto *cell = find_cell(cell_id);
  if (cell == nullptr) {
    return false;
  }
  if (!is_cell_present(cell->block_id, cell->row, cell->col)) {
    return false;
  }
  block_id = cell->block_id;
  row      = cell->row;
  col      = cell->col;
  return true;
}

std::string MapRepository::cell_key(uint32_t block_id, int row, int col) {
  return std::to_string(block_id) + ":" + std::to_string(row) + ":" +
         std::to_string(col);
}

void MapRepository::rebuild_indexes() {
  block_by_id_.clear();
  cell_by_id_.clear();
  cell_by_block_row_col_.clear();

  for (size_t i = 0; i < map_.blocks.size(); ++i) {
    block_by_id_[map_.blocks[i].block_id] = i;
  }

  for (size_t i = 0; i < map_.cells.size(); ++i) {
    const auto &cell          = map_.cells[i];
    cell_by_id_[cell.cell_id] = i;
    cell_by_block_row_col_[cell_key(cell.block_id, cell.row, cell.col)] = i;
  }
}

}  // namespace map_planner
