from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition
from launch.substitutions import LaunchConfiguration, PathJoinSubstitution
from launch_ros.actions import Node
from launch_ros.substitutions import FindPackageShare


def generate_launch_description():
    map_file = LaunchConfiguration('map_file')
    frame_id = LaunchConfiguration('frame_id')
    publish_rate_hz = LaunchConfiguration('publish_rate_hz')
    publish_markers = LaunchConfiguration('publish_markers')
    show_cell_labels = LaunchConfiguration('show_cell_labels')
    auto_plan = LaunchConfiguration('auto_plan')
    planning_search_effort = LaunchConfiguration('planning_search_effort')
    debug_score_breakdown = LaunchConfiguration('debug_score_breakdown')
    robot_length_cm = LaunchConfiguration('robot_length_cm')
    front_roller_width_cm = LaunchConfiguration('front_roller_width_cm')
    rear_roller_width_cm = LaunchConfiguration('rear_roller_width_cm')
    robot_width_cm = LaunchConfiguration('robot_width_cm')
    safety_margin_cm = LaunchConfiguration('safety_margin_cm')
    cleaning_width_cm = LaunchConfiguration('cleaning_width_cm')
    overlap_ratio = LaunchConfiguration('overlap_ratio')
    enable_tail_coverage = LaunchConfiguration('enable_tail_coverage')
    use_rviz = LaunchConfiguration('use_rviz')
    rviz_config = LaunchConfiguration('rviz_config')

    default_rviz_config = PathJoinSubstitution([
        FindPackageShare('map_planner'),
        'config',
        'pv_map.rviz',
    ])

    return LaunchDescription([
        DeclareLaunchArgument('map_file', default_value=''),
        DeclareLaunchArgument('frame_id', default_value='pv_map'),
        DeclareLaunchArgument('publish_rate_hz', default_value='1.0'),
        DeclareLaunchArgument('publish_markers', default_value='true'),
        DeclareLaunchArgument('show_cell_labels', default_value='true'),
        DeclareLaunchArgument('auto_plan', default_value='false'),
        DeclareLaunchArgument('planning_search_effort', default_value='quality'),
        DeclareLaunchArgument('debug_score_breakdown', default_value='false'),
        DeclareLaunchArgument('robot_length_cm', default_value='120.0'),
        DeclareLaunchArgument('front_roller_width_cm', default_value='20.0'),
        DeclareLaunchArgument('rear_roller_width_cm', default_value='20.0'),
        DeclareLaunchArgument('robot_width_cm', default_value='60.0'),
        DeclareLaunchArgument('safety_margin_cm', default_value='0.0'),
        DeclareLaunchArgument('cleaning_width_cm', default_value='80.0'),
        DeclareLaunchArgument('overlap_ratio', default_value='0.1'),
        DeclareLaunchArgument('enable_tail_coverage', default_value='true'),
        DeclareLaunchArgument('use_rviz', default_value='true'),
        DeclareLaunchArgument('rviz_config', default_value=default_rviz_config),
        Node(
            package='map_planner',
            executable='map_planner',
            name='map_planner',
            output='screen',
            parameters=[{
                'map_file': map_file,
                'frame_id': frame_id,
                'publish_rate_hz': publish_rate_hz,
                'publish_markers': publish_markers,
                'show_cell_labels': show_cell_labels,
                'auto_plan': auto_plan,
                'planning_search_effort': planning_search_effort,
                'debug_score_breakdown': debug_score_breakdown,
                'robot_length_cm': robot_length_cm,
                'front_roller_width_cm': front_roller_width_cm,
                'rear_roller_width_cm': rear_roller_width_cm,
                'robot_width_cm': robot_width_cm,
                'safety_margin_cm': safety_margin_cm,
                'cleaning_width_cm': cleaning_width_cm,
                'overlap_ratio': overlap_ratio,
                'enable_tail_coverage': enable_tail_coverage,
            }],
        ),
        Node(
            package='rviz2',
            executable='rviz2',
            name='rviz2',
            output='screen',
            condition=IfCondition(use_rviz),
            arguments=['-d', rviz_config],
        ),
    ])
