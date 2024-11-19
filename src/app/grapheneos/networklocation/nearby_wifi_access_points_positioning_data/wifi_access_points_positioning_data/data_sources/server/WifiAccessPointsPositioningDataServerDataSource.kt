package app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data.data_sources.server

import app.grapheneos.networklocation.wifi_access_points_positioning_data.data_sources.server.AppleWps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class WifiAccessPointsPositioningDataServerDataSource(
    private val wifiAccessPointsPositioningDataApi: WifiAccessPointsPositioningDataApi,
    private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun fetchWifiAccessPointsPositioningData(wifiAccessPointsBssid: List<String>) =
        withContext(ioDispatcher) {
            wifiAccessPointsPositioningDataApi.fetchWifiAccessPointsPositioningData(
                wifiAccessPointsBssid
            )
        }
}

interface WifiAccessPointsPositioningDataApi {
    suspend fun fetchWifiAccessPointsPositioningData(wifiAccessPointsBssid: List<String>): AppleWps.AppleWifiAccessPointPositioningDataApiModel?
}

fun AppleWps.PositioningData.isNull(): Boolean = this.latitude == -18000000000