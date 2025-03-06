package app.grapheneos.networklocation.interop.multilateration

data class Position(
    var x: Double,
    var y: Double,
    var z: Double?,
    var xyVariance: Double,
    var zVariance: Double?,
)
