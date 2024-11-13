package app.grapheneos.networklocation

import android.content.Context
import android.ext.settings.NetworkLocationSettings
import android.location.Location
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderProperties
import android.location.provider.ProviderRequest
import android.net.wifi.WifiScanner
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.NearbyWifiAccessPointsPositioningDataRepository
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points.NearbyWifiAccessPointsRepository
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points.data_sources.local.NearbyWifiAccessPointsApiImpl
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.nearby_wifi_access_points.data_sources.local.NearbyWifiAccessPointsLocalDataSource
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data.WifiAccessPointsPositioningDataRepository
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data.data_sources.server.WifiAccessPointsPositioningDataApiImpl
import app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data.data_sources.server.WifiAccessPointsPositioningDataServerDataSource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration
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
    private val networkLocationServerSetting: () -> Int = {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.NETWORK_LOCATION
        )
    },
    private val networkLocationRepository: NetworkLocationRepository = NetworkLocationRepository(
        nearbyWifiAccessPointsPositioningDataRepository = NearbyWifiAccessPointsPositioningDataRepository(
            nearbyWifiAccessPointsRepository = NearbyWifiAccessPointsRepository(
                nearbyWifiAccessPointsLocalDataSource = NearbyWifiAccessPointsLocalDataSource(
                    nearbyWifiAccessPointsApi = NearbyWifiAccessPointsApiImpl(
                        wifiScanner = context.getSystemService(WifiScanner::class.java)!!
                    ),
                    ioDispatcher = Dispatchers.IO
                )
            ),
            wifiAccessPointsPositioningDataRepository = WifiAccessPointsPositioningDataRepository(
                wifiAccessPointsPositioningDataServerDataSource = WifiAccessPointsPositioningDataServerDataSource(
                    wifiAccessPointsPositioningDataApi = WifiAccessPointsPositioningDataApiImpl(
                        networkLocationServerSetting = networkLocationServerSetting
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
    init {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                isAllowed =
                    networkLocationServerSetting() != NetworkLocationSettings.NETWORK_LOCATION_DISABLED
                delay(1.seconds)
            }
        }
    }

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

        if (networkLocationServerSetting() == NetworkLocationSettings.NETWORK_LOCATION_DISABLED) {
            isAllowed = false
            return
        } else {
            isAllowed = true
        }

        if (request.isActive) {
            val isBatching =
                (request.maxUpdateDelayMillis != 0L) && (request.maxUpdateDelayMillis >= (request.intervalMillis * 2))
            networkLocationRepository.setWorkSource(request.workSource)
            networkLocationRepository.setUpdateTarget(
                SystemClock.elapsedRealtimeNanos() + request.intervalMillis.toDuration(
                    DurationUnit.MILLISECONDS
                ).inWholeNanoseconds
            )
            incrementUpdateTargetJob = incrementUpdateTargetCoroutine.launch {
                while (isActive) {
                    delay(request.intervalMillis)
                    networkLocationRepository.setUpdateTarget(
                        SystemClock.elapsedRealtimeNanos() + request.intervalMillis.toDuration(
                            DurationUnit.MILLISECONDS
                        ).inWholeNanoseconds
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
        }
    }

    // TODO: implement clearing caches and call it when request stops (!isActive) or when provider gets disabled
//    private fun clearCaches() {
//        networkLocationRepository.clearCaches()
//    }

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
        private val PROPERTIES: ProviderProperties =
            ProviderProperties.Builder()
                .setHasNetworkRequirement(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
    }
}