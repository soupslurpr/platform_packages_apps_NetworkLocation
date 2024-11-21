package app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data

import android.net.wifi.ScanResult
import android.os.WorkSource
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points.NearbyWifiAccessPointsRepository
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data.WifiAccessPointsPositioningDataRepository
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NearbyWifiAccessPointsPositioningDataRepository(
    private val nearbyWifiAccessPointsRepository: NearbyWifiAccessPointsRepository,
    private val wifiAccessPointsPositioningDataRepository: WifiAccessPointsPositioningDataRepository
) {
    private val latestNearbyWifiAccessPointsPositioningDataCacheMutex = Mutex()

    /** Cache that only keeps BSSIDs that are in the latest scan to prevent storing a location history. */
    private val latestNearbyWifiAccessPointsPositioningDataCache: MutableList<NearbyWifiAccessPoint> =
        mutableListOf()

    /** Flow that emits nearby Wi-Fi access points positioning data according to the update target. */
    val latestNearbyWifiAccessPointsPositioningData: Flow<List<NearbyWifiAccessPoint>> =
        nearbyWifiAccessPointsRepository.latestNearbyWifiAccessPoints
            .map { scanResults: List<ScanResult> ->
                if (scanResults.isEmpty()) {
                    latestNearbyWifiAccessPointsPositioningDataCacheMutex.withLock {
                        latestNearbyWifiAccessPointsPositioningDataCache.clear()
                    }
                    return@map listOf()
                }

                // only keep cached BSSIDs that are in the latest scan to prevent storing a location history
                latestNearbyWifiAccessPointsPositioningDataCacheMutex.withLock {
                    latestNearbyWifiAccessPointsPositioningDataCache.retainAll { cachedAccessPoint ->
                        scanResults.any { result ->
                            result.BSSID == cachedAccessPoint.bssid
                        }
                    }
                }

                val sortedByLevelScanResults = scanResults.sortedByDescending { it.level }
                val bestNearbyWifiAccessPoints: MutableList<NearbyWifiAccessPoint> =
                    mutableListOf()
                for (scanResult in sortedByLevelScanResults) {
                    val cachedNearbyWifiAccessPoint =
                        latestNearbyWifiAccessPointsPositioningDataCacheMutex.withLock {
                            latestNearbyWifiAccessPointsPositioningDataCache.firstOrNull { cacheEntry ->
                                scanResult.BSSID == cacheEntry.bssid
                            }
                        }
                    if (cachedNearbyWifiAccessPoint != null) {
                        if (cachedNearbyWifiAccessPoint.positioningData != null) {
                            this.latestNearbyWifiAccessPointsPositioningDataCacheMutex.withLock {
                                latestNearbyWifiAccessPointsPositioningDataCache.remove(
                                    cachedNearbyWifiAccessPoint
                                )
                                val updatedCachedNearbyWifiAccessPoint =
                                    cachedNearbyWifiAccessPoint.copy(
                                        positioningData = cachedNearbyWifiAccessPoint.positioningData.copy(
                                            rssi = scanResult.level,
                                            lastSeen = scanResult.timestamp
                                        )
                                    )
                                latestNearbyWifiAccessPointsPositioningDataCache.add(
                                    updatedCachedNearbyWifiAccessPoint
                                )
                                bestNearbyWifiAccessPoints.add(updatedCachedNearbyWifiAccessPoint)
                            }
                            break
                        } else {
                            // if we know this access point doesn't have any positioning data, don't
                            // bother trying to request it.
                            continue
                        }
                    }

                    val wifiAccessPoint =
                        wifiAccessPointsPositioningDataRepository.fetchWifiAccessPointsPositioningData(
                            listOf(scanResult.BSSID)
                        )

                    if (!wifiAccessPoint.isNullOrEmpty()) {
                        val firstWifiAccessPoint = wifiAccessPoint[0]
                        val firstWifiAccessPointPositioningData =
                            firstWifiAccessPoint.positioningData
                        val nearbyWifiAccessPoint =
                            NearbyWifiAccessPoint(
                                bssid = firstWifiAccessPoint.bssid,
                                positioningData = if (firstWifiAccessPointPositioningData == null) {
                                    null
                                } else {
                                    NearbyWifiAccessPointPositioningData(
                                        latitude = firstWifiAccessPointPositioningData.latitude,
                                        longitude = firstWifiAccessPointPositioningData.longitude,
                                        accuracyMeters = firstWifiAccessPointPositioningData.accuracyMeters,
                                        rssi = scanResult.level,
                                        lastSeen = scanResult.timestamp
                                    )
                                }
                            )
                        latestNearbyWifiAccessPointsPositioningDataCacheMutex.withLock {
                            this.latestNearbyWifiAccessPointsPositioningDataCache.add(
                                nearbyWifiAccessPoint
                            )
                        }
                        if (firstWifiAccessPointPositioningData != null) {
                            bestNearbyWifiAccessPoints.add(nearbyWifiAccessPoint)
                            break
                        }
                    }
                }

                bestNearbyWifiAccessPoints
            }

    fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long) {
        val timeAllocatedToFetchingPositioningData =
            150.milliseconds.inWholeNanoseconds
        val nearbyWifiAccessPointsUpdateTarget =
            updateTargetElapsedRealtimeNanos - timeAllocatedToFetchingPositioningData
        nearbyWifiAccessPointsRepository.setUpdateTarget(nearbyWifiAccessPointsUpdateTarget)
    }

    fun setWorkSource(workSource: WorkSource) {
        nearbyWifiAccessPointsRepository.setWorkSource(workSource)
    }

    suspend fun clearCaches() {
        latestNearbyWifiAccessPointsPositioningDataCacheMutex.withLock {
            latestNearbyWifiAccessPointsPositioningDataCache.clear()
        }
    }
}

data class NearbyWifiAccessPoint(
    val bssid: String,
    val positioningData: NearbyWifiAccessPointPositioningData?
)

data class NearbyWifiAccessPointPositioningData(
    val latitude: Double,
    val longitude: Double,
    var accuracyMeters: Double,
    val rssi: Int,
    /** timestamp in microseconds (since boot) when this result was last seen. */
    val lastSeen: Long
)