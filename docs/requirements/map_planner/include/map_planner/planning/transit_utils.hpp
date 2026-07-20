#ifndef MAP_PLANNER__TRANSIT_UTILS_HPP_
#define MAP_PLANNER__TRANSIT_UTILS_HPP_

#include <optional>
#include <string>
#include <vector>

#include "map_planner/planning/bridge_transition_planner.hpp"
#include "map_planner/planning/center_grid.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    struct TransitionPlan {
        bool success{false};
        double cost{};
        std::vector<PathWaypoint> waypoints;
        std::string message;
    };

    bool same_center(const CenterInnerCell &a, const CenterInnerCell &b);
    bool same_pose(const GridPose &a, const GridPose &b);
    Heading opposite_heading(Heading heading);
    int global_row(const CenterInnerCell &center, const CellModel &cell_model);
    int global_col(const CenterInnerCell &center, const CellModel &cell_model);
    CenterInnerCell from_global(uint32_t block_id, const CellModel &cell_model, int row, int col);
    int manhattan_distance(const CenterInnerCell &a, const CenterInnerCell &b, const CellModel &cell_model);
    size_t turn_count(const std::vector<PathWaypoint> &waypoints);
    bool append_unique(std::vector<PathWaypoint> &waypoints, const PathWaypoint &waypoint);

    std::optional<GridPose> normal_pose_for_side(const BridgeSideTransition &side, bool departing_from_bridge);

    TransitionPlan build_bridge_transfer_plan(const GridPose &from_pose,
                                              const GridPose &to_pose,
                                              const BridgeTransition &bridge,
                                              const BridgeSideTransition &source_side,
                                              const BridgeSideTransition &target_side,
                                              const CenterGrid &center_grid,
                                              const CellModel &cell_model);

} // namespace map_planner

#endif // MAP_PLANNER__TRANSIT_UTILS_HPP_
