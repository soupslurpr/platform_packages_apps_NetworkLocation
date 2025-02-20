package app.grapheneos.networklocation.cell.nearby

import android.os.WorkSource
import app.grapheneos.networklocation.cell.nearby.data_sources.local.NearbyCellApi
import app.grapheneos.networklocation.cell.nearby.data_sources.local.NearbyCellLocalDataSource
import app.grapheneos.networklocation.misc.RustyResult
import kotlinx.coroutines.flow.map

class NearbyCellRepository(
    private val nearbyCellLocalDataSource: NearbyCellLocalDataSource
) {
    sealed class LatestNearbyCellError {
        data object Failure : LatestNearbyCellError()
        data object Unavailable : LatestNearbyCellError()
    }

    val latestNearbyCell = nearbyCellLocalDataSource.latestNearbyCell.map {
        when (it) {
            is RustyResult.Err -> when (it.error) {
                NearbyCellApi.LatestNearbyCellError.Failure -> RustyResult.Err(
                    LatestNearbyCellError.Failure
                )

                NearbyCellApi.LatestNearbyCellError.Unavailable -> RustyResult.Err(
                    LatestNearbyCellError.Unavailable
                )
            }

            is RustyResult.Ok -> RustyResult.Ok(it.value)
        }
    }

    fun setWorkSource(workSource: WorkSource) =
        nearbyCellLocalDataSource.setWorkSource(workSource)
}