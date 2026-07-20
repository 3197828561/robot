#ifndef MAP_PLANNER__BLOCK_COVERAGE_CANDIDATE_GENERATOR_HPP_
#define MAP_PLANNER__BLOCK_COVERAGE_CANDIDATE_GENERATOR_HPP_

#include <optional>
#include <vector>

#include "map_planner/planning/center_grid.hpp"
#include "map_planner/planning/lane_planner.hpp"
#include "map_planner/map/map_repository.hpp"
#include "map_planner/planning/motion_connector.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    struct BlockCoverageCandidate {
        uint32_t block_id{};
        uint32_t candidate_id{};
        GridPose entry_pose;
        GridPose exit_pose;
        SweepAxis sweep_axis{SweepAxis::BlockU};
        int lane_stride{};
        int lane_offset{};
        std::vector<PathWaypoint> waypoints;
        bool coverage_complete{false};
        double total_cost{};
        size_t covered_clean_center_count{};
        size_t total_clean_center_count{};
        size_t transition_waypoint_count{};
        size_t transition_turn_count{};
        size_t continuity_error_count{};
        std::string score_breakdown;
        std::vector<std::string> debug_score_breakdowns;
        std::vector<std::string> invalid_reasons;
        std::vector<std::string> unreachable_segments;
    };

    class BlockCoverageCandidateGenerator {
    public:
        std::vector<BlockCoverageCandidate> make_free_start_candidates(const PvMap &map,
                                                                       const MapRepository &repository,
                                                                       const Block &block,
                                                                       const CenterGrid &center_grid,
                                                                       const RobotPlanningConfig &config,
                                                                       size_t max_candidates = 16,
                                                                       size_t max_start_segments = 0) const;

        std::vector<BlockCoverageCandidate> make_entry_constrained_candidates(const PvMap &map,
                                                                              const MapRepository &repository,
                                                                              const Block &block,
                                                                              const CenterGrid &center_grid,
                                                                              const RobotPlanningConfig &config,
                                                                              const GridPose &entry_pose,
                                                                              size_t max_candidates = 8) const;

        std::vector<GridPose> make_corner_entry_poses(const PvMap &map,
                                                      const Block &block,
                                                      const CenterGrid &center_grid,
                                                      size_t max_per_corner = 1) const;

        static bool candidate_less(const BlockCoverageCandidate &lhs, const BlockCoverageCandidate &rhs);

    private:
        std::vector<BlockCoverageCandidate> make_candidates_impl(const PvMap &map,
                                                                 const MapRepository &repository,
                                                                 const Block &block,
                                                                 const CenterGrid &center_grid,
                                                                 const RobotPlanningConfig &config,
                                                                 const std::optional<GridPose> &entry_pose,
                                                                 size_t max_candidates,
                                                                 size_t max_start_segments) const;
    };

} // namespace map_planner

#endif // MAP_PLANNER__BLOCK_COVERAGE_CANDIDATE_GENERATOR_HPP_
