#ifndef MAP_PLANNER__MOTION_CONNECTOR_HPP_
#define MAP_PLANNER__MOTION_CONNECTOR_HPP_

#include <string>
#include <vector>

#include "map_planner/planning/center_grid.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    class MotionConnector {
    public:
        bool append_connection(const GridPose &from, const GridPose &to, const CenterGrid &center_grid,
                               const CellModel &cell_model, std::vector<PathWaypoint> &waypoints,
                               std::string &error) const;

    private:
        static bool same_position(const CenterInnerCell &a, const CenterInnerCell &b);
        static Heading connection_heading(const CenterInnerCell &from, const CenterInnerCell &to, bool &valid);
    };

} // namespace map_planner

#endif // MAP_PLANNER__MOTION_CONNECTOR_HPP_
