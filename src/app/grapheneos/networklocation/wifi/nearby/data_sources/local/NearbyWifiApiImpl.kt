package app.grapheneos.networklocation.wifi.nearby.data_sources.local

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiScanner
import android.net.wifi.WifiScanner.ScanListener
import android.os.WorkSource
import android.util.Log
import app.grapheneos.networklocation.misc.RustyResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class NearbyWifiApiImpl(
    private val wifiScanner: WifiScanner,
    private val wifiManager: WifiManager
) : NearbyWifiApi {
    private lateinit var workSource: WorkSource

    override suspend fun fetchNearbyWifi(): RustyResult<List<ScanResult>, NearbyWifiApi.LatestNearbyWifiError> {
        if (!wifiManager.isWifiScannerSupported && !(wifiManager.isWifiEnabled || wifiManager.isScanAlwaysAvailable)) {
            return RustyResult.Err(NearbyWifiApi.LatestNearbyWifiError.Unavailable)
        }

        val scanSettings = WifiScanner.ScanSettings()
        scanSettings.band = WifiScanner.WIFI_BAND_BOTH
        scanSettings.type = WifiScanner.SCAN_TYPE_LOW_LATENCY
        scanSettings.rnrSetting = WifiScanner.WIFI_RNR_NOT_NEEDED

        return suspendCancellableCoroutine { continuation ->
            val scanListener = object : ScanListener {
                override fun onSuccess() {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "onSuccess: ")
                    }
                }

                override fun onFailure(reason: Int, description: String?) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "onFailure: reason: $reason, description: $description")
                    }
                    continuation.resume(RustyResult.Err(NearbyWifiApi.LatestNearbyWifiError.Failure))
                }

                @Deprecated("Deprecated in Java")
                override fun onPeriodChanged(periodInMs: Int) {
                    /* no-op */
                }

                override fun onResults(results: Array<out WifiScanner.ScanData>?) {
                    if (results == null) {
                        continuation.resume(RustyResult.Err(NearbyWifiApi.LatestNearbyWifiError.Failure))
                        return
                    }
                    // for single scans, the array size should always be 1
                    if (results.size > 1) {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "Found more than 1 batch of scan results")
                        }
                    }
                    val scannedAccessPoints = results[0].results.toList()
                    continuation.resume(RustyResult.Ok(scannedAccessPoints))
                }

                override fun onFullResult(fullScanResult: ScanResult?) {
                    /* no-op */
                }
            }

            wifiScanner.startScan(scanSettings, scanListener, workSource)

            continuation.invokeOnCancellation { wifiScanner.stopScan(scanListener) }
        }
    }

    override fun setWorkSource(workSource: WorkSource) {
        this@NearbyWifiApiImpl.workSource = workSource
    }

    companion object {
        private const val TAG = "NearbyWifiApiImpl"
    }
}