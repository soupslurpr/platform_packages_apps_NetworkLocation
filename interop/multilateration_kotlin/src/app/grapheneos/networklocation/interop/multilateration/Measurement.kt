package app.grapheneos.networklocation.interop.multilateration

data class Measurement(
    var position: Position,
    var distance: Double,
    var realZ: Boolean,
)
