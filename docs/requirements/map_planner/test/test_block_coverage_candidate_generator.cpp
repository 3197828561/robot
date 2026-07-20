#include <gtest/gtest.h>

#include <set>
#include <string>
#include <utility>
#include <vector>

#include "map_planner/planning/block_coverage_candidate_generator.hpp"
#include "map_planner/planning/center_grid.hpp"

namespace {

map_planner::Cell make_cell(
  uint32_t cell_id, uint32_t block_id, int row, int col) {
  map_planner::Cell cell;
  cell.cell_id    = cell_id;
  cell.block_id   = block_id;
  cell.row        = row;
  cell.col        = col;
  const double u0 = static_cast<double>(col) * 200.0;
  const double u1 = u0 + 200.0;
  const double v0 = static_cast<double>(row) * 100.0;
  const double v1 = v0 + 100.0;
  cell.polygon    = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
  return cell;
}

map_planner::PvMap make_long_block_map() {
  map_planner::PvMap map;
  map.map_id                = 1;
  map.version               = 1;
  map.cell_model.inner_rows = 1;
  map.cell_model.inner_cols = 4;

  map_planner::Block block;
  block.block_id = 1;
  block.rows     = 4;
  block.cols     = 8;
  block.grid.assign(
    static_cast<size_t>(block.rows),
    std::vector<int>(static_cast<size_t>(block.cols), 1));

  uint32_t cell_id = 1;
  for (int row = 0; row < block.rows; ++row) {
    for (int col = 0; col < block.cols; ++col) {
      block.cell_ids.push_back(cell_id);
      map.cells.push_back(make_cell(cell_id, block.block_id, row, col));
      ++cell_id;
    }
  }

  map.blocks = {block};
  return map;
}

map_planner::RobotPlanningConfig make_test_config(
  const std::string &effort = "quality") {
  map_planner::RobotPlanningConfig config;
  config.robot_length_cm        = 10.0;
  config.robot_width_cm         = 10.0;
  config.safety_margin_cm       = 0.0;
  config.cleaning_width_cm      = 200.0;
  config.overlap_ratio          = 0.0;
  config.enable_tail_coverage   = true;
  config.planning_search_effort = effort;
  return config;
}

}  // namespace

TEST(
  BlockCoverageCandidateGenerator,
  PrefersGeometricCoverageOverDenseWaypointCount) {
  auto map = make_long_block_map();
  map_planner::MapRepository repository;
  repository.set_map(map);

  auto config = make_test_config("quality");

  const auto center_grid = map_planner::CenterGridBuilder().build(
    repository.map(), repository, config);
  const auto *block = repository.find_block(1);
  ASSERT_NE(block, nullptr);

  const auto candidates =
    map_planner::BlockCoverageCandidateGenerator().make_free_start_candidates(
      repository.map(), repository, *block, center_grid, config, 8);
  ASSERT_FALSE(candidates.empty());

  const auto &best = candidates.front();
  EXPECT_TRUE(best.coverage_complete);
  EXPECT_EQ(best.sweep_axis, map_planner::SweepAxis::BlockU);
  EXPECT_EQ(best.lane_stride, 2);
  EXPECT_EQ(best.covered_clean_center_count, best.total_clean_center_count);
}

TEST(BlockCoverageCandidateGenerator, QualityKeepsOldBalancedBehavior) {
  auto map = make_long_block_map();
  map_planner::MapRepository repository;
  repository.set_map(map);

  auto config            = make_test_config("quality");
  const auto center_grid = map_planner::CenterGridBuilder().build(
    repository.map(), repository, config);
  const auto *block = repository.find_block(1);
  ASSERT_NE(block, nullptr);

  const auto candidates =
    map_planner::BlockCoverageCandidateGenerator().make_free_start_candidates(
      repository.map(), repository, *block, center_grid, config, 16, 8);
  ASSERT_FALSE(candidates.empty());

  const auto &best = candidates.front();
  EXPECT_TRUE(best.coverage_complete);
  EXPECT_EQ(best.sweep_axis, map_planner::SweepAxis::BlockU);
  EXPECT_EQ(best.lane_stride, 2);
}

TEST(
  BlockCoverageCandidateGenerator,
  FastAndBalancedPreserveAxisAndOffsetDiversityWhenPruned) {
  auto map = make_long_block_map();
  map_planner::MapRepository repository;
  repository.set_map(map);
  const auto *block = repository.find_block(1);
  ASSERT_NE(block, nullptr);

  for (const auto *effort : {"fast", "balanced"}) {
    auto config            = make_test_config(effort);
    const auto center_grid = map_planner::CenterGridBuilder().build(
      repository.map(), repository, config);
    const auto candidates =
      map_planner::BlockCoverageCandidateGenerator().make_free_start_candidates(
        repository.map(), repository, *block, center_grid, config, 16, 10);
    ASSERT_FALSE(candidates.empty()) << effort;

    std::set<std::pair<map_planner::SweepAxis, int>> axis_offsets;
    for (const auto &candidate : candidates) {
      axis_offsets.insert({candidate.sweep_axis, candidate.lane_offset});
    }
    EXPECT_GT(axis_offsets.size(), 1U) << effort;
  }
}

TEST(BlockCoverageCandidateGenerator, ExhaustiveRemainsUnprunedFallback) {
  auto map = make_long_block_map();
  map_planner::MapRepository repository;
  repository.set_map(map);

  auto config            = make_test_config("exhaustive");
  const auto center_grid = map_planner::CenterGridBuilder().build(
    repository.map(), repository, config);
  const auto *block = repository.find_block(1);
  ASSERT_NE(block, nullptr);

  const auto candidates =
    map_planner::BlockCoverageCandidateGenerator().make_free_start_candidates(
      repository.map(), repository, *block, center_grid, config, 32, 0);
  ASSERT_FALSE(candidates.empty());
  EXPECT_TRUE(candidates.front().coverage_complete);
}
