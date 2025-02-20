package app.grapheneos.networklocation.cell

import android.app.AppGlobals
import android.content.Context
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_DISABLED
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_APPLE
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import app.grapheneos.networklocation.PositioningData
import java.io.IOException
import java.net.URL

private const val TAG = "AppleCps"

class AppleCellPositioningService : CellPositioningService {

    @Throws(IOException::class)
    override fun fetchNearbyTowerPositioningData(
        towerId: TowerId,
        additionalSuccessfulResultsHint: Int
    ): List<CellTowerPositioningData> {
        val response = fetchInner(towerId, additionalSuccessfulResultsHint)
//        verboseLog(TAG) {
//            "request tower $towerId, response: " + response.cellTowerList.map {
//                it.towerId + "_" + (convertPositioningData(it.positioningData) ?: "(no positioning data)")
//            }
//        }

        val result = mutableListOf<CellTowerPositioningData>()

//        for (tower in response.cellTowerList) {
//            val towerId =
//        }
    }

    @Throws(IOException::class)
    private fun fetchInner(
        towerId: TowerId,
        maxResultsHint: Int
    ): AppleCpsProtos.Response {
        TODO()
    }

    private fun convertPositioningData(pd: AppleCpsProtos.PositioningData): PositioningData? {
        if (pd.latitude == -18000000000) {
            return null
        }
        val latitude = pd.latitude * 0.000_000_01;
        val longitude = pd.longitude * 0.000_000_01
        return PositioningData(latitude, longitude, pd.accuracyMeters, null, null)
    }

    @Throws(IOException::class)
    private fun getServerUrl(): URL {
        val context: Context = AppGlobals.getInitialApplication()
        val setting = NETWORK_LOCATION_SETTING.get(context)
        val urlString = when (setting) {
            NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY ->
                "https://gs-loc.apple.grapheneos.org/clls/wloc"

            NETWORK_LOCATION_SERVER_APPLE ->
                "https://gs-loc.apple.com/clls/wloc"

            NETWORK_LOCATION_DISABLED ->
                // network location can be disabled by the user at any point
                throw IOException("network location setting became disabled")

            else ->
                throw IllegalStateException("unexpected URL setting: $setting")
        }
        return URL(urlString)
    }
}