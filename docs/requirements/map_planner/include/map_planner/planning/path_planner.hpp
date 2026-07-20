#ifndef MAP_PLANNER__PATH_PLANNER_HPP_
#define MAP_PLANNER__PATH_PLANNER_HPP_

#include "map_planner/map/map_repository.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    class IPathPlanner {
    public:
        virtual ~IPathPlanner() = default;

        virtual PlanningResult plan(const MapRepository &repository, const PlanningRequest &request) const = 0;
    };

} // namespace map_planner

#endif // MAP_PLANNER__PATH_PLANNER_HPP_
