package com.robot.solar.map

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.robot.solar.network.mqtt.PoseMessage
import java.io.File
import kotlin.math.atan2

class PvMapParser(private val gson: Gson = Gson()) {
    fun parse(file: File): PvMap = parse(file.readText(Charsets.UTF_8))

    fun parse(json: String): PvMap {
        val map = try {
            gson.fromJson(json, PvMap::class.java)
        } catch (error: JsonParseException) {
            throw MapValidationException("地图 JSON 解析失败", error)
        } ?: throw MapValidationException("地图 JSON 为空")
        validate(map)
        return map
    }

    fun validate(map: PvMap) {
        requireMap(map.mapId >= 0, "map_id 非法")
        requireMap(map.version >= 0, "version 非法")
        requireMap(map.frame.unit == "centimeter", "仅支持 centimeter 地图")
        requireMap(map.cellModel.innerRows > 0 && map.cellModel.innerCols > 0, "内部网格尺寸非法")
        requireMap(map.blocks.isNotEmpty() && map.cells.isNotEmpty(), "地图没有可绘制内容")
        requireUnique(map.blocks.map(MapBlock::blockId), "block_id")
        requireUnique(map.cells.map(MapCell::cellId), "cell_id")
        requireUnique(map.bridges.map(MapBridge::bridgeId), "bridge_id")
        map.blocks.forEach { block ->
            requireMap(block.rows > 0 && block.cols > 0, "block ${block.blockId} 行列非法")
            requireMap(block.grid.size == block.rows, "block ${block.blockId} grid 行数错误")
            requireMap(block.grid.all { it.size == block.cols }, "block ${block.blockId} grid 列数错误")
            requireVector(block.blockFrame.blockOrigin, "block_origin")
            requireVector(block.blockFrame.uAxis, "u_axis")
            requireVector(block.blockFrame.vAxis, "v_axis")
        }
        map.cells.forEach { cell ->
            val block = map.blocksById[cell.blockId] ?: throw MapValidationException("cell ${cell.cellId} 引用了不存在的 block")
            requireMap(cell.row in 0 until block.rows && cell.col in 0 until block.cols, "cell ${cell.cellId} 行列越界")
            requireMap(block.grid[cell.row][cell.col] == 1, "cell ${cell.cellId} 位于缺板位置")
            requireMap(cell.cellId in block.cellIds, "cell ${cell.cellId} 未登记到 block")
            requireMap(cell.polygon.size == 4, "cell ${cell.cellId} polygon 必须为 4 点")
            cell.polygon.forEach { requireVector(it, "cell ${cell.cellId} polygon") }
        }
        map.blocks.forEach { block ->
            requireMap(block.cellIds.all { it in map.cellsById }, "block ${block.blockId} 引用了不存在的 cell")
            requireMap(block.grid.sumOf { row -> row.count { it == 1 } } == block.cellIds.size, "block ${block.blockId} grid 与 cell_ids 数量不一致")
        }
        map.bridges.forEach { bridge ->
            requireMap(bridge.endpoints.size == 2, "bridge ${bridge.bridgeId} 必须有两个 endpoint")
            bridge.polygon.forEach { requireVector(it, "bridge polygon") }
            bridge.centerline.forEach { requireVector(it, "bridge centerline") }
            bridge.endpoints.forEach { endpoint ->
                val block = map.blocksById[endpoint.blockId] ?: throw MapValidationException("bridge 引用了不存在的 block")
                requireMap(endpoint.cellRow in 0 until block.rows && endpoint.cellCol in 0 until block.cols, "bridge endpoint 越界")
                requireMap(map.cellsByIndex.containsKey(Triple(endpoint.blockId, endpoint.cellRow, endpoint.cellCol)), "bridge endpoint 没有对应 cell")
                requireMap(endpoint.innerRow in 0 until map.cellModel.innerRows, "bridge inner_row 越界")
                requireMap(endpoint.innerCol in 0 until map.cellModel.innerCols, "bridge inner_col 越界")
                requireMap(endpoint.edge in EDGES, "bridge edge 非法")
            }
        }
    }

    fun resolvePose(map: PvMap, pose: PoseMessage): MapPosition? {
        if (pose.mapId != map.mapId || pose.mapVersion != map.version) return null
        var cell: MapCell? = pose.cellId?.let(map.cellsById::get)
        if (cell == null && pose.blockId != null && pose.cellRow != null && pose.cellCol != null) {
            cell = map.cellsByIndex[Triple(pose.blockId, pose.cellRow, pose.cellCol)]
        }
        cell ?: return null
        val row = pose.innerRow ?: return null
        val col = pose.innerCol ?: return null
        if (row !in 0 until map.cellModel.innerRows || col !in 0 until map.cellModel.innerCols) return null
        val points = cell.points()
        val u = (col + 0.5) / map.cellModel.innerCols
        val v = (row + 0.5) / map.cellModel.innerRows
        val point = bilinear(points[0], points[1], points[2], points[3], u, v)
        val block = map.blocksById[cell.blockId] ?: return null
        val axis = when (pose.headingCode ?: headingCodeFromName(pose.heading)) {
            0 -> block.blockFrame.uAxis
            1 -> listOf(-block.blockFrame.uAxis[0], -block.blockFrame.uAxis[1])
            2 -> block.blockFrame.vAxis
            3 -> listOf(-block.blockFrame.vAxis[0], -block.blockFrame.vAxis[1])
            else -> return null
        }
        return MapPosition(point, Math.toDegrees(atan2(axis[1], axis[0])))
    }

    private fun bilinear(p00: Point2D, p10: Point2D, p11: Point2D, p01: Point2D, u: Double, v: Double): Point2D {
        val a = (1 - u) * (1 - v)
        val b = u * (1 - v)
        val c = u * v
        val d = (1 - u) * v
        return Point2D(a * p00.u + b * p10.u + c * p11.u + d * p01.u, a * p00.v + b * p10.v + c * p11.v + d * p01.v)
    }

    private fun requireUnique(values: List<Long>, name: String) = requireMap(values.size == values.toSet().size, "$name 重复")
    private fun requireVector(value: List<Double>, name: String) = requireMap(value.size == 2 && value.all(Double::isFinite), "$name 坐标非法")
    private fun requireMap(condition: Boolean, message: String) {
        if (!condition) throw MapValidationException(message)
    }

    companion object {
        private val EDGES = setOf("u_min", "u_max", "v_min", "v_max")
        private fun headingCodeFromName(value: String?): Int? = when (value) {
            "block_u_positive" -> 0
            "block_u_negative" -> 1
            "block_v_positive" -> 2
            "block_v_negative" -> 3
            else -> null
        }
    }
}

class MapValidationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
