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
import app.grapheneos.networklocation.wifi.nearby.NearbyWifiRepository
import app.grapheneos.networklocation.wifi.nearby.data_sources.local.NearbyWifiApiImpl
import app.grapheneos.networklocation.wifi.nearby.data_sources.local.NearbyWifiLocalDataSource
import app.grapheneos.networklocation.wifi.nearby_positioning_data.NearbyWifiPositioningDataRepository
import app.grapheneos.networklocation.wifi.positioning_data.WifiPositioningDataRepository
import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.WifiPositioningDataApiImpl
import app.grapheneos.networklocation.wifi.positioning_data.data_sources.server.WifiPositioningDataServerDataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    private var incrementUpdateTargetJob: Job? = null
    private var incrementUpdateTargetCoroutine = CoroutineScope(Dispatchers.IO)

    override fun onSetRequest(request: ProviderRequest) {
        runBlocking {
            if (incrementUpdateTargetJob?.isActive == true) {
                incrementUpdateTargetJob?.cancelAndJoin()
            }
            if (reportLocationJob?.isActive == true) {
                reportLocationJob?.cancelAndJoin()
            }
            batchedLocations.clear()
        }

        if (request.isActive && isAllowed) {
            val isBatching =
                (request.maxUpdateDelayMillis != 0L) && (request.maxUpdateDelayMillis >= (request.intervalMillis * 2))
            networkLocationRepository.setWorkSource(request.workSource)
            networkLocationRepository.setUpdateTarget(
                SystemClock.elapsedRealtimeNanos() + request.intervalMillis.milliseconds.inWholeNanoseconds
            )
            incrementUpdateTargetJob = incrementUpdateTargetCoroutine.launch {
                while (isActive) {
                    delay(request.intervalMillis)
                    networkLocationRepository.setUpdateTarget(
                        SystemClock.elapsedRealtimeNanos() + request.intervalMillis.milliseconds.inWholeNanoseconds
                    )
                }
            }
            reportLocationJob = reportLocationCoroutine.launch {
                networkLocationRepository.latestLocation.collect { location ->
                    if (location != null) {
                        if (isBatching) {
                            batchedLocations.add(location)
                            val maxBatchSize = request.maxUpdateDelayMillis / request.intervalMillis
                            if (batchedLocations.size >= maxBatchSize) {
                                reportLocations(batchedLocations)
                            }
                        } else {
                            reportLocation(location)
                        }
                    }
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