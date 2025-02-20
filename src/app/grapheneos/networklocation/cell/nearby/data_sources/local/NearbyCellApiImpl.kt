package app.grapheneos.networklocation.cell.nearby.data_sources.local

import android.os.WorkSource
import android.telephony.AccessNetworkConstants
import android.telephony.CellInfo
import android.telephony.NetworkScan
import android.telephony.NetworkScanRequest
import android.telephony.PhoneCapability
import android.telephony.RadioAccessSpecifier
import android.telephony.TelephonyManager
import android.telephony.TelephonyScanManager
import android.util.Log
import app.grapheneos.networklocation.misc.RustyResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executors

class NearbyCellApiImpl(
    private val telephonyManager: TelephonyManager,
    private val telephonyScanManager: TelephonyScanManager
) : NearbyCellApi {
    private lateinit var workSource: WorkSource

    override fun fetchNearbyCell(): Flow<RustyResult<List<CellInfo>, NearbyCellApi.LatestNearbyCellError>> =
        callbackFlow {
            val callback = object : TelephonyScanManager.NetworkScanCallback() {
                override fun onResults(cellInfo: List<CellInfo>) {
                    // TODO: remove this
                    Log.v(TAG, "cellInfo: $cellInfo")

                    trySend(RustyResult.Ok(cellInfo))
                    close()
                }

                override fun onError(error: Int) {
                    if (Log.isLoggable(TAG, Log.ERROR)) {
                        Log.e(TAG, "Error scanning cell for cell towers, error: $error")
                    }

                    when (error) {
                        NetworkScan.ERROR_MODEM_UNAVAILABLE -> {
                            trySend(RustyResult.Err(NearbyCellApi.LatestNearbyCellError.Unavailable))
                        }

                        else -> {
                            trySend(RustyResult.Err(NearbyCellApi.LatestNearbyCellError.Failure))
                        }
                    }

                    close()
                }
            }

            val networkScan = telephonyManager.requestNetworkScan(
                createNetworkScan(),
                Executors.newSingleThreadExecutor(),
                callback
            )

            awaitClose {
                networkScan.stopScan()
            }
        }

    /** Create network scan for allowed network types. */
    private fun createNetworkScan(): NetworkScanRequest {
        val allowedNetworkTypes = getAllowedNetworkTypes()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "createNetworkScan: allowedNetworkTypes = $allowedNetworkTypes")
        }
        val radioAccessSpecifiers = allowedNetworkTypes
            .map { RadioAccessSpecifier(it, null, null) }
            .toTypedArray()
        return NetworkScanRequest(
            NetworkScanRequest.SCAN_TYPE_ONE_SHOT,
            radioAccessSpecifiers,
            NetworkScanRequest.MIN_SEARCH_PERIODICITY_SEC, // one shot, not used
            300,
            true,
            1,
            null
        )
    }

    private fun getAllowedNetworkTypes(): List<Int> {
        val networkTypeBitmap3gpp: Long =
            telephonyManager.getAllowedNetworkTypesBitmask() and
                    TelephonyManager.NETWORK_STANDARDS_FAMILY_BITMASK_3GPP
        return buildList {
            // TODO: probably only support 4G and up, check behavior again but it seems it will
            //  only scan 2G if the user set their SIM to 3G... Seems 3G is shut down but 2G isn't yet

            // TODO: seems like the phone refuses to scan 4G towers if it doesn't have a SIM in it
            //  look into this more, but first try to add support for GSM (2G) to the cell tower
            //  positioning data source

            // TODO: good riddance, apparently 2G will be shut down sometime this year (2025)

            // If the allowed network types are unknown or if they are of the right class, scan for
            // them; otherwise, skip them to save scan time and prevent users from being shown
            // networks that they can't connect to.
//            if (networkTypeBitmap3gpp == 0L
//                || networkTypeBitmap3gpp and TelephonyManager.NETWORK_CLASS_BITMASK_2G != 0L
//            ) {
            // TODO: hm, seems we can't even ask to scan for 2G if we want. it only works if we have
            //  a SIM that's set to 3G. Seems then that SIMless people won't be able to use cell-
            //  tower-based location?
            add(AccessNetworkConstants.AccessNetworkType.GERAN)
//            }
//            if (networkTypeBitmap3gpp == 0L
//                || networkTypeBitmap3gpp and TelephonyManager.NETWORK_CLASS_BITMASK_3G != 0L
//            ) {
//                add(AccessNetworkConstants.AccessNetworkType.UTRAN)
//            }
//            if (networkTypeBitmap3gpp == 0L
//                || networkTypeBitmap3gpp and TelephonyManager.NETWORK_CLASS_BITMASK_4G != 0L
//            ) {
            add(AccessNetworkConstants.AccessNetworkType.EUTRAN)
//            }
            // TODO: test enabling 5G
            // If a device supports 5G stand-alone then the code below should be re-enabled; however
            // a device supporting only non-standalone mode cannot perform PLMN selection and camp
            // on a 5G network, which means that it shouldn't scan for 5G at the expense of battery
            // as part of the manual network selection process.
            //
//            if (networkTypeBitmap3gpp == 0L
//                || (networkTypeBitmap3gpp and TelephonyManager.NETWORK_CLASS_BITMASK_5G != 0L &&
//                        hasNrSaCapability())
//            ) {
//                add(AccessNetworkConstants.AccessNetworkType.NGRAN)
//                Log.d(TAG, "radioAccessSpecifiers add NGRAN.")
//            }
        }
    }

    private fun hasNrSaCapability(): Boolean {
        val phoneCapability = telephonyManager.getPhoneCapability()
        return PhoneCapability.DEVICE_NR_CAPABILITY_SA in phoneCapability.deviceNrCapabilities
    }

    override fun setWorkSource(workSource: WorkSource) {
        this@NearbyCellApiImpl.workSource = workSource
    }

    companion object {
        private const val TAG = "NearbyCellApiImpl"
    }
}