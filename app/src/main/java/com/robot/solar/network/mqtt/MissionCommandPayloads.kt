package com.robot.solar.network.mqtt

internal object MissionCommandPayloads {
    private const val UINT32_MAX = 4_294_967_295L

    fun coverageStart(
        mapId: Long,
        mapVersion: Int,
        cleanableBlockIds: List<Long>
    ): Map<String, Any?>? {
        if (mapId !in 0..UINT32_MAX || mapVersion.toLong() !in 0..UINT32_MAX) return null
        val targetBlockIds = cleanableBlockIds
            .asSequence()
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toList()
        if (targetBlockIds.isEmpty()) return null
        return mapOf(
            "taskKind" to "coverage",
            "coverage" to mapOf(
                "mapId" to mapId,
                "mapVersion" to mapVersion,
                "useCurrentPose" to true,
                "targetBlockIds" to targetBlockIds,
                "globalPlan" to true
            )
        )
    }

    fun targetMission(missionId: String?): Map<String, Any?>? = missionId
        ?.takeIf { it.isNotBlank() }
        ?.let { mapOf("targetMissionId" to it) }
}
