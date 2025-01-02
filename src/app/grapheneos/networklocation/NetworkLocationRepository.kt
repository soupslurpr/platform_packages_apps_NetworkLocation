package app.grapheneos.networklocation

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.os.WorkSource
import app.grapheneos.networklocation.wifi.nearby_positioning_data.NearbyWifiPositioningDataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.pow
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * NetworkLocationRepository combines multiple network location sources to achieve the best network
 * location fix.
 */
class NetworkLocationRepository(
    private val nearbyWifiPositioningDataRepository: NearbyWifiPositioningDataRepository
) {
    val latestLocation: Flow<Location?> =
        nearbyWifiPositioningDataRepository.latestNearbyWifisWithPositioningData.map { nearbyWifiPositioningData ->
            var location: Location? = null
            val firstNearbyWifi = nearbyWifiPositioningData.getOrNull(0)
            if (firstNearbyWifi?.positioningData != null) {
                location = Location(LocationManager.NETWORK_PROVIDER)
                location.elapsedRealtimeNanos =
                    firstNearbyWifi.positioningData.lastSeen.microseconds.inWholeNanoseconds
                location.time =
                    (System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos().nanoseconds.inWholeMilliseconds) + location.elapsedRealtimeNanos.nanoseconds.inWholeMilliseconds
                location.longitude = firstNearbyWifi.positioningData.longitude
                location.latitude = firstNearbyWifi.positioningData.latitude
                location.accuracy = run {
                    // estimate distance in meters from access point using the Log-Distance Path Loss Model
                    val distanceFromAccessPoint = run {
                        val rssi = firstNearbyWifi.positioningData.rssi
                        // assume it's 30
                        val transmittedPower = 30f
                        val pathLoss = transmittedPower - rssi
                        val referenceDistance = 1f
                        // assume RSSI at reference distance is -30
                        val pathLossAtReferenceDistance = transmittedPower - (-30)
                        val pathLossExponent = 3f

                        referenceDistance * 10f.pow((pathLoss - pathLossAtReferenceDistance) / (10f * pathLossExponent))
                    }
                    // should be at the 68th percentile confidence level
                    (firstNearbyWifi.positioningData.accuracyMeters * 0.32f) + distanceFromAccessPoint
                }
                if (firstNearbyWifi.positioningData.altitudeMeters != null) {
                    location.altitude =
                        firstNearbyWifi.positioningData.altitudeMeters.toDouble()
                }
                if (firstNearbyWifi.positioningData.verticalAccuracyMeters != null) {
                    // should be at the 68th percentile confidence level
                    location.verticalAccuracyMeters =
                        firstNearbyWifi.positioningData.verticalAccuracyMeters * 0.32f
                }
            }
            location
        }

    fun setWorkSource(workSource: WorkSource) {
        nearbyWifiPositioningDataRepository.setWorkSource(workSource)
    }

    suspend fun clearCaches() {
        nearbyWifiPositioningDataRepository.clearCaches()
    }
}