package app.grapheneos.networklocation.wifi.nearby_positioning_data

import android.net.wifi.ScanResult
import android.os.WorkSource
import android.util.Log
import app.grapheneos.networklocation.wifi.nearby.NearbyWifiRepository
import app.grapheneos.networklocation.wifi.positioning_data.WifiPositioningDataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NearbyWifiPositioningDataRepository(
    private val nearbyWifiRepository: NearbyWifiRepository,
    private val wifiPositioningDataRepository: WifiPositioningDataRepository
) {
    private val latestNearbyWifiCacheMutex = Mutex()

    /** Cache that only keeps BSSIDs that are in the latest scan to prevent storing a location history. */
    private val latestNearbyWifiCache: MutableList<NearbyWifi> =
        mutableListOf()

    /** Flow that emits nearby Wi-Fi access points with positioning data. */
    val latestNearbyWifisWithPositioningData: Flow<List<NearbyWifi>> =
        nearbyWifiRepository.latestAccessPoints
            .map { scanResults: List<ScanResult> ->
                if (scanResults.isEmpty()) {
                    latestNearbyWifiCacheMutex.withLock {
                        latestNearbyWifiCache.clear()
                    }
                    return@map listOf()
                }

                // only keep cached BSSIDs that are in the latest scan to prevent storing a location history
                latestNearbyWifiCacheMutex.withLock {
                    latestNearbyWifiCache.retainAll { cachedAccessPoint ->
                        scanResults.any { result ->
                            result.BSSID == cachedAccessPoint.bssid
                        }
                    }
                }

                val sortedByLevelScanResults = scanResults.sortedByDescending { it.level }
                val bestNearbyWifis: MutableList<NearbyWifi> =
                    mutableListOf()
                for (scanResult in sortedByLevelScanResults) {
                    val cachedNearbyWifi =
                        latestNearbyWifiCacheMutex.withLock {
                            latestNearbyWifiCache.firstOrNull { cacheEntry ->
                                scanResult.BSSID == cacheEntry.bssid
                            }
                        }
                    if (cachedNearbyWifi != null) {
                        if (cachedNearbyWifi.positioningData != null) {
                            this.latestNearbyWifiCacheMutex.withLock {
                                latestNearbyWifiCache.remove(
                                    cachedNearbyWifi
                                )
                                val updatedCachedNearbyWifiAccessPoint =
                                    cachedNearbyWifi.copy(
                                        positioningData = cachedNearbyWifi.positioningData.copy(
                                            rssi = scanResult.level,
                                            lastSeen = scanResult.timestamp
                                        )
                                    )
                                latestNearbyWifiCache.add(
                                    updatedCachedNearbyWifiAccessPoint
                                )
                                bestNearbyWifis.add(updatedCachedNearbyWifiAccessPoint)
                            }
                            break
                        } else {
                            // if we know this access point doesn't have any positioning data, don't
                            // bother trying to request it.
                            continue
                        }
                    }

                    Log.v(
                        "NearbyWifiPositioningDataRepository",
                        "requested positioning data for unknown access point: ${scanResult.BSSID}"
                    )

                    val wifiPositioningData =
                        wifiPositioningDataRepository.fetchPositioningData(
                            listOf(scanResult.BSSID)
                        )

                    if (!wifiPositioningData.isNullOrEmpty()) {
                        val firstWifi = wifiPositioningData[0]
                        val firstWifiPositioningData =
                            firstWifi.positioningData
                        val nearbyWifi =
                            NearbyWifi(
                                bssid = firstWifi.bssid,
                                positioningData = if (firstWifiPositioningData == null) {
                                    null
                                } else {
                                    NearbyWifi.PositioningData(
                                        latitude = firstWifiPositioningData.latitude,
                                        longitude = firstWifiPositioningData.longitude,
                                        accuracyMeters = firstWifiPositioningData.accuracyMeters,
                                        rssi = scanResult.level,
                                        altitudeMeters = firstWifiPositioningData.altitudeMeters,
                                        verticalAccuracyMeters = firstWifiPositioningData.verticalAccuracyMeters,
                                        lastSeen = scanResult.timestamp
                                    )
                                }
                            )
                        latestNearbyWifiCacheMutex.withLock {
                            this.latestNearbyWifiCache.add(
                                nearbyWifi
                            )
                        }
                        if (firstWifiPositioningData != null) {
                            bestNearbyWifis.add(nearbyWifi)
                            break
                        }
                    }
                }

                bestNearbyWifis
            }

    fun setWorkSource(workSource: WorkSource) {
        nearbyWifiRepository.setWorkSource(workSource)
    }

    suspend fun clearCaches() {
        latestNearbyWifiCacheMutex.withLock {
            latestNearbyWifiCache.clear()
        }
    }
}

data class NearbyWifi(
    val bssid: String,
    val positioningData: PositioningData?
) {
    data class PositioningData(
        val latitude: Double,
        val longitude: Double,
        var accuracyMeters: Long,
        val rssi: Int,
        val altitudeMeters: Long?,
        val verticalAccuracyMeters: Long?,
        /** timestamp in microseconds (since boot) when this result was last seen. */
        val lastSeen: Long
    )
}