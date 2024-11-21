package app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data

import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data.data_sources.server.WifiAccessPointsPositioningDataServerDataSource
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data.data_sources.server.isNull
import kotlin.math.pow

class WifiAccessPointsPositioningDataRepository(
    private val wifiAccessPointsPositioningDataServerDataSource: WifiAccessPointsPositioningDataServerDataSource,
) {
    suspend fun fetchWifiAccessPointsPositioningData(wifiAccessPointsBssid: List<String>): List<WifiAccessPoint>? {
        val wifiAccessPointsPositioningDataServerDataSource =
            wifiAccessPointsPositioningDataServerDataSource.fetchWifiAccessPointsPositioningData(
                wifiAccessPointsBssid
            )?.accessPointsList

        val convertedWifiAccessPointsPositioningDataServerDataSource =
            wifiAccessPointsPositioningDataServerDataSource?.map {
                WifiAccessPoint(
                    bssid = it.bssid,
                    positioningData = if (!it.positioningData.isNull()) {
                        it.positioningData.let { serverApiPositioningData ->
                            WifiAccessPointPositioningData(
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

        return convertedWifiAccessPointsPositioningDataServerDataSource
    }
}

data class WifiAccessPoint(
    val bssid: String,
    val positioningData: WifiAccessPointPositioningData?
)

data class WifiAccessPointPositioningData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Long,
    val altitudeMeters: Long?,
    val verticalAccuracyMeters: Long?
)