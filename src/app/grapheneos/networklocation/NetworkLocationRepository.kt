package app.grapheneos.networklocation

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.os.WorkSource
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.NearbyWifiAccessPointsPositioningDataRepository
import kotlin.math.pow
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * NetworkLocationRepository combines multiple network location sources to achieve the best network
 * location fix.
 */
class NetworkLocationRepository(
    private val nearbyWifiAccessPointsPositioningDataRepository: NearbyWifiAccessPointsPositioningDataRepository
) {
    val latestLocation: Flow<Location?> =
        nearbyWifiAccessPointsPositioningDataRepository.latestNearbyWifiAccessPointsPositioningData.map { nearbyWifiAccessPointsPositioningData ->
            var location: Location? = null
            val firstNearbyWifiAccessPoint = nearbyWifiAccessPointsPositioningData.getOrNull(0)
            if (firstNearbyWifiAccessPoint?.positioningData != null) {
                location = Location(LocationManager.NETWORK_PROVIDER)
                location.elapsedRealtimeNanos =
                    firstNearbyWifiAccessPoint.positioningData.lastSeen.microseconds.inWholeNanoseconds
                location.time =
                    (System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos().nanoseconds.inWholeMilliseconds) + location.elapsedRealtimeNanos.nanoseconds.inWholeMilliseconds
                location.longitude = firstNearbyWifiAccessPoint.positioningData.longitude
                location.latitude = firstNearbyWifiAccessPoint.positioningData.latitude
                location.accuracy = run {
                    // estimate distance in meters from access point using the Log-Distance Path Loss Model
                    val distanceFromAccessPoint = run {
                        val rssi = firstNearbyWifiAccessPoint.positioningData.rssi
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
                    (firstNearbyWifiAccessPoint.positioningData.accuracyInMeters * 0.68).toFloat() + distanceFromAccessPoint
                }
            }
            location
        }

    fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long) {
        nearbyWifiAccessPointsPositioningDataRepository.setUpdateTarget(
            updateTargetElapsedRealtimeNanos
        )
    }

    fun setWorkSource(workSource: WorkSource) {
        nearbyWifiAccessPointsPositioningDataRepository.setWorkSource(workSource)
    }
}