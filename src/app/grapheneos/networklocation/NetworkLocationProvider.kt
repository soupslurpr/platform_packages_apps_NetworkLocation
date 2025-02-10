package app.grapheneos.networklocation

import android.content.Context
import android.location.Location
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderProperties
import android.location.provider.ProviderRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiScanner
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.grapheneos.networklocation.NetworkLocationRepository.LatestLocationError
import app.grapheneos.networklocation.misc.ExponentialBackOff
import app.grapheneos.networklocation.misc.RustyResult
import app.grapheneos.networklocation.wifi.nearby.NearbyWifiRepository
import app.grapheneos.networklocation.wifi.nearby.data_sources.local.NearbyWifiApiImpl
import app.grapheneos.networklocation.wifi.nearby.data_sources.local.NearbyWifiLocalDataSource
import app.grapheneos.networklocation.wifi.nearby_positioning_data.NearbyWifiPositioningDataRepository
import app.grapheneos.networklocation.wifi.positioning_data.WifiPositioningDataRepository
import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.WifiPositioningDataApiImpl
import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.WifiPositioningDataServerDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class NetworkLocationProvider(
    private val context: Context,
    private val networkLocationSettingValue: () -> Int,
    private val networkLocationRepository: NetworkLocationRepository = NetworkLocationRepository(
        nearbyWifiPositioningDataRepository = NearbyWifiPositioningDataRepository(
            nearbyWifiRepository = NearbyWifiRepository(
                nearbyWifiLocalDataSource = NearbyWifiLocalDataSource(
                    nearbyWifiApi = NearbyWifiApiImpl(
                        wifiScanner = context.getSystemService(WifiScanner::class.java)!!,
                        wifiManager = context.getSystemService(WifiManager::class.java)!!
                    ),
                    ioDispatcher = Dispatchers.IO
                )
            ),
            wifiPositioningDataRepository = WifiPositioningDataRepository(
                wifiPositioningDataServerDataSource = WifiPositioningDataServerDataSource(
                    wifiPositioningDataApi = WifiPositioningDataApiImpl(
                        networkLocationServerSetting = networkLocationSettingValue
                    ),
                    ioDispatcher = Dispatchers.IO
                )
            )
        )
    )
) : LocationProviderBase(
    context,
    TAG,
    PROPERTIES
) {
    private var batchedLocations: MutableList<Location> = mutableListOf()

    private var reportLocationJob: Job? = null
    private val reportLocationCoroutine = CoroutineScope(Dispatchers.IO)

    private val locationRetryExponentialBackOff = ExponentialBackOff()

    override fun onSetRequest(request: ProviderRequest) {
        runBlocking {
            if (reportLocationJob?.isActive == true) {
                reportLocationJob?.cancelAndJoin()
            }
            batchedLocations.clear()
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, request.toString())
        }

        if (request.isActive && isAllowed) {
            val isBatching =
                (request.maxUpdateDelayMillis != 0L) &&
                        (request.maxUpdateDelayMillis >= (request.intervalMillis * 2))
            networkLocationRepository.setWorkSource(request.workSource)

            reportLocationJob = reportLocationCoroutine.launch {
                // Make sure apps can't bypass the retry exponential back off by spamming requests.
                if (locationRetryExponentialBackOff.currentDuration > locationRetryExponentialBackOff.initialDuration) {
                    delay(locationRetryExponentialBackOff.currentDuration)
                    locationRetryExponentialBackOff.advance()
                }

                var delayNextLocationRetryElapsedRealtime =
                    SystemClock.elapsedRealtimeNanos().nanoseconds

                networkLocationRepository.latestLocation.collect { location ->
                    var delayNextLocationRetryAddend = request.intervalMillis.milliseconds

                    when (location) {
                        is RustyResult.Err -> when (location.error) {
                            LatestLocationError.Failure, LatestLocationError.Unavailable -> {
                                val delayTime =
                                    (delayNextLocationRetryElapsedRealtime + delayNextLocationRetryAddend) -
                                            SystemClock.elapsedRealtimeNanos().nanoseconds
                                if (delayTime < locationRetryExponentialBackOff.currentDuration) {
                                    delayNextLocationRetryElapsedRealtime =
                                        SystemClock.elapsedRealtimeNanos().nanoseconds
                                    delayNextLocationRetryAddend =
                                        locationRetryExponentialBackOff.currentDuration
                                }
                                locationRetryExponentialBackOff.advance()
                            }
                        }

                        is RustyResult.Ok -> {
                            locationRetryExponentialBackOff.reset()
                            if (location.value != null) {
                                if (isBatching) {
                                    batchedLocations.add(location.value)
                                    val maxBatchSize =
                                        request.maxUpdateDelayMillis / request.intervalMillis
                                    if (batchedLocations.size >= maxBatchSize) {
                                        reportLocations(batchedLocations)
                                        batchedLocations.clear()
                                    }
                                } else {
                                    reportLocation(location.value)
                                }
                            }
                        }
                    }

                    delayNextLocationRetryElapsedRealtime += delayNextLocationRetryAddend

                    delay(
                        delayNextLocationRetryElapsedRealtime - SystemClock.elapsedRealtimeNanos().nanoseconds
                    )

                    delayNextLocationRetryElapsedRealtime =
                        SystemClock.elapsedRealtimeNanos().nanoseconds
                }
            }
        } else {
            // clear caches when the request is inactive or isAllowed is false to prevent saving a
            // location history
            runBlocking {
                networkLocationRepository.clearCaches()
            }
        }
    }

    override fun onFlush(callback: OnFlushCompleteCallback) {
        if (batchedLocations.isNotEmpty()) {
            reportLocations(batchedLocations)
            batchedLocations.clear()
        }
        callback.onFlushComplete()
    }

    override fun onSendExtraCommand(command: String, extras: Bundle?) {}

    companion object {
        private const val TAG: String = "NetworkLocationProvider"

        // make sure to manually keep in sync with Google's network location provider properties
        // for app compatibility
        private val PROPERTIES: ProviderProperties =
            ProviderProperties.Builder()
                .setPowerUsage(ProviderProperties.POWER_USAGE_MEDIUM)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .setHasNetworkRequirement(true)
                .setHasAltitudeSupport(true)
                .build()
    }
}