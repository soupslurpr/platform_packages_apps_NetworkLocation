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
    private val latestNearbyWifiPositioningDataCacheMutex = Mutex()

    /** Cache that only keeps BSSIDs that are in the latest scan to prevent storing a location history. */
    private val latestNearbyWifiPositioningDataCache: MutableList<NearbyWifi> =
        mutableListOf()

    /** Flow that emits nearby Wi-Fi access points positioning data. */
    val latestPositioningData: Flow<List<NearbyWifi>> =
        nearbyWifiRepository.latestAccessPoints
            .map { scanResults: List<ScanResult> ->
                if (scanResults.isEmpty()) {
                    latestNearbyWifiPositioningDataCacheMutex.withLock {
                        latestNearbyWifiPositioningDataCache.clear()
                    }
                    return@map listOf()
                }

                // only keep cached BSSIDs that are in the latest scan to prevent storing a location history
                latestNearbyWifiPositioningDataCacheMutex.withLock {
                    latestNearbyWifiPositioningDataCache.retainAll { cachedAccessPoint ->
                        scanResults.any { result ->
                            result.BSSID == cachedAccessPoint.bssid
                        }
                    }
                }

                val sortedByLevelScanResults = scanResults.sortedByDescending { it.level }
                val bestNearbyWifis: MutableList<NearbyWifi> =
                    mutableListOf()
                for (scanResult in sortedByLevelScanResults) {
                    val cachedNearbyWifiAccessPoint =
                        latestNearbyWifiPositioningDataCacheMutex.withLock {
                            latestNearbyWifiPositioningDataCache.firstOrNull { cacheEntry ->
                                scanResult.BSSID == cacheEntry.bssid
                            }
                        }
                    if (cachedNearbyWifiAccessPoint != null) {
                        if (cachedNearbyWifiAccessPoint.positioningData != null) {
                            this.latestNearbyWifiPositioningDataCacheMutex.withLock {
                                latestNearbyWifiPositioningDataCache.remove(
                                    cachedNearbyWifiAccessPoint
                                )
                                val updatedCachedNearbyWifiAccessPoint =
                                    cachedNearbyWifiAccessPoint.copy(
                                        positioningData = cachedNearbyWifiAccessPoint.positioningData.copy(
                                            rssi = scanResult.level,
                                            lastSeen = scanResult.timestamp
                                        )
                                    )
                                latestNearbyWifiPositioningDataCache.add(
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

                    val wifiAccessPoint =
                        wifiPositioningDataRepository.fetchPositioningData(
                            listOf(scanResult.BSSID)
                        )

                    if (!wifiAccessPoint.isNullOrEmpty()) {
                        val firstWifiAccessPoint = wifiAccessPoint[0]
                        val firstWifiAccessPointPositioningData =
                            firstWifiAccessPoint.positioningData
                        val nearbyWifi =
                            NearbyWifi(
                                bssid = firstWifiAccessPoint.bssid,
                                positioningData = if (firstWifiAccessPointPositioningData == null) {
                                    null
                                } else {
                                    NearbyWifi.PositioningData(
                                        latitude = firstWifiAccessPointPositioningData.latitude,
                                        longitude = firstWifiAccessPointPositioningData.longitude,
                                        accuracyMeters = firstWifiAccessPointPositioningData.accuracyMeters,
                                        rssi = scanResult.level,
                                        altitudeMeters = firstWifiAccessPointPositioningData.altitudeMeters,
                                        verticalAccuracyMeters = firstWifiAccessPointPositioningData.verticalAccuracyMeters,
                                        lastSeen = scanResult.timestamp
                                    )
                                }
                            )
                        latestNearbyWifiPositioningDataCacheMutex.withLock {
                            this.latestNearbyWifiPositioningDataCache.add(
                                nearbyWifi
                            )
                        }
                        if (firstWifiAccessPointPositioningData != null) {
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
        latestNearbyWifiPositioningDataCacheMutex.withLock {
            latestNearbyWifiPositioningDataCache.clear()
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