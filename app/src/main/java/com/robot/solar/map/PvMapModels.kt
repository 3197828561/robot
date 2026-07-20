package com.robot.solar.map

import com.google.gson.annotations.SerializedName

data class Point2D(val u: Double, val v: Double)

data class PvMap(
    @SerializedName("map_id") val mapId: Long,
    val version: Int,
    val source: MapSource? = null,
    val frame: MapFrame,
    @SerializedName("cell_model") val cellModel: CellModel,
    val blocks: List<MapBlock>,
    val bridges: List<MapBridge> = emptyList(),
    val cells: List<MapCell>
) {
    val cellsById: Map<Long, MapCell> get() = cells.associateBy(MapCell::cellId)
    val blocksById: Map<Long, MapBlock> get() = blocks.associateBy(MapBlock::blockId)
    val cellsByIndex: Map<Triple<Long, Int, Int>, MapCell>
        get() = cells.associateBy { Triple(it.blockId, it.row, it.col) }
}

data class MapSource(
    val type: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("generated_at") val generatedAt: String? = null
)

data class MapFrame(val unit: String, val origin: MapOrigin? = null)

data class MapOrigin(
    @SerializedName("latitude_deg") val latitudeDeg: Double? = null,
    @SerializedName("longitude_deg") val longitudeDeg: Double? = null,
    @SerializedName("yaw_deg") val yawDeg: Double? = null
)

data class CellModel(
    @SerializedName("inner_rows") val innerRows: Int,
    @SerializedName("inner_cols") val innerCols: Int
)

data class BlockFrame(
    @SerializedName("block_origin") val blockOrigin: List<Double>,
    @SerializedName("u_axis") val uAxis: List<Double>,
    @SerializedName("v_axis") val vAxis: List<Double>
)

data class MapBlock(
    @SerializedName("block_id") val blockId: Long,
    @SerializedName("block_frame") val blockFrame: BlockFrame,
    val rows: Int,
    val cols: Int,
    val grid: List<List<Int>>,
    @SerializedName("cell_ids") val cellIds: List<Long>,
    val cleanable: Boolean = true
)

data class MapCell(
    @SerializedName("cell_id") val cellId: Long,
    @SerializedName("block_id") val blockId: Long,
    val row: Int,
    val col: Int,
    val polygon: List<List<Double>>
) {
    fun points() = polygon.map { Point2D(it[0], it[1]) }
}

data class BridgeEndpoint(
    @SerializedName("block_id") val blockId: Long,
    @SerializedName("cell_row") val cellRow: Int,
    @SerializedName("cell_col") val cellCol: Int,
    val edge: String,
    @SerializedName("inner_row") val innerRow: Int,
    @SerializedName("inner_col") val innerCol: Int
)

data class MapBridge(
    @SerializedName("bridge_id") val bridgeId: Long,
    val source: String? = null,
    val endpoints: List<BridgeEndpoint>,
    val centerline: List<List<Double>> = emptyList(),
    val polygon: List<List<Double>> = emptyList()
) {
    fun centerlinePoints() = centerline.map { Point2D(it[0], it[1]) }
    fun polygonPoints() = polygon.map { Point2D(it[0], it[1]) }
}

data class MapPosition(val point: Point2D, val headingDegrees: Double)
