#ifndef MAP_PLANNER__MAP_SERVER_NODE_HPP_
#define MAP_PLANNER__MAP_SERVER_NODE_HPP_

#include <memory>
#include <string>
#include <vector>

#include "rclcpp/rclcpp.hpp"
#include "visualization_msgs/msg/marker_array.hpp"

#include "map_planner/map/map_importer.hpp"
#include "map_planner/map/map_repository.hpp"
#include "map_planner/map/map_visualizer.hpp"
#include "map_planner/msg/pv_map.hpp"
#include "map_planner/planning/path_planner.hpp"
#include "map_planner/planning/planning_visualizer.hpp"
#include "map_planner/planning/transit_path_planner.hpp"
#include "map_planner/srv/get_center_poses.hpp"
#include "map_planner/srv/get_cell_id.hpp"
#include "map_planner/srv/get_cell_index.hpp"
#include "map_planner/srv/get_map.hpp"
#include "map_planner/srv/plan_coverage_path.hpp"
#include "map_planner/srv/plan_transit_path.hpp"
#include "map_planner/srv/reload_map.hpp"

namespace map_planner
{

    class MapServerNode : public rclcpp::Node {
    public:
        explicit MapServerNode(const rclcpp::NodeOptions &options = rclcpp::NodeOptions());

    private:
        void load_map(const std::string &path);
        void publish_map();
        void publish_clear_planned_path_markers();
        void publish_planned_path_markers(const PlanningResult &result, const PvMap &map);
        void try_auto_plan();
        std::vector<uint32_t> cleanable_block_ids() const;
        msg::PvMap to_msg(const PvMap &map) const;
        std::unique_ptr<MapImporter> create_importer(const std::string &path) const;

        void handle_get_map(const std::shared_ptr<srv::GetMap::Request> request,
                            std::shared_ptr<srv::GetMap::Response> response);
        void handle_reload_map(const std::shared_ptr<srv::ReloadMap::Request> request,
                               std::shared_ptr<srv::ReloadMap::Response> response);
        void handle_get_cell_id(const std::shared_ptr<srv::GetCellId::Request> request,
                                std::shared_ptr<srv::GetCellId::Response> response);
        void handle_get_cell_index(const std::shared_ptr<srv::GetCellIndex::Request> request,
                                   std::shared_ptr<srv::GetCellIndex::Response> response);
        void handle_plan_coverage_path(const std::shared_ptr<srv::PlanCoveragePath::Request> request,
                                       std::shared_ptr<srv::PlanCoveragePath::Response> response);
        void handle_plan_transit_path(const std::shared_ptr<srv::PlanTransitPath::Request> request,
                                      std::shared_ptr<srv::PlanTransitPath::Response> response);
        void handle_get_center_poses(const std::shared_ptr<srv::GetCenterPoses::Request> request,
                                     std::shared_ptr<srv::GetCenterPoses::Response> response);

        std::string map_file_;
        std::string frame_id_;
        double publish_rate_hz_{};
        bool publish_markers_{};
        bool show_cell_labels_{};
        bool auto_plan_{false};

        RobotPlanningConfig planning_config_;

        MapRepository repository_;
        MapVisualizer visualizer_;
        PlanningVisualizer planning_visualizer_;
        std::unique_ptr<IPathPlanner> planner_;
        TransitPathPlanner transit_planner_;

        rclcpp::Publisher<msg::PvMap>::SharedPtr map_pub_;
        rclcpp::Publisher<visualization_msgs::msg::MarkerArray>::SharedPtr marker_pub_;
        rclcpp::Publisher<visualization_msgs::msg::MarkerArray>::SharedPtr planned_path_marker_pub_;
        rclcpp::Service<srv::GetMap>::SharedPtr get_map_srv_;
        rclcpp::Service<srv::ReloadMap>::SharedPtr reload_map_srv_;
        rclcpp::Service<srv::GetCellId>::SharedPtr get_cell_id_srv_;
        rclcpp::Service<srv::GetCellIndex>::SharedPtr get_cell_index_srv_;
        rclcpp::Service<srv::PlanCoveragePath>::SharedPtr plan_coverage_path_srv_;
        rclcpp::Service<srv::PlanTransitPath>::SharedPtr plan_transit_path_srv_;
        rclcpp::Service<srv::GetCenterPoses>::SharedPtr get_center_poses_srv_;
        rclcpp::TimerBase::SharedPtr publish_timer_;
        visualization_msgs::msg::MarkerArray last_planned_path_markers_;
    };

} // namespace map_planner

#endif // MAP_PLANNER__MAP_SERVER_NODE_HPP_
