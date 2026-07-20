#ifndef MAP_PLANNER__MAP_IMPORTER_HPP_
#define MAP_PLANNER__MAP_IMPORTER_HPP_

#include <string>

#include "map_planner/map/map_types.hpp"

namespace map_planner
{

    class MapImporter {
    public:
        virtual ~MapImporter() = default;

        virtual bool can_import(const std::string &path) const = 0;
        virtual PvMap import_from_file(const std::string &path) const = 0;
    };

} // namespace map_planner

#endif // MAP_PLANNER__MAP_IMPORTER_HPP_
