package app.grapheneos.networklocation.cell.nearby.data_sources.local

import android.os.WorkSource
import android.telephony.CellInfo
import app.grapheneos.networklocation.misc.RustyResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.take

class NearbyCellLocalDataSource(
    private val nearbyCellApi: NearbyCellApi,
    ioDispatcher: CoroutineDispatcher
) {
    val latestNearbyCell = flow {
        while (true) {
            nearbyCellApi.fetchNearbyCell().take(1).collect {
                emit(it)
            }
        }
    }.flowOn(ioDispatcher)

    fun setWorkSource(workSource: WorkSource) = nearbyCellApi.setWorkSource(workSource)
}

interface NearbyCellApi {
    sealed class LatestNearbyCellError {
        data object Failure : LatestNearbyCellError()
        data object Unavailable : LatestNearbyCellError()
    }

    /**
     * Fetch nearby cell towers.
     */
    fun fetchNearbyCell(): Flow<RustyResult<List<CellInfo>, LatestNearbyCellError>>
    fun setWorkSource(workSource: WorkSource)
}