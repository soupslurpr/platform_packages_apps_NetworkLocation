package app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points.data_sources.local

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiScanner
import android.net.wifi.WifiScanner.ScanListener
import android.os.SystemClock
import android.os.WorkSource
import android.util.Log
import kotlin.coroutines.resume
import kotlin.properties.Delegates
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

class NearbyWifiAccessPointsApiImpl(
    private val wifiScanner: WifiScanner,
    private val wifiManager: WifiManager
) : NearbyWifiAccessPointsApi {
    private var updateTargetElapsedRealtimeNanos by Delegates.notNull<Long>()
    private lateinit var workSource: WorkSource

    override suspend fun fetchFreshestNearbyWifiAccessPoints(): MutableList<ScanResult>? {
        if (!wifiManager.isWifiScannerSupported && !(wifiManager.isWifiEnabled || wifiManager.isScanAlwaysAvailable)) {
            return null
        }

        // TODO: can use more time-consuming settings if we have enough time
        val scanSettings = WifiScanner.ScanSettings()
        scanSettings.band = WifiScanner.WIFI_BAND_5_GHZ
        scanSettings.type = WifiScanner.SCAN_TYPE_LOW_LATENCY
        scanSettings.rnrSetting = WifiScanner.WIFI_RNR_NOT_NEEDED
        // estimated scanning duration for 5 GHz only
        val estimatedScanningDuration = 1000.milliseconds
        delay(
            (updateTargetElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()).nanoseconds - estimatedScanningDuration
        )

        return suspendCancellableCoroutine { continuation ->
            val scanListener = object : ScanListener {
                override fun onSuccess() {
                    Log.d(TAG, "onSuccess: ")
                }

                override fun onFailure(reason: Int, description: String?) {
                    Log.d(TAG, "onFailure: reason: $reason, description: $description")
                    continuation.resume(null)
                }

                @Deprecated("Deprecated in Java")
                override fun onPeriodChanged(periodInMs: Int) {
                    /* no-op */
                }

                override fun onResults(results: Array<out WifiScanner.ScanData>?) {
                    // For single scans, the array size should always be 1.
                    if (results?.size != 1) {
                        Log.wtf(TAG, "Found more than 1 batch of scan results, ignoring...")
                        continuation.resume(null)
                        return
                    }
                    val scannedAccessPoints = results[0].results.toMutableList()
                    continuation.resume(scannedAccessPoints)
                }

                override fun onFullResult(fullScanResult: ScanResult?) {
                    /* no-op */
                }
            }

            wifiScanner.startScan(scanSettings, scanListener, workSource)

            continuation.invokeOnCancellation { wifiScanner.stopScan(scanListener) }
        }
    }

    override fun setUpdateTarget(updateTargetElapsedRealtimeNanos: Long) {
        this@NearbyWifiAccessPointsApiImpl.updateTargetElapsedRealtimeNanos =
            updateTargetElapsedRealtimeNanos
    }

    override fun setWorkSource(workSource: WorkSource) {
        this@NearbyWifiAccessPointsApiImpl.workSource = workSource
    }

    companion object {
        private const val TAG = "NearbyWifiAccessPointsApiImpl"
    }
}