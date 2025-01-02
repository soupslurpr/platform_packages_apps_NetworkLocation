package app.grapheneos.networklocation.wifi.nearby_positioning_data

import android.net.wifi.ScanResult
import android.os.SystemClock
import android.os.WorkSource
import android.util.Log
import app.grapheneos.networklocation.wifi.nearby.NearbyWifiRepository
import app.grapheneos.networklocation.wifi.positioning_data.WifiPositioningDataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

class NearbyWifiPositioningDataRepository(
    private val nearbyWifiRepository: NearbyWifiRepository,
    private val wifiPositioningDataRepository: WifiPositioningDataRepository
) {
    private val latestNearbyWifiCacheMutex = Mutex()

    /**
     * In-memory cache that stores nearby Wi-Fi access points. It's checked after every scan to
     * ensure that it only stores BSSIDs seen in the last 5 minutes, preventing the storage of a
     * long location history.
     */
    private val latestNearbyWifiCache: MutableList<NearbyWifi> =
        mutableListOf()

    /** Flow that emits nearby Wi-Fi access points with positioning data. */
    val latestNearbyWifisWithPositioningData: Flow<List<NearbyWifi>> =
        nearbyWifiRepository.latestAccessPoints
            .map { scanResults: List<ScanResult> ->
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
                        Log.v(
                            TAG,
                            "found access point in cache: $cachedNearbyWifi"
                        )

                        val updatedCachedNearbyWifi =
                            cachedNearbyWifi.copy(
                                positioningData = cachedNearbyWifi.positioningData?.copy(
                                    rssi = scanResult.level
                                ),
                                lastSeen = scanResult.timestamp
                            )
                        latestNearbyWifiCacheMutex.withLock {
                            latestNearbyWifiCache.remove(
                                cachedNearbyWifi
                            )
                            latestNearbyWifiCache.add(
                                updatedCachedNearbyWifi
                            )
                        }
                        if (updatedCachedNearbyWifi.positioningData != null) {
                            // only add the closest one for now
                            if (bestNearbyWifis.isEmpty()) {
                                bestNearbyWifis.add(updatedCachedNearbyWifi)
                            }
                        } else {
                            // if we know that this access point doesn't have any positioning data,
                            // don't bother trying to request it again.
                            continue
                        }
                    }

                    if (bestNearbyWifis.isNotEmpty()) {
                        continue
                    }

                    Log.v(
                        TAG,
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
                                        verticalAccuracyMeters = firstWifiPositioningData.verticalAccuracyMeters
                                    )
                                },
                                lastSeen = scanResult.timestamp
                            )
                        latestNearbyWifiCacheMutex.withLock {
                            this.latestNearbyWifiCache.add(
                                nearbyWifi
                            )
                        }
                        if (firstWifiPositioningData != null) {
                            bestNearbyWifis.add(nearbyWifi)
                        }
                    }
                }

                // check cache for entries that were last seen more than 5 minutes ago and remove
                // them.
                latestNearbyWifiCacheMutex.withLock {
                    latestNearbyWifiCache.removeIf {
                        val delta =
                            SystemClock.elapsedRealtimeNanos().nanoseconds - it.lastSeen.microseconds

                        delta > 5.minutes
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

    companion object {
        private const val TAG: String = "NearbyWifiPositioningDataRepository"
    }
}

data class NearbyWifi(
    val bssid: String,
    val positioningData: PositioningData?,
    /** timestamp in microseconds (since boot) when this result was last seen. */
    val lastSeen: Long
) {
    data class PositioningData(
        val latitude: Double,
        val longitude: Double,
        var accuracyMeters: Long,
        val rssi: Int,
        val altitudeMeters: Long?,
        val verticalAccuracyMeters: Long?
    )
}