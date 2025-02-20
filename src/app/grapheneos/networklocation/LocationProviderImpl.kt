package app.grapheneos.networklocation

import android.content.Context
import android.ext.settings.NetworkLocationSettings
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderProperties
import android.location.provider.ProviderRequest
import android.os.Bundle
import android.util.Log
import app.grapheneos.networklocation.wifi.AppleWifiPositioningService
import app.grapheneos.networklocation.wifi.LocationReportingTask
import app.grapheneos.networklocation.wifi.WifiApScanner
import app.grapheneos.networklocation.wifi.WifiPositioningServiceCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "LocationProviderImpl"

// Reuse cache across NetworkLocationProvider instances to reduce latency and network usage. Note
// that the cache is periodically cleaned up to prevent recording long location history, see
// WifiPositioningServiceCache.scheduleClean()
private val wifiPositioningServiceCache by lazy {
    WifiPositioningServiceCache(AppleWifiPositioningService())
}

class LocationProviderImpl(private val context: Context)
        : LocationProviderBase(context, TAG, createProviderProperties()) {

    private var locationReportingJob: Job? = null

    override fun onSetRequest(request: ProviderRequest) {
        val isVerbose = Log.isLoggable(TAG, Log.VERBOSE)
        if (isVerbose) Log.v(TAG, "onSetRequest: $request")

        synchronized(this) {
            val prevJob = locationReportingJob
            if (prevJob != null) {
                if (isVerbose) Log.v(TAG, "cancelling previous locationReportingJob")
                runBlocking {
                    prevJob.cancelAndJoin()
                }
                if (isVerbose) Log.v(TAG, "canceled previous locationReportingJob")
                locationReportingJob = null
            }

            if (!request.isActive) {
                return
            }

            val task = LocationReportingTask(this, request, WifiApScanner(context),
                wifiPositioningServiceCache)
            locationReportingJob = CoroutineScope(Dispatchers.IO).launch {
                task.run()
            }
        }
    }

    override fun onFlush(callback: OnFlushCompleteCallback) {
        verboseLog(TAG) {"onFlush"}
        // Location batching is not supported by this location provider. Location batching is meant to
        // be used by providers that can estimate location even when the application processor (AP)
        // is suspended.
        callback.onFlushComplete()
    }

    override fun onSendExtraCommand(command: String, extras: Bundle?) {
        Log.d(TAG, "onSendExtraCommand: $command, extras: $extras")
    }

    fun updateIsAllowedState() {
        val setting = NETWORK_LOCATION_SETTING.get(context)
        val isEnabled = setting != NetworkLocationSettings.NETWORK_LOCATION_DISABLED
        setAllowed(isEnabled)
    }
}

// make sure to manually keep in sync with Google's network location provider properties
// for app compatibility
private fun createProviderProperties(): ProviderProperties {
    return ProviderProperties.Builder().run {
        setPowerUsage(ProviderProperties.POWER_USAGE_MEDIUM)
        setAccuracy(ProviderProperties.ACCURACY_FINE)
        setHasNetworkRequirement(true)
        setHasAltitudeSupport(true)
        build()
    }
}

// TODO: refactor below code into above
private data class NearbyPositioningDataSourceTracker(
    val identifier: Identifier,
    // desirability of this data source relative to the others from greatest to least
    val desirability: Int,
    var viability: Viability,
    // exponential back off meant to track minimum time before trying to use the data source
    // again after it's nonviable
    val nonviabilityExponentialBackOff: ExponentialBackOff,
    // the timestamp of the end of the last attempt to use this data source, used for
    // exponential backoff tracking
    var lastTryTimestamp: Duration = 0.seconds
) {
    enum class Identifier {
        WIFI,
        CELL
    }

    enum class Viability {
        VIABLE,
        TEETERING,
        NONVIABLE,
    }
}

private val nearbyPositioningDataSourceTrackers = setOf(
    NearbyPositioningDataSourceTracker(
        NearbyPositioningDataSourceTracker.Identifier.CELL,
        0,
        NearbyPositioningDataSourceTracker.Viability.VIABLE,
        ExponentialBackOff()
    ),
    NearbyPositioningDataSourceTracker(
        NearbyPositioningDataSourceTracker.Identifier.WIFI,
        1,
        NearbyPositioningDataSourceTracker.Viability.VIABLE,
        ExponentialBackOff()
    )
)

