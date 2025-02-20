package app.grapheneos.networklocation.cell.positioning_data

import app.grapheneos.networklocation.cell.positioning_data.data_sources.server.CellPositioningDataApi
import app.grapheneos.networklocation.cell.positioning_data.data_sources.server.CellPositioningDataServerDataSource
import app.grapheneos.networklocation.cell.positioning_data.data_sources.server.isNull
import app.grapheneos.networklocation.misc.RustyResult
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

class CellPositioningDataRepository(
    private val cellPositioningDataServerDataSource: CellPositioningDataServerDataSource,
) {
    sealed class FetchPositioningDataError {
        data object Failure : FetchPositioningDataError()
        data object Unavailable : FetchPositioningDataError()
    }

    suspend fun fetchPositioningData(identifiers: List<CellTower.Identifier>): RustyResult<List<CellTower>, FetchPositioningDataError> {
        val response = cellPositioningDataServerDataSource.fetchPositioningData(identifiers)

        when (response) {
            is RustyResult.Err -> {
                return when (response.error) {
                    CellPositioningDataApi.FetchPositioningDataError.Failure -> RustyResult.Err(
                        FetchPositioningDataError.Failure
                    )

                    CellPositioningDataApi.FetchPositioningDataError.Unavailable -> RustyResult.Err(
                        FetchPositioningDataError.Unavailable
                    )
                }
            }

            is RustyResult.Ok -> {
                val convertedPositioningData =
                    response.value.cellTowerResponseList.mapNotNull {
                        CellTower(
                            identifier = CellTower.Identifier(
                                mcc = it.mcc.toUShort(),
                                mnc = it.mnc.toUShort(),
                                cellId = if (it.cellId != -1L) {
                                    it.cellId.toUInt()
                                } else {
                                    return@mapNotNull null
                                },
                                tacId = it.tacId.toUInt(),
                                earfcn = if (it.hasEarfcn()) {
                                    it.earfcn.toUInt()
                                } else {
                                    null
                                },
                                pci = if (it.hasPci()) {
                                    it.pci.toUInt()
                                } else {
                                    null
                                }
                            ),
                            positioningData = if (!it.positioningData.isNull()) {
                                it.positioningData.let { serverApiPositioningData ->
                                    var latitude = serverApiPositioningData.latitude * 10.0.pow(-8)
                                    var longitude =
                                        serverApiPositioningData.longitude * 10.0.pow(-8)

                                    // TODO: quantization testing
                                    fun roundToThreeDecimals(value: Double): Double {
                                        return BigDecimal(value).setScale(3, RoundingMode.HALF_UP)
                                            .toDouble()
                                    }

                                    latitude = roundToThreeDecimals(latitude)
                                    longitude = roundToThreeDecimals(longitude)

                                    CellTower.PositioningData(
                                        latitude,
                                        longitude,
                                        serverApiPositioningData.accuracyMeters
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }

                return RustyResult.Ok(convertedPositioningData)
            }
        }
    }
}

data class CellTower(
    val identifier: Identifier,
    val positioningData: PositioningData?
) {
    data class Identifier(
        val mcc: UShort,
        val mnc: UShort,
        val cellId: UInt,
        val tacId: UInt,
        val earfcn: UInt?,
        val pci: UInt?
    )

    data class PositioningData(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Long
    )
}
