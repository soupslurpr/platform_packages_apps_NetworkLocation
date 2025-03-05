package app.grapheneos.networklocation.interop.multilateration

object Multilateration {
    private external fun multilateration(measurements: Array<Measurement>): Position

    init {
        System.loadLibrary("multilateration_native")
    }

    fun main(measurements: Array<Measurement>): Position {
        return multilateration(measurements)
    }
}
