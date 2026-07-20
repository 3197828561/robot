cmake_minimum_required(VERSION 3.22)
project(map_planner)

if(NOT CMAKE_CXX_STANDARD)
  set(CMAKE_CXX_STANDARD 20)
endif()
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(ament_cmake REQUIRED)
find_package(rclcpp REQUIRED)
find_package(std_msgs REQUIRED)
find_package(geometry_msgs REQUIRED)
find_package(visualization_msgs REQUIRED)
find_package(yaml-cpp REQUIRED)
find_package(rosidl_default_generators REQUIRED)

if(BUILD_TESTING)
  find_package(ament_cmake_gtest REQUIRED)
endif()

rosidl_generate_interfaces(${PROJECT_NAME}
  "msg/Point2D.msg"
  "msg/Block.msg"
  "msg/Cell.msg"
  "msg/BridgeEndpoint.msg"
  "msg/Bridge.msg"
  "msg/PvMap.msg"
  "msg/PathWaypoint.msg"
  "msg/PlanningDebug.msg"
  "msg/PlannedPath.msg"
  "msg/CenterPoseStatus.msg"
  "srv/GetMap.srv"
  "srv/GetCenterPoses.srv"
  "srv/GetCellId.srv"
  "srv/GetCellIndex.srv"
  "srv/ReloadMap.srv"
  "srv/PlanCoveragePath.srv"
  "srv/PlanTransitPath.srv"
  DEPENDENCIES std_msgs
)

add_library(map_planner_core
  src/map/yaml_map_importer.cpp
  src/map/json_map_importer.cpp
  src/map/map_repository.cpp
  src/map/map_visualizer.cpp
  src/map/map_geometry.cpp
  src/planning/planning_types.cpp
  src/planning/center_grid.cpp
  src/planning/lane_planner.cpp
  src/planning/motion_connector.cpp
  src/planning/bridge_transition_planner.cpp
  src/planning/transit_utils.cpp
  src/planning/transit_path_planner.cpp
  src/planning/block_coverage_candidate_generator.cpp
  src/planning/snake_coverage_planner.cpp
  src/planning/global_coverage_planner.cpp
  src/planning/planning_visualizer.cpp
  src/planning_ros_adapter.cpp
)

target_include_directories(map_planner_core PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  $<INSTALL_INTERFACE:include>
)

target_link_libraries(map_planner_core
  yaml-cpp
)

ament_target_dependencies(map_planner_core
  rclcpp
  geometry_msgs
  visualization_msgs
)

add_executable(map_planner_node
  src/main.cpp
  src/map_server_node.cpp
)
set_target_properties(map_planner_node PROPERTIES OUTPUT_NAME map_planner)

target_include_directories(map_planner_node PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  $<INSTALL_INTERFACE:include>
)

target_link_libraries(map_planner_node
  map_planner_core
)

ament_target_dependencies(map_planner_node
  rclcpp
  std_msgs
  geometry_msgs
  visualization_msgs
)

rosidl_get_typesupport_target(cpp_typesupport_target ${PROJECT_NAME} "rosidl_typesupport_cpp")
target_link_libraries(map_planner_core ${cpp_typesupport_target})
target_link_libraries(map_planner_node ${cpp_typesupport_target})

install(TARGETS map_planner_core map_planner_node
  ARCHIVE DESTINATION lib
  LIBRARY DESTINATION lib
  RUNTIME DESTINATION lib/${PROJECT_NAME}
)

install(DIRECTORY include/
  DESTINATION include
)

install(DIRECTORY launch config docs
  DESTINATION share/${PROJECT_NAME}
)

install(PROGRAMS
  tools/test_plan_path.py
  DESTINATION lib/${PROJECT_NAME}
)

install(FILES
  tools/plan_tui.py
  tools/plan_tui_model.py
  DESTINATION lib/${PROJECT_NAME}
)

if(BUILD_TESTING)
  ament_add_gtest(test_yaml_map_importer
    test/test_yaml_map_importer.cpp
    src/map/yaml_map_importer.cpp
    src/map/json_map_importer.cpp
    src/map/map_repository.cpp
  )
  target_include_directories(test_yaml_map_importer PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  )
  target_link_libraries(test_yaml_map_importer yaml-cpp)

  ament_add_gtest(test_map_geometry
    test/test_map_geometry.cpp
    src/map/map_geometry.cpp
    src/map/map_repository.cpp
  )
  target_include_directories(test_map_geometry PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  )

  ament_add_gtest(test_center_grid
    test/test_center_grid.cpp
  )
  target_include_directories(test_center_grid PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  )
  target_link_libraries(test_center_grid map_planner_core)

  ament_add_gtest(test_bridge_transition_planner
    test/test_bridge_transition_planner.cpp
  )
  target_include_directories(test_bridge_transition_planner PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  )
  target_link_libraries(test_bridge_transition_planner map_planner_core)

  ament_add_gtest(test_block_coverage_candidate_generator
    test/test_block_coverage_candidate_generator.cpp
  )
  target_include_directories(test_block_coverage_candidate_generator PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  )
  target_link_libraries(test_block_coverage_candidate_generator map_planner_core)

  ament_add_gtest(test_global_coverage_planner
    test/test_global_coverage_planner.cpp
  )
  target_include_directories(test_global_coverage_planner PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  )
  target_link_libraries(test_global_coverage_planner map_planner_core)

  ament_add_gtest(test_transit_path_planner
    test/test_transit_path_planner.cpp
  )
  target_include_directories(test_transit_path_planner PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  )
  target_link_libraries(test_transit_path_planner map_planner_core)

  ament_add_gtest(test_planning_ros_adapter
    test/test_planning_ros_adapter.cpp
  )
  target_include_directories(test_planning_ros_adapter PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  )
  target_link_libraries(test_planning_ros_adapter map_planner_core)
endif()

ament_package()
