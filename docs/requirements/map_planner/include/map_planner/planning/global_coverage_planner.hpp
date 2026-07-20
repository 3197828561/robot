#ifndef MAP_PLANNER__GLOBAL_COVERAGE_PLANNER_HPP_
#define MAP_PLANNER__GLOBAL_COVERAGE_PLANNER_HPP_

#include "map_planner/planning/block_coverage_candidate_generator.hpp"
#include "map_planner/planning/bridge_transition_planner.hpp"
#include "map_planner/planning/path_planner.hpp"
#include "map_planner/planning/snake_coverage_planner.hpp"

namespace map_planner
{

    class GlobalCoveragePlanner : public IPathPlanner {
    public:
        PlanningResult plan(const MapRepository &repository, const PlanningRequest &request) const override;

    private:
        SnakeCoveragePlanner single_block_planner_;
    };

} // namespace map_planner

#endif // MAP_PLANNER__GLOBAL_COVERAGE_PLANNER_HPP_
