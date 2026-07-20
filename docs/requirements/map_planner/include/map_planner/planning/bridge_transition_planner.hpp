#ifndef MAP_PLANNER__BRIDGE_TRANSITION_PLANNER_HPP_
#define MAP_PLANNER__BRIDGE_TRANSITION_PLANNER_HPP_

#include <optional>
#include <string>
#include <vector>

#include "map_planner/planning/center_grid.hpp"
#include "map_planner/map/map_geometry.hpp"
#include "map_planner/map/map_repository.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    struct BridgeSideTransition {
        uint32_t bridge_id{};
        uint32_t block_id{};
        BridgeEndpoint endpoint;
        Point2D anchor_point;
        CenterInnerCell safe_center;
        CenterInnerCell bridge_edge_center;
        GridPose bridge_edge_pose;
        std::optional<GridPose> staging_pose;
        Heading approach_heading{Heading::BlockUPositive};
        bool uses_bridge_edge_exception{false};
        bool usable{false};
        std::string message;
    };

    struct BridgeTransition {
        uint32_t bridge_id{};
        std::vector<BridgeSideTransition> sides;
        double reference_length_cm{};
        double cost{};
        bool usable{false};
        std::string message;
    };

    class BridgeTransitionPlanner {
    public:
        std::vector<BridgeTransition> make_transitions(const PvMap &map, const MapRepository &repository,
                                                       const CenterGrid &center_grid,
                                                       const RobotPlanningConfig &config) const;
    };

} // namespace map_planner

#endif // MAP_PLANNER__BRIDGE_TRANSITION_PLANNER_HPP_
