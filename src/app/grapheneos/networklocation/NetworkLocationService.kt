package app.grapheneos.networklocation

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.ext.settings.NetworkLocationSettings
import android.location.provider.ProviderRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager

/**
 * The network location service.
 */
class NetworkLocationService : Service() {
    private var provider: NetworkLocationProvider? = null
    private var networkLocationSettingObserver: Any? = null
    private var networkLocationSettingValue = {
        val networkLocationSettingValue = networkLocationSetting.get(this)
        val isAllowed =
            networkLocationSettingValue != NetworkLocationSettings.NETWORK_LOCATION_DISABLED
        if (provider?.isAllowed != isAllowed) {
            provider?.isAllowed = isAllowed
            if (provider?.isAllowed == false) {
                provider?.onSetRequest(ProviderRequest.EMPTY_REQUEST)
            }
        }
        networkLocationSettingValue
    }

    override fun onCreate() {
        if (!applicationContext.getSystemService(UserManager::class.java)!!.isSystemUser) {
            applicationContext.packageManager.setApplicationEnabledSetting(
                applicationContext.packageName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0
            )
        }
        provider = NetworkLocationProvider(
            context = this,
            networkLocationSettingValue = networkLocationSettingValue
        )
        networkLocationSettingObserver = networkLocationSetting.registerObserver(
            this,
            Handler(Looper.getMainLooper())
        ) {
            networkLocationSettingValue()
        }
        networkLocationSettingValue()
    }

    override fun onBind(intent: Intent?): IBinder? {
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
