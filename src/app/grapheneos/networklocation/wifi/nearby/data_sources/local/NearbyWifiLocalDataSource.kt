package app.grapheneos.networklocation.wifi.nearby.data_sources.local

import android.net.wifi.ScanResult
import android.os.WorkSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class NearbyWifiLocalDataSource(
    private val nearbyWifiApi: NearbyWifiApi,
    private val ioDispatcher: CoroutineDispatcher
) {
    val latestAccessPoints: Flow<List<ScanResult>> = flow {
        while (true) {
            val nearbyWifi =
                nearbyWifiApi.fetchFreshestNearbyWifi()
            if (nearbyWifi != null) {
                emit(nearbyWifi)
            }
        }
    }.flowOn(ioDispatcher)

    fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long) =
        nearbyWifiApi.setUpdateTarget(updateTargetElapsedRealtimeNanos)

    fun setWorkSource(workSource: WorkSource) = nearbyWifiApi.setWorkSource(workSource)
}

interface NearbyWifiApi {
    /**
     * Fetch the freshest (to the update target time) nearby Wi-Fi access points.
     */
    suspend fun fetchFreshestNearbyWifi(): MutableList<ScanResult>?

    /**
     * Target time to update the flow.
     */
    fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long)

    fun setWorkSource(workSource: WorkSource)
}