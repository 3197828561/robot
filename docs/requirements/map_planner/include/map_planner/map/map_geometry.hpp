#ifndef MAP_PLANNER__MAP_GEOMETRY_HPP_
#define MAP_PLANNER__MAP_GEOMETRY_HPP_

#include <array>

#include "map_planner/map/map_repository.hpp"
#include "map_planner/map/map_types.hpp"

namespace map_planner::map_geometry
{

    struct InnerCellGeometry {
        Point2D corner00;
        Point2D corner10;
        Point2D corner11;
        Point2D corner01;
        Point2D center;
        double inner_u_size_cm{};
        double inner_v_size_cm{};
    };

    struct BlockInnerCellSizeStats {
        double median_inner_u_size_cm{};
        double median_inner_v_size_cm{};
    };

    Point2D interpolate_cell_point(const Cell &cell, double u_ratio, double v_ratio);

    InnerCellGeometry make_inner_cell_geometry(const Cell &cell, int inner_rows, int inner_cols, int inner_row,
                                               int inner_col);

    Point2D derive_inner_cell_center(const Cell &cell, int inner_rows, int inner_cols, int inner_row, int inner_col);

    Point2D derive_bridge_endpoint_anchor(const Cell &cell, const BridgeEndpoint &endpoint, int inner_rows,
                                          int inner_cols);

    BlockInnerCellSizeStats estimate_block_inner_cell_size_stats(const PvMap &map, const MapRepository &repository,
                                                                 const Block &block);

    double distance_cm(const Point2D &a, const Point2D &b);

} // namespace map_planner::map_geometry

#endif // MAP_PLANNER__MAP_GEOMETRY_HPP_
