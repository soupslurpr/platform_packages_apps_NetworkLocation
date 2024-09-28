package app.grapheneos.networklocation

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderProperties
import android.location.provider.ProviderRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import app.grapheneos.networklocation.apple_wps.AppleWps
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.pow
import kotlin.properties.Delegates
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * A location provider that uses Apple's Wi-Fi positioning service to get an approximate location.
 */
class NetworkLocationProvider(private val context: Context) : LocationProviderBase(
    context, TAG, PROPERTIES
) {
    companion object {
        private const val TAG: String = "NetworkLocationProvider"
        private val PROPERTIES: ProviderProperties =
            ProviderProperties.Builder()
                .setHasNetworkRequirement(true)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
    }

    // We are above Android N (24)
    @SuppressLint("WifiManagerPotentialLeak")
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var mRequest: ProviderRequest = ProviderRequest.EMPTY_REQUEST
    private var expectedNextLocationUpdateElapsedRealtimeNanos by Delegates.notNull<Long>()
    private var expectedNextBatchUpdateElapsedRealtimeNanos by Delegates.notNull<Long>()
    private var reportLocationJob: Job? = null
    private val reportLocationCoroutineScope = CoroutineScope(Dispatchers.IO)
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isSuccessful = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            scanFinished(isSuccessful)
        }
    }
    private val previousKnownAccessPoints: MutableSet<AppleWps.AccessPoint> = mutableSetOf()
    private val previousUnknownAccessPoints: MutableSet<AppleWps.AccessPoint> = mutableSetOf()
    private val isBatching: Boolean
        get() {
            return if (mRequest.isActive) {
                mRequest.maxUpdateDelayMillis >= (mRequest.intervalMillis * 2)
            } else {
                false
            }
        }
    private val batchedLocations: MutableList<Location> = mutableListOf()

    override fun isAllowed(): Boolean {
        // TODO: also check if the provider is enabled in settings
        return wifiManager.isWifiEnabled || wifiManager.isScanAlwaysAvailable
    }

    fun scanFinished(isSuccessful: Boolean) {
        reportLocationJob = reportLocationCoroutineScope.launch {
            if (!mRequest.isActive) {
                cancel()
            }
            if (!isSuccessful) {
                delay(1000)
                startNextScan()
                cancel()
            }

            val results = wifiManager.scanResults
            val location = Location(LocationManager.NETWORK_PROVIDER)

            location.time = System.currentTimeMillis()
            location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            results.sortByDescending { it.level }

            previousKnownAccessPoints.retainAll { knownAccessPoint ->
                results.any { result ->
                    result.BSSID == knownAccessPoint.bssid
                }
            }
            previousUnknownAccessPoints.retainAll { unknownAccessPoint ->
                results.any { result ->
                    result.BSSID == unknownAccessPoint.bssid
                }
            }

            results.removeAll { result ->
                previousUnknownAccessPoints.any { unknownAccessPoints ->
                    unknownAccessPoints.bssid == result.BSSID
                }
            }

            var bestAvailableAccessPoint: Pair<ScanResult, AppleWps.AccessPoint>? = null

            for (accessPointScanResult in results) {
                run {
                    val foundAccessPoint = previousKnownAccessPoints.find { knownAccessPoint ->
                        knownAccessPoint.bssid == accessPointScanResult.BSSID
                    }
                    if (foundAccessPoint != null) {
                        bestAvailableAccessPoint = Pair(accessPointScanResult, foundAccessPoint)
                    }
                }
                if (bestAvailableAccessPoint != null) {
                    break
                }

                try {
                    // TODO: use settings value to determine whether to connect through GrapheneOS'
                    //  proxy or directly to Apple's server
                    val url = URL("https://gs-loc.apple.com/clls/wloc")
                    val connection = url.openConnection() as HttpsURLConnection

                    val accessPointList = listOf(
                        AppleWps.AccessPoint.newBuilder()
                            .setBssid(accessPointScanResult.BSSID)
                            .build()
                    )

                    val locationRequest =
                        AppleWps.Body.newBuilder()
                            .addAllAccessPoints(accessPointList)
                            .setUnknown1(0)
                            .setNumberOfResults(accessPointList.size)
                            .build()

                    try {
                        connection.requestMethod = "POST"
                        connection.setRequestProperty(
                            "Content-Type", "application/x-www-form-urlencoded"
                        )
                        connection.doOutput = true

                        val message = ByteArrayOutputStream()
                        val dataOutputStream = DataOutputStream(message)
                        locationRequest.writeDelimitedTo(dataOutputStream)
                        dataOutputStream.close()

                        connection.outputStream.use { outputStream ->
                            var request = byteArrayOf()

                            // TODO: Support other locales
                            val locale = "en_US"
                            val identifier = ""
                            val version = ""

                            request += 1.toShort().toBeBytes()
                            request += locale.length.toShort().toBeBytes()
                            request += locale.toByteArray()
                            request += identifier.length.toShort().toBeBytes()
                            request += identifier.toByteArray()
                            request += version.length.toShort().toBeBytes()
                            request += version.toByteArray()
                            request += 0.toShort().toBeBytes()
                            request += 1.toShort().toBeBytes()
                            request += 0.toShort().toBeBytes()
                            request += 0.toByte()

                            request += message.toByteArray()

                            outputStream.write(request)
                        }

                        val responseCode = connection.responseCode
                        if (responseCode == HttpsURLConnection.HTTP_OK) {
                            connection.inputStream.use { inputStream ->
                                inputStream.skip(10)
                                val response = AppleWps.Body.parseFrom(inputStream)

                                val nullLatitudeOrLongitude = -18000000000

                                val matchedAccessPoints = results.mapNotNull { accessPoint ->
                                    val foundAccessPoint =
                                        response.accessPointsList.find { responseAccessPoint ->
                                            (responseAccessPoint.bssid == accessPoint.BSSID) && (responseAccessPoint.positioningInfo.latitude != nullLatitudeOrLongitude) && (responseAccessPoint.positioningInfo.longitude != nullLatitudeOrLongitude)
                                        }
                                    return@mapNotNull if (foundAccessPoint != null) {
                                        Pair(accessPoint, foundAccessPoint)
                                    } else {
                                        null
                                    }
                                }.sortedByDescending {
                                    it.first.level
                                }

                                if (matchedAccessPoints.isNotEmpty()) {
                                    bestAvailableAccessPoint = matchedAccessPoints[0]
                                }
                            }
                        } else {
                            delay(1000)
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    delay(1000)
                }
            }

            // TODO: move setting the location outta the loop and check that
            //  bestAvailableAccessPoint isn't null
            location.latitude =
                firstMatchedAccessPoint.second.positioningInfo.latitude.toDouble() * (10).toDouble()
                    .pow(-8)
            location.longitude =
                firstMatchedAccessPoint.second.positioningInfo.longitude.toDouble() * (10).toDouble()
                    .pow(-8)

            // TODO: verify that this formula is correct
            // estimate distance (in meters) from access point using signal strength
            val distanceFromAccessPoint =
                (10).toDouble().pow((-30 - (firstMatchedAccessPoint.first.level)) / 30)

            /// should be at the 68th percentile confidence level
            val accuracy =
                (firstMatchedAccessPoint.second.positioningInfo.accuracy.toFloat() * 0.68f) + distanceFromAccessPoint.toFloat()

            location.verticalAccuracyMeters
            location.accuracy = accuracy
            //


            if (isBatching) {
                batchedLocations += location

                if ((SystemClock.elapsedRealtimeNanos() >= expectedNextBatchUpdateElapsedRealtimeNanos) || (batchedLocations.size >= (mRequest.maxUpdateDelayMillis / mRequest.intervalMillis))) {
                    expectedNextBatchUpdateElapsedRealtimeNanos += mRequest.maxUpdateDelayMillis.toDuration(
                        DurationUnit.MILLISECONDS
                    ).inWholeNanoseconds
                    reportLocations(batchedLocations)
                }
            } else {
                reportLocation(location)
            }

            expectedNextLocationUpdateElapsedRealtimeNanos += mRequest.intervalMillis.toDuration(
                DurationUnit.MILLISECONDS
            ).inWholeNanoseconds

            startNextScan()
        }
    }

    private fun start() {
        expectedNextLocationUpdateElapsedRealtimeNanos =
            SystemClock.elapsedRealtimeNanos() + mRequest.intervalMillis.toDuration(
                DurationUnit.MILLISECONDS
            ).inWholeNanoseconds
        if (isBatching) {
            expectedNextBatchUpdateElapsedRealtimeNanos =
                SystemClock.elapsedRealtimeNanos() + mRequest.maxUpdateDelayMillis.toDuration(
                    DurationUnit.MILLISECONDS
                ).inWholeNanoseconds
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, intentFilter)

        reportLocationJob = reportLocationCoroutineScope.launch {
            startNextScan()
        }
    }

    fun stop() {
        mRequest = ProviderRequest.EMPTY_REQUEST
        reportLocationJob?.cancel()
        reportLocationJob = null
        previousKnownAccessPoints.clear()
        previousUnknownAccessPoints.clear()

        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private suspend fun startNextScan() {
        // scan takes ~20 seconds on lynx (Pixel 7a)
        val estimatedAfterScanElapsedRealtimeNanos =
            SystemClock.elapsedRealtimeNanos() + 20.0.toDuration(DurationUnit.SECONDS).inWholeNanoseconds
        if (estimatedAfterScanElapsedRealtimeNanos < expectedNextLocationUpdateElapsedRealtimeNanos) {
            // delay to ensure we get a fresh location
            delay(expectedNextLocationUpdateElapsedRealtimeNanos - estimatedAfterScanElapsedRealtimeNanos)
        }
        wifiManager.startScan(mRequest.workSource)
    }

    override fun onSetRequest(request: ProviderRequest) {
        // TODO: remove debug println
        println("CALLED onSetRequest! request: $request")

        stop()
        mRequest = request

        if (mRequest.isActive) {
            start()
        }
    }

    override fun onFlush(callback: OnFlushCompleteCallback) {
        if (batchedLocations.isNotEmpty()) {
            if (batchedLocations.size == 1) {
                reportLocation(batchedLocations[0])
            } else {
                reportLocations(batchedLocations)
            }

            batchedLocations.clear()
        }

        callback.onFlushComplete()
    }

    override fun onSendExtraCommand(command: String, extras: Bundle?) {
    }

}

fun Short.toBeBytes(): ByteArray {
    return byteArrayOf(
        ((this.toInt() shr 8) and 0xFF).toByte(), (this.toInt() and 0xFF).toByte()
    )
}