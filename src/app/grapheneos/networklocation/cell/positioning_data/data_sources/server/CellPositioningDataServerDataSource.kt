package app.grapheneos.networklocation.cell.positioning_data.data_sources.server

import app.grapheneos.networklocation.cell.positioning_data.CellTower
import app.grapheneos.networklocation.misc.RustyResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class CellPositioningDataServerDataSource(
    private val cellPositioningDataApi: CellPositioningDataApi,
    private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun fetchPositioningData(identifiers: List<CellTower.Identifier>) =
        withContext(ioDispatcher) {
            cellPositioningDataApi.fetchPositioningData(
                identifiers
            )
        }
}

interface CellPositioningDataApi {
    sealed class FetchPositioningDataError {
        data object Failure : FetchPositioningDataError()
        data object Unavailable : FetchPositioningDataError()
    }

    fun fetchPositioningData(identifiers: List<CellTower.Identifier>): RustyResult<AppleCps.CellPositioningDataApiModel, FetchPositioningDataError>
}

fun AppleCps.PositioningData.isNull(): Boolean = this.latitude == -18000000000