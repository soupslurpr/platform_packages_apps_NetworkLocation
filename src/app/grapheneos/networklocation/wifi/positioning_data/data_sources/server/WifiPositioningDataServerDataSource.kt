package app.grapheneos.networklocation.wifi.positioning_data.data_sources.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class WifiPositioningDataServerDataSource(
    private val wifiPositioningDataApi: WifiPositioningDataApi,
    private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun fetchPositioningData(accessPointBssids: List<String>) =
        withContext(ioDispatcher) {
            wifiPositioningDataApi.fetchPositioningData(
                accessPointBssids
            )
        }
}

interface WifiPositioningDataApi {
    fun fetchPositioningData(wifiAccessPointsBssid: List<String>): AppleWps.WifiPositioningDataApiModel?
}

fun AppleWps.PositioningData.isNull(): Boolean = this.latitude == -18000000000