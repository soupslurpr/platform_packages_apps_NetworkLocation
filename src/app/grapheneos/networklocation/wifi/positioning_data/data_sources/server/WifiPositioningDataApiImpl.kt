package app.grapheneos.networklocation.wifi.positioning_data.data_sources.server

import android.ext.settings.NetworkLocationSettings
import android.util.Log
import app.grapheneos.networklocation.misc.RustyResult
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class WifiPositioningDataApiImpl(
    private val networkLocationServerSetting: () -> Int
) : WifiPositioningDataApi {
    override fun fetchPositioningData(wifiAccessPointsBssid: List<String>): RustyResult<AppleWps.WifiPositioningDataApiModel, WifiPositioningDataApi.FetchPositioningDataError> {
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
                        return RustyResult.Err(WifiPositioningDataApi.FetchPositioningDataError.Unavailable)
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
                        AppleWps.WifiPositioningDataApiModel.newBuilder()
                            .addAllAccessPoints(
                                wifiAccessPointsBssid.map { bssid ->
                                    AppleWps.AccessPoint.newBuilder().setBssid(bssid).build()
                                }
                            )
                            .setNumberOfResults(wifiAccessPointsBssid.size)
                            .build()

                    outputStream.write(header)
                    body.writeDelimitedTo(outputStream)
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    connection.inputStream.use { inputStream ->
                        inputStream.skip(10)
                        val successfulResponse =
                            AppleWps.WifiPositioningDataApiModel.parseFrom(
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
                            "Failed to fetch Wi-Fi access points' positioning data",
                            e
                        )
                    }
                    return RustyResult.Err(WifiPositioningDataApi.FetchPositioningDataError.Failure)
                }

                else -> throw e
            }
        }
    }

    companion object {
        const val TAG: String = "WifiPositioningDataApiImpl"
    }
}

private fun Short.toBeBytes(): ByteArray {
    return byteArrayOf(
        ((this.toInt() shr 8) and 0xFF).toByte(), (this.toInt() and 0xFF).toByte()
    )
}