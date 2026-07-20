#ifndef MAP_PLANNER__PLANNING_ROS_ADAPTER_HPP_
#define MAP_PLANNER__PLANNING_ROS_ADAPTER_HPP_

#include <string>

#include "map_planner/map/map_types.hpp"
#include "map_planner/planning/planning_types.hpp"
#include "map_planner/srv/plan_coverage_path.hpp"
#include "map_planner/srv/plan_transit_path.hpp"

namespace map_planner
{

    class PlanningRosAdapter {
    public:
        static PlanningRequest from_request(const srv::PlanCoveragePath::Request &request);
        static TransitPlanningRequest from_request(const srv::PlanTransitPath::Request &request);

        static void fill_response(const PlanningResult &result, const PvMap &map, const std::string &frame_id,
                                  srv::PlanCoveragePath::Response &response);
        static void fill_response(const PlanningResult &result, const PvMap &map, const std::string &frame_id,
                                  srv::PlanTransitPath::Response &response);

        static msg::PathWaypoint to_msg(const PathWaypoint &waypoint);
        static msg::PlanningDebug to_msg(const PlanningDebug &debug);
        static uint8_t to_msg(WaypointType type);
        static uint8_t to_msg(Heading heading);
    };

} // namespace map_planner

#endif // MAP_PLANNER__PLANNING_ROS_ADAPTER_HPP_
