package app.grapheneos.networklocation.wifi.nearby.data_sources.local

import android.net.wifi.ScanResult
import android.os.WorkSource
import app.grapheneos.networklocation.misc.RustyResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class NearbyWifiLocalDataSource(
    private val nearbyWifiApi: NearbyWifiApi,
    ioDispatcher: CoroutineDispatcher
) {
    val latestNearbyWifi = flow {
        while (true) {
            val nearbyWifi =
                nearbyWifiApi.fetchNearbyWifi()
            emit(nearbyWifi)
        }
    }.flowOn(ioDispatcher)

    fun setWorkSource(workSource: WorkSource) = nearbyWifiApi.setWorkSource(workSource)
}

interface NearbyWifiApi {
    sealed class LatestNearbyWifiError {
        data object Failure : LatestNearbyWifiError()
        data object Unavailable : LatestNearbyWifiError()
    }

    /**
     * Fetch nearby Wi-Fi access points.
     */
    suspend fun fetchNearbyWifi(): RustyResult<List<ScanResult>, LatestNearbyWifiError>

    fun setWorkSource(workSource: WorkSource)
}