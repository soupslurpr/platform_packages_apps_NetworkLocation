package app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points.data_sources.local

import android.net.wifi.ScanResult
import android.net.wifi.WifiScanner
import android.net.wifi.WifiScanner.ScanListener
import android.os.SystemClock
import android.os.WorkSource
import android.util.Log
import kotlin.properties.Delegates
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.delay

class NearbyWifiAccessPointsApiImpl(
    private val wifiScanner: WifiScanner
) : NearbyWifiAccessPointsApi {
    private var updateTargetElapsedRealtimeNanos by Delegates.notNull<Long>()
    private lateinit var workSource: WorkSource

    override suspend fun fetchFreshestNearbyWifiAccessPoints(): MutableList<ScanResult> {
        // TODO: can use more time-consuming settings if we have enough time
        val scanSettings = WifiScanner.ScanSettings()
        scanSettings.band = WifiScanner.WIFI_BAND_5_GHZ
        scanSettings.type = WifiScanner.SCAN_TYPE_LOW_LATENCY
        scanSettings.rnrSetting = WifiScanner.WIFI_RNR_NOT_NEEDED
        // estimated scanning duration for 5 GHz only
        val estimatedScanningDuration = 1000.toDuration(DurationUnit.MILLISECONDS)
        delay(
            (updateTargetElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()).toDuration(
                DurationUnit.NANOSECONDS
            ) - estimatedScanningDuration
        )
        var scanResults: MutableList<ScanResult>? = null
        var isScanning = true
        val scanListener = object : ScanListener {
            override fun onSuccess() {
                Log.d(TAG, "onSuccess: ")
            }

            override fun onFailure(reason: Int, description: String?) {
                Log.d(TAG, "onFailure: reason: $reason, description: $description")
                isScanning = false
            }

            @Deprecated("Deprecated in Java")
            override fun onPeriodChanged(periodInMs: Int) {
                /* no-op */
            }

            override fun onResults(results: Array<out WifiScanner.ScanData>?) {
                // For single scans, the array size should always be 1.
                if (results?.size != 1) {
                    Log.wtf(TAG, "Found more than 1 batch of scan results, Ignoring...")
                    return
                }
                val scannedAccessPoints = results[0].results.toMutableList()
                scanResults = scannedAccessPoints
                isScanning = false
            }

            override fun onFullResult(fullScanResult: ScanResult?) {
                /* no-op */
            }
        }
        wifiScanner.startScan(scanSettings, scanListener, workSource)
        while (isScanning) {
            delay(100.milliseconds)
        }
        return scanResults ?: mutableListOf()
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