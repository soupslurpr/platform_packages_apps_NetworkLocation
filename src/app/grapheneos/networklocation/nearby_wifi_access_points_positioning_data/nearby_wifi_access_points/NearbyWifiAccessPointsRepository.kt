package app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points

import android.net.wifi.ScanResult
import android.os.WorkSource
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points.data_sources.local.NearbyWifiAccessPointsLocalDataSource
import kotlinx.coroutines.flow.Flow

class NearbyWifiAccessPointsRepository(
    private val nearbyWifiAccessPointsLocalDataSource: NearbyWifiAccessPointsLocalDataSource
) {
    val latestNearbyWifiAccessPoints: Flow<List<ScanResult>> =
        nearbyWifiAccessPointsLocalDataSource.latestNearbyWifiAccessPoints

    fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long) =
        nearbyWifiAccessPointsLocalDataSource.setUpdateTarget(updateTargetElapsedRealtimeNanos)

    fun setWorkSource(workSource: WorkSource) =
        nearbyWifiAccessPointsLocalDataSource.setWorkSource(workSource)
}