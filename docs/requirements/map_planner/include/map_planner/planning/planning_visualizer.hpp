#ifndef MAP_PLANNER__PLANNING_VISUALIZER_HPP_
#define MAP_PLANNER__PLANNING_VISUALIZER_HPP_

#include <string>

#include "visualization_msgs/msg/marker_array.hpp"

#include "map_planner/map/map_repository.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    class PlanningVisualizer {
    public:
        visualization_msgs::msg::MarkerArray make_marker_array(const PlanningResult &result, const PvMap &map,
                                                               const MapRepository &repository,
                                                               const std::string &frame_id) const;
    };

} // namespace map_planner

#endif // MAP_PLANNER__PLANNING_VISUALIZER_HPP_
