#ifndef MAP_PLANNER__YAML_MAP_IMPORTER_HPP_
#define MAP_PLANNER__YAML_MAP_IMPORTER_HPP_

#include <string>

#include "map_planner/map/map_importer.hpp"

namespace map_planner
{

    class YamlMapImporter : public MapImporter {
    public:
        bool can_import(const std::string &path) const override;
        PvMap import_from_file(const std::string &path) const override;
        PvMap import_yaml_like_file(const std::string &path) const;
    };

} // namespace map_planner

#endif // MAP_PLANNER__YAML_MAP_IMPORTER_HPP_
