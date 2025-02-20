package app.grapheneos.networklocation.cell.positioning_data.data_sources.server

import android.ext.settings.NetworkLocationSettings
import android.util.Log
import app.grapheneos.networklocation.cell.positioning_data.CellTower
import app.grapheneos.networklocation.misc.RustyResult
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class CellPositioningDataApiImpl(
    private val networkLocationServerSetting: () -> Int
) : CellPositioningDataApi {
    override fun fetchPositioningData(identifiers: List<CellTower.Identifier>): RustyResult<AppleCps.CellPositioningDataApiModel, CellPositioningDataApi.FetchPositioningDataError> {
        try {
            val url = URL(
                when (networkLocationServerSetting()) {
                    NetworkLocationSettings.NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY -> {
                        "https://gs-loc.apple.grapheneos.org/clls/wloc"
                    }

                    NetworkLocationSettings.NETWORK_LOCATION_SERVER_APPLE -> {
                        "https://gs-loc.apple.com/clls/wloc"
                    }

                    else -> {
                        return RustyResult.Err(CellPositioningDataApi.FetchPositioningDataError.Unavailable)
                    }
                }
            )
            val connection = url.openConnection() as HttpsURLConnection

            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty(
                    "Content-Type", "application/x-www-form-urlencoded"
                )
                connection.doOutput = true

                connection.outputStream.use { outputStream ->
                    var header = byteArrayOf()

                    header += 1.toShort().toBeBytes()
                    header += 0.toShort().toBeBytes()
                    header += 0.toShort().toBeBytes()
                    header += 0.toShort().toBeBytes()
                    header += 0.toShort().toBeBytes()
                    header += 1.toShort().toBeBytes()
                    header += 0.toShort().toBeBytes()
                    header += 0.toByte()

                    val body =
                        AppleCps.CellPositioningDataApiModel.newBuilder()
                            .addAllCellTowerRequest(
                                identifiers.map {
                                    val cellTower = AppleCps.CellTower.newBuilder()

                                    cellTower.mcc = it.mcc.toLong()
                                    cellTower.mnc = it.mnc.toLong()
                                    cellTower.cellId = it.cellId.toLong()
                                    cellTower.tacId = it.tacId.toLong()

                                    cellTower.build()
                                }
                            )
                            .setNumberOfResultsModifier(1)
                            .build()

                    outputStream.write(header)
                    body.writeDelimitedTo(outputStream)
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    connection.inputStream.use { inputStream ->
                        inputStream.skip(10)
                        val successfulResponse =
                            AppleCps.CellPositioningDataApiModel.parseFrom(
                                inputStream
                            )

                        return RustyResult.Ok(successfulResponse)
                    }
                } else {
                    throw RuntimeException("Response code is $responseCode, not 200 (OK)")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            when (e) {
                is RuntimeException, is IOException -> {
                    if (Log.isLoggable(TAG, Log.ERROR)) {
                        Log.e(
                            TAG,
                            "Failed to fetch cell towers' positioning data",
                            e
                        )
                    }
                    return RustyResult.Err(CellPositioningDataApi.FetchPositioningDataError.Failure)
                }

                else -> throw e
            }
        }
    }

    companion object {
        const val TAG: String = "CellPositioningDataApiImpl"
    }
}

private fun Short.toBeBytes(): ByteArray {
    return byteArrayOf(
        ((this.toInt() shr 8) and 0xFF).toByte(), (this.toInt() and 0xFF).toByte()
    )
}