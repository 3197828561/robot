#include "map_planner/map_server_node.hpp"

#include <algorithm>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <vector>

#include "map_planner/map/json_map_importer.hpp"
#include "map_planner/map/yaml_map_importer.hpp"
#include "map_planner/planning/center_grid.hpp"
#include "map_planner/planning/global_coverage_planner.hpp"
#include "map_planner/planning_ros_adapter.hpp"

namespace map_planner {
namespace {

constexpr const char *kMapTopic    = "pv_map";
constexpr const char *kMarkerTopic = "pv_map/markers";
constexpr const char *kPlannedPathMarkerTopic =
  "map_planner/planned_path_markers";

}  // namespace

MapServerNode::MapServerNode(const rclcpp::NodeOptions &options)
    : Node("map_planner", options) {
  map_file_         = declare_parameter<std::string>("map_file", "");
  frame_id_         = declare_parameter<std::string>("frame_id", "pv_map");
  publish_rate_hz_  = declare_parameter<double>("publish_rate_hz", 1.0);
  publish_markers_  = declare_parameter<bool>("publish_markers", true);
  show_cell_labels_ = declare_parameter<bool>("show_cell_labels", false);
  auto_plan_        = declare_parameter<bool>("auto_plan", false);
  planning_config_.planning_search_effort =
    declare_parameter<std::string>("planning_search_effort", "balanced");
  planning_config_.debug_score_breakdown =
    declare_parameter<bool>("debug_score_breakdown", false);
  planning_config_.robot_length_cm =
    declare_parameter<double>("robot_length_cm", 120.0);
  planning_config_.front_roller_width_cm =
    declare_parameter<double>("front_roller_width_cm", 0.0);
  planning_config_.rear_roller_width_cm =
    declare_parameter<double>("rear_roller_width_cm", 0.0);
  planning_config_.robot_width_cm =
    declare_parameter<double>("robot_width_cm", 70.0);
  planning_config_.safety_margin_cm =
    declare_parameter<double>("safety_margin_cm", 10.0);
  planning_config_.cleaning_width_cm =
    declare_parameter<double>("cleaning_width_cm", 55.0);
  planning_config_.overlap_ratio =
    declare_parameter<double>("overlap_ratio", 0.2);
  planning_config_.enable_tail_coverage =
    declare_parameter<bool>("enable_tail_coverage", true);

  const auto qos =
    rclcpp::QoS(rclcpp::KeepLast(1)).reliable().transient_local();
  map_pub_ = create_publisher<msg::PvMap>(kMapTopic, qos);
  marker_pub_ =
    create_publisher<visualization_msgs::msg::MarkerArray>(kMarkerTopic, qos);
  planned_path_marker_pub_ =
    create_publisher<visualization_msgs::msg::MarkerArray>(
      kPlannedPathMarkerTopic, qos);
  planner_ = std::make_unique<GlobalCoveragePlanner>();

  get_map_srv_ = create_service<srv::GetMap>(
    "map_planner/get_map", std::bind(
                             &MapServerNode::handle_get_map, this,
                             std::placeholders::_1, std::placeholders::_2));
  reload_map_srv_ = create_service<srv::ReloadMap>(
    "map_planner/reload_map", std::bind(
                                &MapServerNode::handle_reload_map, this,
                                std::placeholders::_1, std::placeholders::_2));
  get_cell_id_srv_ = create_service<srv::GetCellId>(
    "map_planner/get_cell_id", std::bind(
                                 &MapServerNode::handle_get_cell_id, this,
                                 std::placeholders::_1, std::placeholders::_2));
  get_cell_index_srv_ = create_service<srv::GetCellIndex>(
    "map_planner/get_cell_index",
    std::bind(
      &MapServerNode::handle_get_cell_index, this, std::placeholders::_1,
      std::placeholders::_2));
  plan_coverage_path_srv_ = create_service<srv::PlanCoveragePath>(
    "map_planner/plan_coverage_path",
    std::bind(
      &MapServerNode::handle_plan_coverage_path, this, std::placeholders::_1,
      std::placeholders::_2));
  plan_transit_path_srv_ = create_service<srv::PlanTransitPath>(
    "map_planner/plan_transit_path",
    std::bind(
      &MapServerNode::handle_plan_transit_path, this, std::placeholders::_1,
      std::placeholders::_2));
  get_center_poses_srv_ = create_service<srv::GetCenterPoses>(
    "map_planner/get_center_poses",
    std::bind(
      &MapServerNode::handle_get_center_poses, this, std::placeholders::_1,
      std::placeholders::_2));

  if (!map_file_.empty()) {
    try {
      load_map(map_file_);
    } catch (const std::exception &ex) {
      RCLCPP_ERROR(
        get_logger(), "Failed to load map '%s': %s", map_file_.c_str(),
        ex.what());
    }
  } else {
    RCLCPP_WARN(
      get_logger(),
      "Parameter 'map_file' is empty; map server "
      "starts without a loaded map");
  }

  const double safe_rate = publish_rate_hz_ > 0.0 ? publish_rate_hz_ : 1.0;
  const auto period      = std::chrono::duration<double>(1.0 / safe_rate);
  publish_timer_         = create_wall_timer(
    std::chrono::duration_cast<std::chrono::nanoseconds>(period),
    std::bind(&MapServerNode::publish_map, this));
}

void MapServerNode::load_map(const std::string &path) {
  auto importer = create_importer(path);
  auto map      = importer->import_from_file(path);
  repository_.set_map(std::move(map));
  map_file_ = path;
  RCLCPP_INFO(get_logger(), "Loaded PV map from '%s'", path.c_str());
  publish_map();
  try_auto_plan();
}

void MapServerNode::publish_map() {
  if (!repository_.has_map()) {
    return;
  }

  const auto &map = repository_.map();
  map_pub_->publish(to_msg(map));

  if (publish_markers_) {
    marker_pub_->publish(visualizer_.make_marker_array(
      map, repository_, frame_id_, show_cell_labels_));
  }
}

void MapServerNode::publish_clear_planned_path_markers() {
  visualization_msgs::msg::MarkerArray deletes;

  visualization_msgs::msg::Marker delete_all;
  delete_all.header.frame_id = frame_id_;
  delete_all.header.stamp    = now();
  delete_all.ns              = "planned_path_clear";
  delete_all.id              = 0;
  delete_all.action          = visualization_msgs::msg::Marker::DELETEALL;
  deletes.markers.push_back(delete_all);

  for (const auto &old_marker : last_planned_path_markers_.markers) {
    if (old_marker.action != visualization_msgs::msg::Marker::ADD) {
      continue;
    }
    visualization_msgs::msg::Marker marker;
    marker.header.frame_id = old_marker.header.frame_id.empty()
                               ? frame_id_
                               : old_marker.header.frame_id;
    marker.header.stamp    = now();
    marker.ns              = old_marker.ns;
    marker.id              = old_marker.id;
    marker.action          = visualization_msgs::msg::Marker::DELETE;
    deletes.markers.push_back(marker);
  }

  planned_path_marker_pub_->publish(deletes);
  last_planned_path_markers_.markers.clear();
}

void MapServerNode::publish_planned_path_markers(
  const PlanningResult &result, const PvMap &map) {
  publish_clear_planned_path_markers();
  last_planned_path_markers_ =
    planning_visualizer_.make_marker_array(result, map, repository_, frame_id_);
  planned_path_marker_pub_->publish(last_planned_path_markers_);
}

std::vector<uint32_t> MapServerNode::cleanable_block_ids() const {
  std::vector<uint32_t> block_ids;
  if (!repository_.has_map()) {
    return block_ids;
  }
  for (const auto &block : repository_.map().blocks) {
    if (block.cleanable) {
      block_ids.push_back(block.block_id);
    }
  }
  return block_ids;
}

msg::PvMap MapServerNode::to_msg(const PvMap &map) const {
  msg::PvMap msg;
  msg.header.stamp    = now();
  msg.header.frame_id = frame_id_;
  msg.map_id          = map.map_id;
  msg.version         = map.version;
  msg.unit            = map.frame.unit;
  msg.inner_rows      = static_cast<uint32_t>(map.cell_model.inner_rows);
  msg.inner_cols      = static_cast<uint32_t>(map.cell_model.inner_cols);

  for (const auto &block : map.blocks) {
    map_planner::msg::Block block_msg;
    block_msg.block_id = block.block_id;
    block_msg.rows     = static_cast<uint32_t>(block.rows);
    block_msg.cols     = static_cast<uint32_t>(block.cols);
    for (const auto &row : block.grid) {
      for (const auto value : row) {
        block_msg.grid_flat.push_back(value);
      }
    }
    block_msg.cell_ids    = block.cell_ids;
    block_msg.cleanable   = block.cleanable;
    block_msg.origin_u_cm = block.block_frame.block_origin.u_cm;
    block_msg.origin_v_cm = block.block_frame.block_origin.v_cm;
    block_msg.u_axis_x    = block.block_frame.u_axis_x;
    block_msg.u_axis_y    = block.block_frame.u_axis_y;
    block_msg.v_axis_x    = block.block_frame.v_axis_x;
    block_msg.v_axis_y    = block.block_frame.v_axis_y;
    msg.blocks.push_back(block_msg);
  }

  for (const auto &cell : map.cells) {
    map_planner::msg::Cell cell_msg;
    cell_msg.cell_id  = cell.cell_id;
    cell_msg.block_id = cell.block_id;
    cell_msg.row      = cell.row;
    cell_msg.col      = cell.col;
    for (const auto &point : cell.polygon) {
      map_planner::msg::Point2D point_msg;
      point_msg.u_cm = point.u_cm;
      point_msg.v_cm = point.v_cm;
      cell_msg.polygon.push_back(point_msg);
    }
    msg.cells.push_back(cell_msg);
  }

  for (const auto &bridge : map.bridges) {
    map_planner::msg::Bridge bridge_msg;
    bridge_msg.bridge_id = bridge.bridge_id;
    bridge_msg.source    = bridge.source;
    for (const auto &endpoint : bridge.endpoints) {
      map_planner::msg::BridgeEndpoint endpoint_msg;
      endpoint_msg.block_id  = endpoint.block_id;
      endpoint_msg.cell_row  = endpoint.cell_row;
      endpoint_msg.cell_col  = endpoint.cell_col;
      endpoint_msg.edge      = endpoint.edge;
      endpoint_msg.inner_row = endpoint.inner_row;
      endpoint_msg.inner_col = endpoint.inner_col;
      bridge_msg.endpoints.push_back(endpoint_msg);
    }
    for (const auto &point : bridge.centerline) {
      map_planner::msg::Point2D point_msg;
      point_msg.u_cm = point.u_cm;
      point_msg.v_cm = point.v_cm;
      bridge_msg.centerline.push_back(point_msg);
    }
    for (const auto &point : bridge.polygon) {
      map_planner::msg::Point2D point_msg;
      point_msg.u_cm = point.u_cm;
      point_msg.v_cm = point.v_cm;
      bridge_msg.polygon.push_back(point_msg);
    }
    msg.bridges.push_back(bridge_msg);
  }

  return msg;
}

std::unique_ptr<MapImporter> MapServerNode::create_importer(
  const std::string &path) const {
  std::vector<std::unique_ptr<MapImporter>> importers;
  importers.push_back(std::make_unique<YamlMapImporter>());
  importers.push_back(std::make_unique<JsonMapImporter>());

  for (auto &importer : importers) {
    if (importer->can_import(path)) {
      return std::move(importer);
    }
  }

  throw std::runtime_error("no importer supports map file: " + path);
}

void MapServerNode::handle_get_map(
  const std::shared_ptr<srv::GetMap::Request> /* request */,
  std::shared_ptr<srv::GetMap::Response> response) {
  if (!repository_.has_map()) {
    response->success = false;
    response->message = "map is not loaded";
    return;
  }
  response->success = true;
  response->message = "ok";
  response->map     = to_msg(repository_.map());
}

void MapServerNode::handle_reload_map(
  const std::shared_ptr<srv::ReloadMap::Request> request,
  std::shared_ptr<srv::ReloadMap::Response> response) {
  const std::string path =
    request->map_file.empty() ? map_file_ : request->map_file;
  if (path.empty()) {
    response->success = false;
    response->message = "map_file is empty";
    return;
  }

  try {
    load_map(path);
    response->success = true;
    response->message = "ok";
  } catch (const std::exception &ex) {
    response->success = false;
    response->message = ex.what();
  }
}

void MapServerNode::handle_get_cell_id(
  const std::shared_ptr<srv::GetCellId::Request> request,
  std::shared_ptr<srv::GetCellId::Response> response) {
  if (!repository_.has_map()) {
    response->success = false;
    response->message = "map is not loaded";
    return;
  }
  if (request->map_id != repository_.map().map_id) {
    response->success = false;
    response->message = "map_id mismatch";
    return;
  }

  uint32_t cell_id = 0;
  if (!repository_.get_cell_id(
        request->block_id, request->cell_row, request->cell_col, cell_id)) {
    response->success = false;
    response->message = "cell does not exist or is missing";
    return;
  }
  response->success = true;
  response->message = "ok";
  response->cell_id = cell_id;
}

void MapServerNode::handle_get_cell_index(
  const std::shared_ptr<srv::GetCellIndex::Request> request,
  std::shared_ptr<srv::GetCellIndex::Response> response) {
  if (!repository_.has_map()) {
    response->success = false;
    response->message = "map is not loaded";
    return;
  }
  if (request->map_id != repository_.map().map_id) {
    response->success = false;
    response->message = "map_id mismatch";
    return;
  }

  uint32_t block_id = 0;
  int row           = 0;
  int col           = 0;
  if (!repository_.get_cell_index(request->cell_id, block_id, row, col)) {
    response->success = false;
    response->message = "cell_id does not exist";
    return;
  }
  response->success  = true;
  response->message  = "ok";
  response->block_id = block_id;
  response->cell_row = row;
  response->cell_col = col;
}

void MapServerNode::try_auto_plan() {
  if (!auto_plan_) {
    return;
  }
  RCLCPP_WARN(
    get_logger(),
    "auto_plan is disabled for the mission-facing API because a fixed start pose "
    "is required; call /map_planner/plan_coverage_path instead");
}

void MapServerNode::handle_plan_coverage_path(
  const std::shared_ptr<srv::PlanCoveragePath::Request> request,
  std::shared_ptr<srv::PlanCoveragePath::Response> response) {
  if (!repository_.has_map()) {
    response->success = false;
    response->message = "map is not loaded";
    return;
  }
  if (request->map_id != repository_.map().map_id) {
    response->success = false;
    response->message = "map_id mismatch";
    return;
  }
  if (request->map_version != repository_.map().version) {
    response->success = false;
    response->message = "map_version mismatch";
    return;
  }

  try {
    const auto planning_request = PlanningRosAdapter::from_request(*request);
    const auto result           = planner_->plan(repository_, planning_request);
    PlanningRosAdapter::fill_response(
      result, repository_.map(), frame_id_, *response);
    if (response->success) {
      publish_planned_path_markers(result, repository_.map());
    }
  } catch (const std::exception &ex) {
    response->success = false;
    response->message = ex.what();
  }
}

void MapServerNode::handle_plan_transit_path(
  const std::shared_ptr<srv::PlanTransitPath::Request> request,
  std::shared_ptr<srv::PlanTransitPath::Response> response) {
  if (!repository_.has_map()) {
    response->success = false;
    response->message = "map is not loaded";
    return;
  }
  if (request->map_id != repository_.map().map_id) {
    response->success = false;
    response->message = "map_id mismatch";
    return;
  }
  if (request->map_version != repository_.map().version) {
    response->success = false;
    response->message = "map_version mismatch";
    return;
  }

  try {
    const auto planning_request = PlanningRosAdapter::from_request(*request);
    const auto result = transit_planner_.plan(repository_, planning_request);
    PlanningRosAdapter::fill_response(
      result, repository_.map(), frame_id_, *response);
    if (response->success) {
      publish_planned_path_markers(result, repository_.map());
    }
  } catch (const std::exception &ex) {
    response->success = false;
    response->message = ex.what();
  }
}

void MapServerNode::handle_get_center_poses(
  const std::shared_ptr<srv::GetCenterPoses::Request> request,
  std::shared_ptr<srv::GetCenterPoses::Response> response) {
  if (!repository_.has_map()) {
    response->success = false;
    response->message = "map is not loaded";
    return;
  }
  if (request->map_id != repository_.map().map_id) {
    response->success = false;
    response->message = "map_id mismatch";
    return;
  }
  if (request->map_version != repository_.map().version) {
    response->success = false;
    response->message = "map_version mismatch";
    return;
  }

  RobotPlanningConfig config;
  config.robot_length_cm       = request->robot_length_cm;
  config.front_roller_width_cm = request->front_roller_width_cm;
  config.rear_roller_width_cm  = request->rear_roller_width_cm;
  config.robot_width_cm        = request->robot_width_cm;
  config.safety_margin_cm      = request->safety_margin_cm;

  const auto &map = repository_.map();
  CenterGridBuilder builder;
  const auto center_grid = builder.build(map, repository_, config);

  std::unordered_set<uint32_t> block_filter;
  if (!request->block_ids.empty()) {
    block_filter.insert(request->block_ids.begin(), request->block_ids.end());
  }

  std::unordered_set<uint8_t> heading_filter;
  if (!request->headings.empty()) {
    heading_filter.insert(request->headings.begin(), request->headings.end());
  }

  const std::vector<Heading> all_headings = {
    Heading::BlockUPositive,
    Heading::BlockUNegative,
    Heading::BlockVPositive,
    Heading::BlockVNegative,
  };

  for (const auto &block : map.blocks) {
    if (!block.cleanable) {
      continue;
    }
    if (!block_filter.empty() && block_filter.count(block.block_id) == 0) {
      continue;
    }

    for (const auto heading : all_headings) {
      if (
        !heading_filter.empty() &&
        heading_filter.count(static_cast<uint8_t>(heading)) == 0) {
        continue;
      }

      for (int cell_row = 0; cell_row < static_cast<int>(block.rows);
           ++cell_row) {
        for (int cell_col = 0; cell_col < static_cast<int>(block.cols);
             ++cell_col) {
          for (int inner_row = 0;
               inner_row < static_cast<int>(map.cell_model.inner_rows);
               ++inner_row) {
            for (int inner_col = 0;
                 inner_col < static_cast<int>(map.cell_model.inner_cols);
                 ++inner_col) {
              CenterInnerCell center;
              center.block_id  = block.block_id;
              center.cell_row  = cell_row;
              center.cell_col  = cell_col;
              center.inner_row = inner_row;
              center.inner_col = inner_col;

              const auto status = center_grid.status(center, heading);
              if (request->free_only && status != TraversabilityStatus::Free) {
                continue;
              }

              msg::CenterPoseStatus pose_status;
              pose_status.block_id  = center.block_id;
              pose_status.cell_row  = center.cell_row;
              pose_status.cell_col  = center.cell_col;
              pose_status.inner_row = center.inner_row;
              pose_status.inner_col = center.inner_col;
              pose_status.heading   = static_cast<uint8_t>(heading);
              pose_status.status    = static_cast<uint8_t>(status);
              response->poses.push_back(pose_status);
            }
          }
        }
      }
    }
  }

  response->success     = true;
  response->message     = "ok";
  response->map_id      = map.map_id;
  response->map_version = map.version;
}

}  // namespace map_planner
