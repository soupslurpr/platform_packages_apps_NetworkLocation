package app.grapheneos.networklocation

class PositioningData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Long,
    val altitudeMeters: Long?,
    val verticalAccuracyMeters: Long?,
) {
    override fun toString(): String {
        return StringBuilder().run {
            append('{'); append(latitude); append(','); append(longitude)
            append('±'); append(accuracyMeters); append('m')
            if (altitudeMeters != null) {
                append(" altitude:"); append(altitudeMeters)
                if (verticalAccuracyMeters != null) {
                    append('±'); append(verticalAccuracyMeters)
                }
                append('m')
            }
            append('}')
            toString()
        }
    }
}
