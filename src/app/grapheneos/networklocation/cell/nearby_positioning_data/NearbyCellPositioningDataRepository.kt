package app.grapheneos.networklocation.cell.nearby_positioning_data

import android.os.WorkSource
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellInfo
import android.util.Log
import app.grapheneos.networklocation.cell.nearby.NearbyCellRepository
import app.grapheneos.networklocation.cell.positioning_data.CellPositioningDataRepository
import app.grapheneos.networklocation.cell.positioning_data.CellTower
import app.grapheneos.networklocation.misc.RustyResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NearbyCellPositioningDataRepository(
    private val nearbyCellRepository: NearbyCellRepository,
    private val cellPositioningDataRepository: CellPositioningDataRepository
) {
    sealed class LatestNearbyCellPositioningDataError {
        data object Failure : LatestNearbyCellPositioningDataError()
        data object Unavailable : LatestNearbyCellPositioningDataError()
    }

    val latestNearbyCellPositioningData: Flow<RustyResult<List<NearbyCell>, LatestNearbyCellPositioningDataError>> =
        nearbyCellRepository.latestNearbyCell.map { result ->
            val convertedAndSortedByDbmResults = when (result) {
                is RustyResult.Err -> {
                    return@map when (result.error) {
                        NearbyCellRepository.LatestNearbyCellError.Failure -> RustyResult.Err(
                            LatestNearbyCellPositioningDataError.Failure
                        )

                        NearbyCellRepository.LatestNearbyCellError.Unavailable -> RustyResult.Err(
                            LatestNearbyCellPositioningDataError.Unavailable
                        )
                    }
                }

                is RustyResult.Ok -> result.value.sortedByDescending { it.cellSignalStrength.dbm }
                    .map {
                        val cellIdentity = it.cellIdentity
                        val nearbyCell = NearbyCell(
                            identifier = NearbyCell.Identifier(
                                mcc = if (cellIdentity.mccString != null) {
                                    cellIdentity.mccString.toUShort()
                                } else {
                                    null
                                },
                                mnc = if (cellIdentity.mncString != null) {
                                    cellIdentity.mncString.toUShort()
                                } else {
                                    null
                                },
                                cellId = run {
                                    val cellId = when (cellIdentity) {
                                        is CellIdentityLte -> {
                                            cellIdentity.ci
                                        }

                                        is CellIdentityGsm -> {
                                            cellIdentity.cid
                                        }

                                        else -> CellInfo.UNAVAILABLE
                                    }

                                    if (cellId != CellInfo.UNAVAILABLE) {
                                        cellId.toUInt()
                                    } else {
                                        null
                                    }
                                },
                                tacId = run {
                                    val tacId = when (cellIdentity) {
                                        is CellIdentityLte -> {
                                            cellIdentity.tac
                                        }

                                        is CellIdentityGsm -> {
                                            cellIdentity.lac
                                        }

                                        else -> CellInfo.UNAVAILABLE
                                    }

                                    if (tacId != CellInfo.UNAVAILABLE) {
                                        tacId.toUInt()
                                    } else {
                                        null
                                    }
                                },
                                earfcn = run {
                                    val earfcn = when (cellIdentity) {
                                        is CellIdentityLte -> {
                                            cellIdentity.earfcn
                                        }

                                        is CellIdentityGsm -> {
                                            cellIdentity.arfcn
                                        }

                                        else -> CellInfo.UNAVAILABLE
                                    }

                                    if (earfcn != CellInfo.UNAVAILABLE) {
                                        earfcn.toUInt()
                                    } else {
                                        null
                                    }
                                },
                                pci = run {
                                    val pci = when (cellIdentity) {
                                        is CellIdentityLte -> {
                                            cellIdentity.pci
                                        }

                                        is CellIdentityGsm -> {
                                            // TODO: try to manually make sure this is right
                                            cellIdentity.bsic
                                        }

                                        else -> CellInfo.UNAVAILABLE
                                    }

                                    if (pci != CellInfo.UNAVAILABLE) {
                                        pci.toUInt()
                                    } else {
                                        null
                                    }
                                }
                            ),
                            positioningData = null,
                            lastSeen = it.timestampMillis,
                        )


                        Pair(nearbyCell, it)
                    }
            }

            val nearbyCells: MutableList<NearbyCell> = mutableListOf()

            for (cellTower in convertedAndSortedByDbmResults.filter { it.first.identifier.mcc != null && it.first.identifier.mnc != null && it.first.identifier.cellId != null && it.first.identifier.tacId != null }) {
                // don't request more if we already have the top 5
                if (nearbyCells.size >= 5) {
                    continue
                }

                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(
                        TAG,
                        "Requested positioning data for unknown cell tower: $cellTower"
                    )
                }

                val positioningData =
                    cellPositioningDataRepository.fetchPositioningData(
                        listOf(
                            cellTower.first.identifier.let {
                                CellTower.Identifier(
                                    it.mcc!!,
                                    it.mnc!!,
                                    it.cellId!!,
                                    it.tacId!!,
                                    it.earfcn,
                                    it.pci
                                )
                            }
                        )
                    )

                when (positioningData) {
                    is RustyResult.Err -> return@map when (positioningData.error) {
                        CellPositioningDataRepository.FetchPositioningDataError.Failure -> RustyResult.Err(
                            LatestNearbyCellPositioningDataError.Failure
                        )

                        CellPositioningDataRepository.FetchPositioningDataError.Unavailable -> RustyResult.Err(
                            LatestNearbyCellPositioningDataError.Unavailable
                        )
                    }

                    is RustyResult.Ok -> {
                        if (positioningData.value.isNotEmpty()) {
                            positioningData.value.filter {
//                                nearbyCells.find { nearbyCell ->
//                                    nearbyCell.identifier == NearbyCell.Identifier(
//                                        it.identifier.mcc,
//                                        it.identifier.mnc,
//                                        it.identifier.cellId,
//                                        it.identifier.tacId,
//                                        it.identifier.earfcn,
//                                        it.identifier.pci
//                                    )
//                                }
                                nearbyCells.isEmpty() || nearbyCells.find { nearbyCell ->
                                    nearbyCell.identifier.mcc == it.identifier.mcc &&
                                            nearbyCell.identifier.mnc == it.identifier.mnc &&
                                            nearbyCell.identifier.cellId == it.identifier.cellId &&
                                            nearbyCell.identifier.tacId == it.identifier.tacId
                                } == null
                            }.mapNotNull {
                                val foundResult =
                                    convertedAndSortedByDbmResults.find { (nearbyCell, _) ->
                                        (nearbyCell.identifier.mcc == it.identifier.mcc &&
                                                nearbyCell.identifier.mnc == it.identifier.mnc &&
                                                nearbyCell.identifier.cellId == it.identifier.cellId &&
                                                nearbyCell.identifier.tacId == it.identifier.tacId) ||
                                                ((nearbyCell.identifier.mcc == null &&
                                                        nearbyCell.identifier.mnc == null &&
                                                        nearbyCell.identifier.cellId == null &&
                                                        nearbyCell.identifier.tacId == null) &&
                                                        (nearbyCell.identifier.earfcn == it.identifier.earfcn && nearbyCell.identifier.pci == it.identifier.pci))
                                    }

                                if (foundResult != null) {
                                    Pair(it, foundResult)
                                } else {
                                    null
                                }
                            }.forEach { foundResult ->
                                val foundResultIdentifier = foundResult.first.identifier
                                val foundResultPositioningData = foundResult.first.positioningData
                                val nearbyCell =
                                    cellTower.first.copy(
                                        identifier = NearbyCell.Identifier(
                                            foundResultIdentifier.mcc,
                                            foundResultIdentifier.mnc,
                                            foundResultIdentifier.cellId,
                                            foundResultIdentifier.tacId,
                                            foundResultIdentifier.earfcn,
                                            foundResultIdentifier.pci
                                        ),
                                        positioningData = if (foundResultPositioningData == null) {
                                            null
                                        } else {
                                            NearbyCell.PositioningData(
                                                latitude = foundResultPositioningData.latitude,
                                                longitude = foundResultPositioningData.longitude,
                                                accuracyMeters = foundResultPositioningData.accuracyMeters,
                                                // TODO: this isn't actually RSSI, might have to cast it
                                                //  to specific technology like CellSignalStrengthLte etc
                                                rssi = cellTower.second.cellSignalStrength.dbm
                                            )
                                        },
                                    )
                                if (foundResultPositioningData != null) {
                                    nearbyCells.add(nearbyCell)
                                }
                            }
                        }
                    }
                }
            }

            // TODO: remove this
            Log.d(TAG, "Done requesting positioning data: $nearbyCells")

            RustyResult.Ok(nearbyCells)
        }

    fun setWorkSource(workSource: WorkSource) {
        nearbyCellRepository.setWorkSource(workSource)
    }

    fun clearCaches() {

    }

    companion object {
        const val TAG = "NearbyCellPositioningDataRepository"
    }
}

data class NearbyCell(
    val identifier: Identifier,
    val positioningData: PositioningData?,
    /** timestamp in milliseconds (since boot) when this result was last seen. */
    val lastSeen: Long
) {
    data class Identifier(
        val mcc: UShort?,
        val mnc: UShort?,
        val cellId: UInt?,
        val tacId: UInt?,
        val earfcn: UInt?,
        val pci: UInt?
    )

    data class PositioningData(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Long,
        val rssi: Int
    )
}