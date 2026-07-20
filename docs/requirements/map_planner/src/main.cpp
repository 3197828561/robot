#include "map_planner/map_server_node.hpp"
#include "rclcpp/rclcpp.hpp"

int main(int argc, char **argv) {
  rclcpp::init(argc, argv);
  rclcpp::spin(std::make_shared<map_planner::MapServerNode>());
  rclcpp::shutdown();
  return 0;
}
