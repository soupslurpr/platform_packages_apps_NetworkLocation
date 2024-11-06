package app.grapheneos.networklocation

import android.content.Context
import android.location.provider.ProviderRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiScanner
import android.net.wifi.WifiScanner.ScanListener
import android.os.Build
import android.util.Log

class WifiScanner(
    context: Context,
    private val request: () -> ProviderRequest,
    private val callback: (response: ScanResponse) -> Unit
) {
    sealed class ScanResponse {
        data class Success(val scanResults: MutableList<ScanResult>) : ScanResponse()
        data class Failed(val reason: Int, val description: String?) : ScanResponse()
    }

    private val wifiScanner = context.getSystemService(WifiScanner::class.java)!!

    private val scanListener = object : ScanListener {
        override fun onSuccess() {
            if (DEBUG) {
                Log.d(TAG, "onSuccess: ")
            }
        }

        override fun onFailure(reason: Int, description: String?) {
            if (DEBUG) {
                Log.d(TAG, "onFailure: ")
            }
            callback(ScanResponse.Failed(reason, description))
        }

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
            callback(ScanResponse.Success(scannedAccessPoints))
        }

        override fun onFullResult(fullScanResult: ScanResult?) {
            /* no-op */
        }

    }

    fun start() {
        Log.d(TAG, "scanning update requested")

        val scanSettings = WifiScanner.ScanSettings()
        scanSettings.band = WifiScanner.WIFI_BAND_5_GHZ
        scanSettings.type = WifiScanner.SCAN_TYPE_LOW_LATENCY

        scanSettings.rnrSetting = WifiScanner.WIFI_RNR_NOT_NEEDED

        val source = request().workSource
        wifiScanner.startScan(scanSettings, scanListener, source)
    }

    fun stop() {
        Log.d(TAG, "scanning request stopped")
        wifiScanner.stopScan(scanListener)
    }

    companion object {
        private const val TAG = "WifiScanner"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG) || Build.IS_DEBUGGABLE
    }
}