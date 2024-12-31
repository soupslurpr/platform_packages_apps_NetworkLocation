package app.grapheneos.networklocation.wifi.nearby.data_sources.local

import android.net.wifi.ScanResult
import android.os.WorkSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class NearbyWifiLocalDataSource(
    private val nearbyWifiApi: NearbyWifiApi,
    ioDispatcher: CoroutineDispatcher
) {
    val latestAccessPoints: Flow<List<ScanResult>> = flow {
        while (true) {
            val nearbyWifi =
                nearbyWifiApi.fetchNearbyWifi()
            if (nearbyWifi != null) {
                emit(nearbyWifi)
            }
        }
    }.flowOn(ioDispatcher)

    fun setWorkSource(workSource: WorkSource) = nearbyWifiApi.setWorkSource(workSource)
}

interface NearbyWifiApi {
    /**
     * Fetch the nearby Wi-Fi access points.
     */
    suspend fun fetchNearbyWifi(): MutableList<ScanResult>?

    fun setWorkSource(workSource: WorkSource)
}