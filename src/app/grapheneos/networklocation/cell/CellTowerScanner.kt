package app.grapheneos.networklocation.cell

import android.content.Context
import android.os.WorkSource
import android.telephony.CellInfo
import android.telephony.NetworkScan
import android.telephony.TelephonyManager
import android.telephony.TelephonyScanManager
import android.util.SparseArray

private const val TAG = "CellTowerScanner"

class CellTowerScanner(private val context: Context) {

    @Throws(CellScannerUnavailableException::class, CellScanFailedException::class)
    suspend fun scan(workSource: WorkSource): List<CellInfo> {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            ?: throw CellScannerUnavailableException("telephonyManager is null")

        // check that radio is available
        // TODO: make sure to also handle exception
        telephonyManager.serviceState

        val telephonyScanManager = context.getSystemService(TelephonyScanManager::class.java)
            ?: throw CellScannerUnavailableException("telephonyScanManager is null")
    }
}

class CellScannerUnavailableException(msg: String) : Exception(msg) {
    override fun toString() = "CellScannerUnavailableException: $message"
}

class CellScanFailedException(
    /** @see android.telephony.NetworkScan.ScanErrorCode */
    val error: Int
) : Exception() {
    override fun toString(): String {
        return "CellScanFailedException{error: ${errorStrings.get(error) ?: error.toString()}}"
    }

    companion object {
        private val errorStrings: SparseArray<String> by lazy {
            val res = SparseArray<String>()
            NetworkScan::class.java.declaredFields.forEach {
                val name = it.name
                if (name.startsWith("ERROR_") || name == "SUCCESS") {
                    if (it.type == Int::class.javaPrimitiveType) {
                        res.put(it.getInt(null), name)
                    }
                }
            }
            res
        }
    }
}