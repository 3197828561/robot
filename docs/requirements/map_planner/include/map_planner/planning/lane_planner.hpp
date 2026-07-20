#ifndef MAP_PLANNER__LANE_PLANNER_HPP_
#define MAP_PLANNER__LANE_PLANNER_HPP_

#include <vector>

#include "map_planner/planning/center_grid.hpp"
#include "map_planner/map/map_geometry.hpp"
#include "map_planner/map/map_repository.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    struct LaneSegment {
        SweepAxis sweep_axis{SweepAxis::BlockU};
        int lane_index{};
        int lane_offset_inner{};
        Heading heading{Heading::BlockUPositive};
        std::vector<CenterInnerCell> centers;
    };

    struct LanePlanCandidate {
        SweepAxis sweep_axis{SweepAxis::BlockU};
        int lane_stride{1};
        int lane_offset{};
        std::vector<LaneSegment> segments;
        bool coverage_complete{false};
        double cost{};
        std::vector<std::string> invalid_reasons;
        std::vector<std::string> unreachable_segments;
    };

    class LanePlanner {
    public:
        std::vector<LanePlanCandidate> make_candidates(const PvMap &map, const MapRepository &repository,
                                                       const Block &block, const CenterGrid &center_grid,
                                                       const RobotPlanningConfig &config) const;

        static int compute_lane_stride(SweepAxis sweep_axis,
                                       const map_geometry::BlockInnerCellSizeStats &stats,
                                       const RobotPlanningConfig &config);
    };

} // namespace map_planner

#endif // MAP_PLANNER__LANE_PLANNER_HPP_
