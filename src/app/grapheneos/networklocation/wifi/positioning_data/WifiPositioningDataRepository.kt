package app.grapheneos.networklocation.wifi.positioning_data

import app.grapheneos.networklocation.misc.RustyResult
import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.WifiPositioningDataApi
import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.WifiPositioningDataServerDataSource
import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.isNull
import kotlin.math.pow

class WifiPositioningDataRepository(
    private val wifiPositioningDataServerDataSource: WifiPositioningDataServerDataSource,
) {
    sealed class FetchPositioningDataError {
        data object Failure : FetchPositioningDataError()
        data object Unavailable : FetchPositioningDataError()
    }

    suspend fun fetchPositioningData(accessPointBssids: List<String>): RustyResult<List<AccessPoint>, FetchPositioningDataError> {
        val positioningData =
            wifiPositioningDataServerDataSource.fetchPositioningData(accessPointBssids)

        when (positioningData) {
            is RustyResult.Err -> {
                return when (positioningData.error) {
                    WifiPositioningDataApi.FetchPositioningDataError.Failure -> RustyResult.Err(
                        FetchPositioningDataError.Failure
                    )

                    WifiPositioningDataApi.FetchPositioningDataError.Unavailable -> RustyResult.Err(
                        FetchPositioningDataError.Unavailable
                    )
                }
            }

            is RustyResult.Ok -> {
                val convertedPositioningData =
                    positioningData.value.accessPointsList.map {
                        AccessPoint(
                            bssid = it.bssid,
                            positioningData = if (!it.positioningData.isNull()) {
                                it.positioningData.let { serverApiPositioningData ->
                                    WifiPositioningData(
                                        serverApiPositioningData.latitude * 10.toDouble().pow(-8),
                                        serverApiPositioningData.longitude * 10.toDouble().pow(-8),
                                        serverApiPositioningData.accuracyMeters,
                                        serverApiPositioningData.altitudeMeters.let { altitudeMeters ->
                                            // the api returns -1 or -500 for unknown altitude
                                            if ((altitudeMeters == -1L) || (altitudeMeters == -500L)) {
                                                null
                                            } else {
                                                altitudeMeters
                                            }
                                        },
                                        serverApiPositioningData.verticalAccuracyMeters.let { verticalAccuracyMeters ->
                                            // the api returns -1 for unknown vertical accuracy (altitude accuracy)
                                            if (verticalAccuracyMeters == -1L) {
                                                null
                                            } else {
                                                verticalAccuracyMeters
                                            }
                                        }
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }

                return RustyResult.Ok(convertedPositioningData)
            }
        }
    }
}

data class AccessPoint(
    val bssid: String,
    val positioningData: WifiPositioningData?
)

data class WifiPositioningData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Long,
    val altitudeMeters: Long?,
    val verticalAccuracyMeters: Long?
)