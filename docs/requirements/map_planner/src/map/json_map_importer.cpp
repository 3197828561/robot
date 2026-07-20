#include "map_planner/map/json_map_importer.hpp"

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <stdexcept>
#include <string>

#include "map_planner/map/yaml_map_importer.hpp"

namespace map_planner {
namespace {

std::string lower_extension(const std::string &path) {
  std::string ext = std::filesystem::path(path).extension().string();
  std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) {
    return static_cast<char>(std::tolower(c));
  });
  return ext;
}

}  // namespace

bool JsonMapImporter::can_import(const std::string &path) const {
  return lower_extension(path) == ".json";
}

PvMap JsonMapImporter::import_from_file(const std::string &path) const {
  if (!can_import(path)) {
    throw std::runtime_error("unsupported map file extension: " + path);
  }

  YamlMapImporter yaml_importer;
  return yaml_importer.import_yaml_like_file(path);
}

}  // namespace map_planner
