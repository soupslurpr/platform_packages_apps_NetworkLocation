package app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points.data_sources.local

import android.net.wifi.ScanResult
import android.os.WorkSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class NearbyWifiAccessPointsLocalDataSource(
    private val nearbyWifiAccessPointsApi: NearbyWifiAccessPointsApi,
    private val ioDispatcher: CoroutineDispatcher
) {
    val latestNearbyWifiAccessPoints: Flow<List<ScanResult>> = flow {
        while (true) {
            val nearbyWifiAccessPoints =
                nearbyWifiAccessPointsApi.fetchFreshestNearbyWifiAccessPoints()
            if (nearbyWifiAccessPoints != null) {
                emit(nearbyWifiAccessPoints)
            }
        }
    }.flowOn(ioDispatcher)

    fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long) =
        nearbyWifiAccessPointsApi.setUpdateTarget(updateTargetElapsedRealtimeNanos)

    fun setWorkSource(workSource: WorkSource) = nearbyWifiAccessPointsApi.setWorkSource(workSource)
}

interface NearbyWifiAccessPointsApi {
    /**
     * Fetch the freshest (to the update target time) nearby Wi-Fi access points.
     */
    suspend fun fetchFreshestNearbyWifiAccessPoints(): MutableList<ScanResult>?

    /**
     * Target time to update the flow.
     */
    fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long)

    fun setWorkSource(workSource: WorkSource)
}