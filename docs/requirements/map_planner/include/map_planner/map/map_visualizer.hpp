#ifndef MAP_PLANNER__MAP_VISUALIZER_HPP_
#define MAP_PLANNER__MAP_VISUALIZER_HPP_

#include <string>

#include "visualization_msgs/msg/marker_array.hpp"

#include "map_planner/map/map_repository.hpp"
#include "map_planner/map/map_types.hpp"

namespace map_planner
{

    class MapVisualizer {
    public:
        visualization_msgs::msg::MarkerArray make_marker_array(const PvMap &map, const MapRepository &repository,
                                                               const std::string &frame_id,
                                                               bool show_cell_labels) const;

    private:
        static geometry_msgs::msg::Point to_marker_point(const Point2D &point, double z_m = 0.0);
        static Point2D polygon_center(const std::vector<Point2D> &polygon);
        static void add_inner_grid_marker(visualization_msgs::msg::MarkerArray &markers, const Cell &cell,
                                          const CellModel &cell_model, const std::string &frame_id, int &id);
        static Point2D interpolate_rect_cell_point(const Cell &cell, double col_ratio, double row_ratio);
        static Point2D derive_endpoint_point(const BridgeEndpoint &endpoint, const MapRepository &repository,
                                             const CellModel &cell_model);
    };

} // namespace map_planner

#endif // MAP_PLANNER__MAP_VISUALIZER_HPP_
