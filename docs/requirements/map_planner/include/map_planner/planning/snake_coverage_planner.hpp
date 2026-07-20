#ifndef MAP_PLANNER__SNAKE_COVERAGE_PLANNER_HPP_
#define MAP_PLANNER__SNAKE_COVERAGE_PLANNER_HPP_

#include "map_planner/planning/lane_planner.hpp"
#include "map_planner/planning/motion_connector.hpp"
#include "map_planner/planning/path_planner.hpp"

namespace map_planner
{

    class SnakeCoveragePlanner : public IPathPlanner {
    public:
        PlanningResult plan(const MapRepository &repository, const PlanningRequest &request) const override;

    private:
        static PathWaypoint make_clean_waypoint(const CenterInnerCell &center, Heading heading);
        static double candidate_cost(const LanePlanCandidate &candidate);
    };

} // namespace map_planner

#endif // MAP_PLANNER__SNAKE_COVERAGE_PLANNER_HPP_
