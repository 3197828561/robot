#ifndef MAP_PLANNER__CENTER_GRID_HPP_
#define MAP_PLANNER__CENTER_GRID_HPP_

#include <cstddef>
#include <unordered_map>
#include <vector>

#include "map_planner/map/map_repository.hpp"
#include "map_planner/planning/planning_types.hpp"

namespace map_planner
{

    class CenterGrid {
    public:
        void set_status(const CenterInnerCell &center, Heading heading, TraversabilityStatus status);

        TraversabilityStatus status(const CenterInnerCell &center, Heading heading) const;

        bool is_traversable(const CenterInnerCell &center, Heading heading) const;
        bool is_traversable(const GridPose &pose) const;

    private:
        struct Key {
            uint32_t block_id{};
            int cell_row{};
            int cell_col{};
            int inner_row{};
            int inner_col{};
            Heading heading{Heading::BlockUPositive};

            bool operator==(const Key &other) const;
        };

        struct KeyHash {
            size_t operator()(const Key &key) const;
        };

        static Key make_key(const CenterInnerCell &center, Heading heading);

        std::unordered_map<Key, TraversabilityStatus, KeyHash> statuses_;
    };

    class CenterGridBuilder {
    public:
        CenterGrid build(const PvMap &map, const MapRepository &repository, const RobotPlanningConfig &config) const;
        CenterGrid build_block(const PvMap &map,
                               const MapRepository &repository,
                               const Block &block,
                               const RobotPlanningConfig &config) const;

    private:
        static std::vector<Heading> headings();
    };

} // namespace map_planner

#endif // MAP_PLANNER__CENTER_GRID_HPP_
