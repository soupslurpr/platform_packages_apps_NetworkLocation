package app.grapheneos.networklocation.wifi.nearby

import android.net.wifi.ScanResult
import android.os.WorkSource
import app.grapheneos.networklocation.wifi.nearby.data_sources.local.NearbyWifiLocalDataSource
import kotlinx.coroutines.flow.Flow

class NearbyWifiRepository(
    private val nearbyWifiLocalDataSource: NearbyWifiLocalDataSource
) {
    val latestAccessPoints: Flow<List<ScanResult>> =
        nearbyWifiLocalDataSource.latestAccessPoints

    fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long) =
        nearbyWifiLocalDataSource.setUpdateTarget(updateTargetElapsedRealtimeNanos)

    fun setWorkSource(workSource: WorkSource) =
        nearbyWifiLocalDataSource.setWorkSource(workSource)
}