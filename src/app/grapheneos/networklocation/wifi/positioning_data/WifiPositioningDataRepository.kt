package app.grapheneos.networklocation.wifi.positioning_data

import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.WifiPositioningDataServerDataSource
import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.isNull
import kotlin.math.pow

class WifiPositioningDataRepository(
    private val wifiPositioningDataServerDataSource: WifiPositioningDataServerDataSource,
) {
    suspend fun fetchPositioningData(accessPointBssids: List<String>): List<AccessPoint>? {
        val positioningData =
            wifiPositioningDataServerDataSource.fetchPositioningData(
                accessPointBssids
            )?.accessPointsList

        val convertedPositioningData =
            positioningData?.map {
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

        return convertedPositioningData
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