val latestLocation: Flow<RustyResult<Location?, LatestLocationError>> = flow {
    while (true) {
        // TODO: consider incrementing this every time we try to get a location. once it hits
        //  2 or 3, just try every single data source we have left at the same time
        var tries = 0
        val dataSourcesToTry: MutableList<NearbyPositioningDataSourceTracker> = mutableListOf()
        val geoMeasurements: MutableList<GeoMeasurement> = mutableListOf()

        data class EstimatedGeolocation(
            val position: GeoPoint,
            val accuracyRadius: Double
        )

        var estimatedGeolocation: EstimatedGeolocation? = null

        for ((index, dataSource) in nearbyPositioningDataSourceTrackers.sortedByDescending { it.desirability }
            .withIndex()) {
            dataSourcesToTry.add(dataSource)

            val isOnLastDataSource = (index + 1) == nearbyPositioningDataSourceTrackers.size

            // if we found a viable data source or if we exhaust all options without finding a
            // viable one, try with what we got
            if ((dataSource.viability == NearbyPositioningDataSourceTracker.Viability.VIABLE) || isOnLastDataSource) {
                val dataSourceJobs: MutableList<Job> = mutableListOf()
                val dataSourceCoroutine = CoroutineScope(Dispatchers.IO)

                for (dataSourceToTry in dataSourcesToTry) {
                    if (dataSourceToTry.viability == NearbyPositioningDataSourceTracker.Viability.NONVIABLE) {
                        val exponentialBackOff = dataSourceToTry.nonviabilityExponentialBackOff

                        if (exponentialBackOff.currentDuration > exponentialBackOff.initialDuration) {
                            if ((dataSourceToTry.lastTryTimestamp + exponentialBackOff.currentDuration) > SystemClock.elapsedRealtime().milliseconds) {
                                continue
                            }
                        }
                    }

                    val job = dataSourceCoroutine.launch {
                        val dataSourceToTryGeoMeasurements: MutableList<GeoMeasurement> =
                            mutableListOf()

                        when (dataSourceToTry.identifier) {
                            NearbyPositioningDataSourceTracker.Identifier.WIFI -> {
                                nearbyWifiPositioningDataRepository.latestNearbyWifiPositioningData.take(
                                    1
                                ).collect { nearbyWifi ->
                                    dataSourceToTry.viability = when (nearbyWifi) {
                                        is RustyResult.Err -> {
                                            NearbyPositioningDataSourceTracker.Viability.NONVIABLE
                                        }

                                        is RustyResult.Ok -> {
                                            val ok = nearbyWifi.value.mapNotNull {
                                                val positioningData =
                                                    it.positioningData ?: return@mapNotNull null

                                                GeoMeasurement(
                                                    GeoPoint(
                                                        it.positioningData.latitude,
                                                        it.positioningData.longitude
                                                    ),
                                                    positioningData.accuracyMeters.toDouble()
                                                        .pow(2),
                                                    positioningData.rssi.toDouble(),
                                                    it.lastSeen.microseconds
                                                )
                                            }

                                            dataSourceToTryGeoMeasurements.addAll(ok)

                                            when (ok.size) {
                                                0 -> NearbyPositioningDataSourceTracker.Viability.NONVIABLE
                                                1, 2 -> NearbyPositioningDataSourceTracker.Viability.TEETERING
                                                else -> NearbyPositioningDataSourceTracker.Viability.VIABLE
                                            }
                                        }
                                    }
                                }
                            }

                            NearbyPositioningDataSourceTracker.Identifier.CELL -> {
                                nearbyCellPositioningDataRepository.latestNearbyCellPositioningData.take(
                                    1
                                ).collect { nearbyCell ->
                                    dataSourceToTry.viability = when (nearbyCell) {
                                        is RustyResult.Err -> {
                                            NearbyPositioningDataSourceTracker.Viability.NONVIABLE
                                        }

                                        is RustyResult.Ok -> {
                                            val ok = nearbyCell.value.mapNotNull {
                                                val positioningData =
                                                    it.positioningData ?: return@mapNotNull null

                                                GeoMeasurement(
                                                    GeoPoint(
                                                        it.positioningData.latitude,
                                                        it.positioningData.longitude
                                                    ),
                                                    positioningData.accuracyMeters.toDouble()
                                                        .pow(2),
                                                    positioningData.rssi.toDouble(),
                                                    it.lastSeen.milliseconds
                                                )
                                            }

                                            dataSourceToTryGeoMeasurements.addAll(ok)

                                            when (ok.size) {
                                                0 -> NearbyPositioningDataSourceTracker.Viability.NONVIABLE
                                                1, 2 -> NearbyPositioningDataSourceTracker.Viability.TEETERING
                                                else -> NearbyPositioningDataSourceTracker.Viability.VIABLE
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (dataSourceToTry.viability == NearbyPositioningDataSourceTracker.Viability.NONVIABLE) {
                            dataSourceToTry.nonviabilityExponentialBackOff.advance()
                        } else {
                            dataSourceToTry.nonviabilityExponentialBackOff.reset()
                        }

                        geoMeasurements.addAll(dataSourceToTryGeoMeasurements)

                        dataSourceToTry.lastTryTimestamp =
                            SystemClock.elapsedRealtime().milliseconds
                    }

                    dataSourceJobs.add(job)
                }
                dataSourceJobs.joinAll()

                var wereAnyViable = false

                for (triedDataSource in dataSourcesToTry) {
                    if (Log.isLoggable(TAG, Log.INFO)) {
                        Log.i(
                            TAG,
                            "checking viability status of triedDataSource: $triedDataSource"
                        )
                    }

                    if (triedDataSource.viability == NearbyPositioningDataSourceTracker.Viability.VIABLE) {
                        wereAnyViable = true
                        break
                    }
                }

                dataSourcesToTry.clear()

                if (wereAnyViable || isOnLastDataSource) {
                    if (geoMeasurements.isNotEmpty()) {
                        val refGeoPoint = GeoPoint(
                            geoMeasurements.map { it.apPosition.latitude }.median(),
                            geoMeasurements.map { it.apPosition.longitude }.median()
                        )

                        val measurements = geoMeasurements.map {
                            Measurement(
                                apPosition = geoPointToEnuPoint(
                                    it.apPosition,
                                    refGeoPoint
                                ),
                                apPositionVariance = it.apPositionVariance,
                                rssi = it.rssi,
                                lastSeen = it.lastSeen
                            )
                        }

                        val estimatedLocation = estimateLocation(
                            measurements,
                            // accuracy radius should be at the 68th percentile confidence level
                            0.68,
                            Random(System.currentTimeMillis())
                        )

                        if (estimatedLocation != null) {
                            estimatedGeolocation = EstimatedGeolocation(
                                enuPointToGeoPoint(
                                    Point(
                                        estimatedLocation.position.x,
                                        estimatedLocation.position.y
                                    ),
                                    refGeoPoint
                                ),
                                estimatedLocation.accuracyRadius
                            )
                            break
                        }
                    }
                }
            }
        }

        val result = if (estimatedGeolocation != null && geoMeasurements.isNotEmpty()) {
            val location = Location(LocationManager.NETWORK_PROVIDER)

            location.elapsedRealtimeNanos =
                geoMeasurements.map { it.lastSeen.inWholeNanoseconds }.sorted()[0]
            location.time =
                (System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos().nanoseconds.inWholeMilliseconds) + location.elapsedRealtimeNanos.nanoseconds.inWholeMilliseconds
            location.latitude = estimatedGeolocation.position.latitude
            location.longitude = estimatedGeolocation.position.longitude

            location.accuracy = estimatedGeolocation.accuracyRadius.toFloat()

            RustyResult.Ok(location)
        } else {
            if (geoMeasurements.isEmpty()) {
                RustyResult.Err(LatestLocationError.Unavailable)
            } else {
                RustyResult.Err(LatestLocationError.Failure)
            }
        }

        emit(result)
    }
}