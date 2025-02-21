package app.grapheneos.networklocation.wifi.nearby

import android.os.WorkSource
import app.grapheneos.networklocation.misc.RustyResult
import app.grapheneos.networklocation.wifi.nearby.data_sources.local.NearbyWifiApi
import app.grapheneos.networklocation.wifi.nearby.data_sources.local.NearbyWifiLocalDataSource
import kotlinx.coroutines.flow.map

class NearbyWifiRepository(
    private val nearbyWifiLocalDataSource: NearbyWifiLocalDataSource
) {
    sealed class LatestNearbyWifiError {
        data object Failure : LatestNearbyWifiError()
        data object Unavailable : LatestNearbyWifiError()
    }

    val latestNearbyWifi = nearbyWifiLocalDataSource.latestNearbyWifi.map { scanResults ->
        when (scanResults) {
            is RustyResult.Err -> when (scanResults.error) {
                NearbyWifiApi.LatestNearbyWifiError.Failure -> RustyResult.Err(
                    LatestNearbyWifiError.Failure
                )

                NearbyWifiApi.LatestNearbyWifiError.Unavailable -> RustyResult.Err(
                    LatestNearbyWifiError.Unavailable
                )
            }

            is RustyResult.Ok -> RustyResult.Ok(scanResults.value.filter {
                val ssid = it.wifiSsid

                if (ssid != null) {
                    !ssid.bytes.decodeToString().endsWith("_nomap")
                } else {
                    true
                }
            })
        }
    }

    fun setWorkSource(workSource: WorkSource) =
        nearbyWifiLocalDataSource.setWorkSource(workSource)
}