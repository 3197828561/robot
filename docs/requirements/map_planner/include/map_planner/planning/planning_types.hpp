#ifndef MAP_PLANNER__PLANNING_TYPES_HPP_
#define MAP_PLANNER__PLANNING_TYPES_HPP_

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace map_planner
{

    enum class WaypointType : uint8_t {
        Clean = 0,
        Deadhead = 1,
        TurnInPlace = 2,
        ApproachBridge = 3,
        BridgeCrossing = 4,
        ReinitVision = 5,
    };

    enum class Heading : uint8_t {
        BlockUPositive = 0,
        BlockUNegative = 1,
        BlockVPositive = 2,
        BlockVNegative = 3,
    };

    enum class SweepAxis : uint8_t {
        BlockU = 0,
        BlockV = 1,
    };

    enum class TraversabilityStatus : uint8_t {
        Free = 0,
        BlockedMissingCell = 1,
        BlockedBoundary = 2,
        BlockedMissingInflation = 3,
        BlockedBridgeEdge = 4,
        BlockedObstacle = 5,
        Unknown = 255,
    };

    struct RobotPlanningConfig {
        double robot_length_cm{120.0};
        double front_roller_width_cm{0.0};
        double rear_roller_width_cm{0.0};
        double robot_width_cm{70.0};
        double safety_margin_cm{10.0};
        double cleaning_width_cm{55.0};
        double overlap_ratio{0.2};
        bool enable_tail_coverage{true};
        std::string planning_search_effort{"balanced"};
        bool debug_score_breakdown{false};
    };

    double effective_chassis_length_cm(const RobotPlanningConfig &config);

    struct CenterInnerCell {
        uint32_t block_id{};
        int cell_row{};
        int cell_col{};
        int inner_row{};
        int inner_col{};
    };

    struct GridPose {
        CenterInnerCell center;
        Heading heading{Heading::BlockUPositive};
    };

    struct PathWaypoint {
        WaypointType type{WaypointType::Deadhead};
        std::optional<CenterInnerCell> center_inner_cell;
        std::optional<Heading> heading;
        bool brush_on{false};
        std::optional<uint32_t> bridge_id;
        int rotation_angle_deg{0};  // TurnInPlace rotation; CW/right+, CCW/left-, 0=same
    };

    struct PlanningRequest {
        uint32_t map_id{};
        uint32_t map_version{};
        GridPose start_pose;
        std::vector<uint32_t> target_block_ids;
        bool global_plan{true};
        RobotPlanningConfig config;
    };

    struct TransitPlanningRequest {
        uint32_t map_id{};
        uint32_t map_version{};
        GridPose start_pose;
        GridPose goal_pose;
        bool require_goal_heading{false};
        std::vector<uint32_t> allowed_block_ids;
        RobotPlanningConfig config;
    };

    struct PlanningDebug {
        bool coverage_complete{false};
        uint32_t selected_block_id{};
        SweepAxis selected_sweep_axis{SweepAxis::BlockU};
        int selected_lane_stride{};
        int lane_offset{};
        double total_cost{};
        std::vector<std::string> score_breakdown;
        std::vector<std::string> invalid_reasons;
        std::vector<std::string> unreachable_segments;
        std::vector<std::string> unusable_bridges;
        int blocked_boundary_count{};
        int blocked_missing_cell_count{};
        int blocked_missing_inflation_count{};
        int blocked_obstacle_count{};
    };

    struct PlanningResult {
        bool success{false};
        std::string message;
        std::vector<PathWaypoint> waypoints;
        PlanningDebug debug;
    };

    std::string to_string(WaypointType type);
    std::string to_string(Heading heading);
    std::string to_string(SweepAxis axis);

    // heading geometry (FRD, positive=right): U+(0°)  V-(90°)  U-(180°)  V+(-90°)
    int heading_angle_deg(Heading h);
    int rotation_between_headings_deg(Heading from, Heading to);
    void fill_rotation_angles(std::vector<PathWaypoint> &waypoints);

} // namespace map_planner

#endif // MAP_PLANNER__PLANNING_TYPES_HPP_
