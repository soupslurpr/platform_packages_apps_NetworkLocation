package app.grapheneos.networklocation.wifi.positioning_data.data_sources.server

import app.grapheneos.networklocation.misc.RustyResult
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
    sealed class FetchPositioningDataError {
        data object Failure : FetchPositioningDataError()
        data object Unavailable : FetchPositioningDataError()
    }

    fun fetchPositioningData(wifiAccessPointsBssid: List<String>): RustyResult<AppleWps.WifiPositioningDataApiModel, FetchPositioningDataError>
}

fun AppleWps.PositioningData.isNull(): Boolean = this.latitude == -18000000000