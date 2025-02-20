package app.grapheneos.networklocation.cell

import app.grapheneos.networklocation.PositioningData
import java.io.IOException

interface CellPositioningService {
    @Throws(IOException::class)
    fun fetchNearbyTowerPositioningData(
        towerId: TowerId,
        additionalSuccessfulResultsHint: Int
    ): List<CellTowerPositioningData>
}

data class TowerId(
    val mcc: UShort,
    val mnc: UShort,
    val cellId: UInt,
    val tacId: UInt
)

class CellTowerPositioningData(
    val towerId: TowerId,
    val positioningData: PositioningData?
) {
    override fun toString(): String {
        val pd = positioningData
        return if (pd == null) "$towerId (no positioning data)" else "${towerId}_$pd"
    }
}
