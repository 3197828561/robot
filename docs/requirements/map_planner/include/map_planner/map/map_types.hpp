#ifndef MAP_PLANNER__MAP_TYPES_HPP_
#define MAP_PLANNER__MAP_TYPES_HPP_

#include <cstdint>
#include <string>
#include <vector>

namespace map_planner
{

    struct Point2D {
        double u_cm{};
        double v_cm{};
    };

    struct Frame {
        std::string unit{"centimeter"};
        double latitude_deg{};
        double longitude_deg{};
        double yaw_deg{};
        bool has_origin{false};
    };

    struct CellModel {
        int inner_rows{};
        int inner_cols{};
    };

    struct BlockFrame {
        Point2D block_origin;
        double u_axis_x{1.0};
        double u_axis_y{0.0};
        double v_axis_x{0.0};
        double v_axis_y{1.0};
    };

    struct Block {
        uint32_t block_id{};
        BlockFrame block_frame;
        int rows{};
        int cols{};
        std::vector<std::vector<int>> grid;
        std::vector<uint32_t> cell_ids;
        bool cleanable{true};
    };

    struct Cell {
        uint32_t cell_id{};
        uint32_t block_id{};
        int row{};
        int col{};
        std::vector<Point2D> polygon;
    };

    struct BridgeEndpoint {
        uint32_t block_id{};
        int cell_row{};
        int cell_col{};
        std::string edge;
        int inner_row{};
        int inner_col{};
    };

    struct Bridge {
        uint32_t bridge_id{};
        std::string source;
        std::vector<BridgeEndpoint> endpoints;
        std::vector<Point2D> centerline;
        std::vector<Point2D> polygon;
    };

    struct PvMap {
        uint32_t map_id{};
        uint32_t version{};
        Frame frame;
        CellModel cell_model;
        std::vector<Block> blocks;
        std::vector<Cell> cells;
        std::vector<Bridge> bridges;
    };

} // namespace map_planner

#endif // MAP_PLANNER__MAP_TYPES_HPP_
