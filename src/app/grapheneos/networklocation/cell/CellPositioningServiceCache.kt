package app.grapheneos.networklocation.cell

import android.annotation.ElapsedRealtimeLong
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import app.grapheneos.networklocation.PositioningData
import com.android.internal.annotations.GuardedBy
import com.android.internal.os.BackgroundThread
import com.android.server.permission.access.collection.forEachIndexed
import java.io.IOException
import java.util.Optional

private const val TAG = "CellPositioningServiceCache"

private const val CACHE_CAPACITY = 1000

// max number of CellTowerPositioningData entries that service should return in a single call
private const val MAX_RESPONSE_SIZE = 100
private const val CACHE_CLEANUP_INTERVAL_MILLIS: Long = 15 * 60_000L // 15 minutes

class CellPositioningServiceCache(private val service: CellPositioningService) {
    private val lruCache = LruCache<TowerId, Entry>(CACHE_CAPACITY)

    private class Entry(val towerPositioningData: CellTowerPositioningData) {
        @ElapsedRealtimeLong
        var lastAccessTime: Long = SystemClock.elapsedRealtime()
            private set

        @GuardedBy("CellPositioningServiceCache.lruCache")
        fun updateLastAccessTime() {
            lastAccessTime = SystemClock.elapsedRealtime()
        }
    }

    @Throws(IOException::class)
    fun getPositioningData(towerId: TowerId, onlyCached: Boolean): PositioningData? {
        val isVerbose = Log.isLoggable(TAG, Log.VERBOSE)
        checkCache(towerId)?.let {
            val res = it.orElse(null)
            if (isVerbose) Log.v(TAG, "getPositioningData: cache hit for $towerId: $res")
            return res
        }

        if (onlyCached) {
            return null
        }

        if (isVerbose) Log.v(TAG, "querying positioning data for $towerId")
        val towerInfos: List<CellTowerPositioningData> =
            service.fetchNearbyTowerPositioningData(towerId, MAX_RESPONSE_SIZE)
        if (isVerbose) Log.v(TAG, "service response: $towerInfos")

        if (towerInfos.size > MAX_RESPONSE_SIZE) {
            Log.w(
                TAG,
                "service response size (${towerInfos.size}) is greater than MAX_RESPONSE_SIZE"
            )
        }

        synchronized(lruCache) {
            towerInfos.forEachIndexed { idx, pd: CellTowerPositioningData ->
                lruCache.put(pd.cellId, Entry(pd))
                if (idx == MAX_RESPONSE_SIZE) {
                    return@forEachIndexed
                }
            }
            checkCache(towerId)?.let {
                return it.orElse(null)
            }
        }

        Log.w(TAG, "cache lookup failed after service.fetchNearbyTowerPositioningData()")
        return null
    }

    private fun checkCache(towerId: TowerId): Optional<PositioningData>? {
        val cacheEntry = synchronized(lruCache) {
            val res = lruCache.get(towerId) ?: return null
            res.updateLastAccessTime()
            res
        }
        return Optional.ofNullable(cacheEntry.towerPositioningData.positioningData)
    }

    init {
        scheduleClean()
    }

    private fun scheduleClean() {
        // note that the time that the device spends in deep sleep is not counted against the delay
        BackgroundThread.getHandler().postDelayed({ clean() }, CACHE_CLEANUP_INTERVAL_MILLIS)
    }

    private fun clean() {
        scheduleClean()
        val minTime = SystemClock.elapsedRealtime() - CACHE_CLEANUP_INTERVAL_MILLIS
        var numRemoved = 0
        synchronized(lruCache) {
            lruCache.snapshot().entries.forEach {
                if (it.value.lastAccessTime < minTime) {
                    if (lruCache.remove(it.key) != null) {
                        ++numRemoved
                    }
                }
            }
        }
        Log.d(TAG, "clean() removed $numRemoved items")
    }
}