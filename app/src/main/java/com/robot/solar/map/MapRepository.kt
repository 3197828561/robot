package com.robot.solar.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MapRepository(
    private val syncManager: MapSyncManager
) {
    private val _state = MutableStateFlow(MapRepositoryState())
    val state: StateFlow<MapRepositoryState> = _state.asStateFlow()

    suspend fun syncCurrentMap(
        productType: String,
        deviceId: String,
        force: Boolean = false
    ): MapSyncResult {
        _state.value = _state.value.copy(isSyncing = true, error = null)
        return try {
            val result = syncManager.sync(productType, deviceId, force)
            _state.value = MapRepositoryState(
                isSyncing = false,
                currentResult = result,
                error = null
            )
            result
        } catch (error: Throwable) {
            _state.value = _state.value.copy(isSyncing = false, error = error)
            throw error
        }
    }
}

data class MapRepositoryState(
    val isSyncing: Boolean = false,
    val currentResult: MapSyncResult? = null,
    val error: Throwable? = null
)
