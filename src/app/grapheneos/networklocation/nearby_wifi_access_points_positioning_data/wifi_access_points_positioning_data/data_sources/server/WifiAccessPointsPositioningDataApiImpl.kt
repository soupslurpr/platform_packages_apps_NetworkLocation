package app.grapheneos.networklocation.nearby_wifi_access_points_positioning_data.wifi_access_points_positioning_data.data_sources.server

import android.ext.settings.NetworkLocationSettings
import android.util.Log
import app.grapheneos.networklocation.wifi_access_points_positioning_data.data_sources.server.AppleWps
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class WifiAccessPointsPositioningDataApiImpl(
    private val networkLocationServerSetting: () -> Int
) : WifiAccessPointsPositioningDataApi {
    override fun fetchWifiAccessPointsPositioningData(wifiAccessPointsBssid: List<String>): AppleWps.AppleWifiAccessPointPositioningDataApiModel? {
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
                        return null
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
                        AppleWps.AppleWifiAccessPointPositioningDataApiModel.newBuilder()
                            .addAllAccessPoints(
                                wifiAccessPointsBssid.map { bssid ->
                                    AppleWps.AccessPoint.newBuilder().setBssid(bssid).build()
                                }
                            )
                            .build()

                    outputStream.write(header)
                    body.writeDelimitedTo(outputStream)
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    connection.inputStream.use { inputStream ->
                        inputStream.skip(10)
                        val successfulResponse =
                            AppleWps.AppleWifiAccessPointPositioningDataApiModel.parseFrom(
                                inputStream
                            )

                        return successfulResponse
                    }
                } else {
                    throw RuntimeException("Response code is $responseCode, not 200 (OK)")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            when (e) {
                is RuntimeException, is IOException -> Log.e(
                    TAG,
                    "Failed to fetch Wi-Fi access points positioning data",
                    e
                )

                else -> throw e
            }
        }
        return null
    }

    companion object {
        const val TAG: String = "WifiAccessPointsPositioningDataApiImpl"
    }
}

private fun Short.toBeBytes(): ByteArray {
    return byteArrayOf(
        ((this.toInt() shr 8) and 0xFF).toByte(), (this.toInt() and 0xFF).toByte()
    )
}