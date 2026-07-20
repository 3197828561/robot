#ifndef MAP_PLANNER__MAP_REPOSITORY_HPP_
#define MAP_PLANNER__MAP_REPOSITORY_HPP_

#include <cstdint>
#include <string>
#include <unordered_map>

#include "map_planner/map/map_types.hpp"

namespace map_planner
{

    class MapRepository {
    public:
        void set_map(PvMap map);
        const PvMap &map() const;
        bool has_map() const;

        const Block *find_block(uint32_t block_id) const;
        const Cell *find_cell(uint32_t cell_id) const;
        const Cell *find_cell(uint32_t block_id, int row, int col) const;

        bool is_cell_present(uint32_t block_id, int row, int col) const;
        bool get_cell_id(uint32_t block_id, int row, int col, uint32_t &cell_id) const;
        bool get_cell_index(uint32_t cell_id, uint32_t &block_id, int &row, int &col) const;

    private:
        static std::string cell_key(uint32_t block_id, int row, int col);
        void rebuild_indexes();

        bool has_map_{false};
        PvMap map_;
        std::unordered_map<uint32_t, size_t> block_by_id_;
        std::unordered_map<uint32_t, size_t> cell_by_id_;
        std::unordered_map<std::string, size_t> cell_by_block_row_col_;
    };

} // namespace map_planner

#endif // MAP_PLANNER__MAP_REPOSITORY_HPP_
