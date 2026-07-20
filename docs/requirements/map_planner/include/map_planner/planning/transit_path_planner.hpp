#ifndef MAP_PLANNER__TRANSIT_PATH_PLANNER_HPP_
#define MAP_PLANNER__TRANSIT_PATH_PLANNER_HPP_

#include "map_planner/map/map_repository.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    class TransitPathPlanner {
    public:
        PlanningResult plan(const MapRepository &repository, const TransitPlanningRequest &request) const;
    };

} // namespace map_planner

#endif // MAP_PLANNER__TRANSIT_PATH_PLANNER_HPP_
