package app.grapheneos.networklocation

import android.app.Service
import android.content.Intent
import android.ext.settings.NetworkLocationSettings
import android.location.provider.ProviderRequest
import android.os.Handler
import android.os.IBinder
import kotlin.properties.Delegates

/**
 * The network location service.
 */
class NetworkLocationService : Service() {
    private var provider: NetworkLocationProvider? = null
    private var networkLocationSettingObserver: Any? = null
    private var networkLocationSettingValue by Delegates.notNull<Int>()

    override fun onCreate() {
        super.onCreate()
        networkLocationSettingValue = networkLocationSetting.get(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (provider == null) {
            provider = NetworkLocationProvider(
                context = this,
                networkLocationSettingValue = { networkLocationSettingValue }
            )
        }
        if (networkLocationSettingObserver == null) {
            networkLocationSettingObserver =
                networkLocationSetting.registerObserver(
                    this,
                    Handler(false)
                ) {
                    networkLocationSettingValue = it.get(this)
                    provider?.isAllowed =
                        networkLocationSettingValue != NetworkLocationSettings.NETWORK_LOCATION_DISABLED
                    if (provider?.isAllowed == false) {
                        provider?.onSetRequest(ProviderRequest.EMPTY_REQUEST)
                    }
                }
        }

        return provider?.binder
    }

    override fun onDestroy() {
        super.onDestroy()
        provider?.onSetRequest(ProviderRequest.EMPTY_REQUEST)
        provider = null
        networkLocationSettingObserver?.let {
            networkLocationSetting.unregisterObserver(this, it)
        }
        networkLocationSettingObserver = null
    }

    companion object {
        private val networkLocationSetting = NetworkLocationSettings.NETWORK_LOCATION_SETTING
    }
}
