package app.grapheneos.networklocation

import android.app.Service
import android.content.Intent
import android.ext.settings.NetworkLocationSettings
import android.location.provider.ProviderRequest
import android.os.IBinder
import android.provider.Settings

/**
 * The network location service.
 */
class NetworkLocationService : Service() {
    private var mProvider: NetworkLocationProvider? = null

    override fun onBind(intent: Intent?): IBinder? {
        // TODO: remove debug println
        println("ONBIND called")

        if (mProvider == null) {
            mProvider = NetworkLocationProvider(
                context = this,
                networkLocationServerSetting = {
                    val networkLocationServerSetting = Settings.Global.getInt(
                        this.contentResolver,
                        Settings.Global.NETWORK_LOCATION
                    )

                    // TODO: verify this works
                    val isAllowed =
                        networkLocationServerSetting != NetworkLocationSettings.NETWORK_LOCATION_DISABLED
                    // TODO: remove debug println
                    println("networkLocationServerSetting: isAllowed: $isAllowed")
                    if (!isAllowed) {
                        stopSelf()
                    }

                    networkLocationServerSetting
                }
            )
        }

        return mProvider?.binder
    }

    override fun onDestroy() {
        super.onDestroy()
        mProvider?.onSetRequest(ProviderRequest.EMPTY_REQUEST)
        mProvider = null
    }
}
