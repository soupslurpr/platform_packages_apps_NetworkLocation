package app.grapheneos.networklocation

import android.app.Service
import android.content.Intent
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import android.location.provider.ProviderRequest
import android.os.IBinder
import android.util.Log
import com.android.internal.os.BackgroundThread

private const val TAG = "NetworkLocationService"

class NetworkLocationService : Service() {
    private lateinit var locationProvider: LocationProviderImpl
    private lateinit var settingObserverToken: Any

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        locationProvider = LocationProviderImpl(applicationContext)
        settingObserverToken = NETWORK_LOCATION_SETTING.registerObserver(
                this, BackgroundThread.getHandler()) {
            locationProvider.updateIsAllowedState()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind: $intent")
        locationProvider.updateIsAllowedState()
        return locationProvider.binder
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        locationProvider.onSetRequest(ProviderRequest.EMPTY_REQUEST)
        NETWORK_LOCATION_SETTING.unregisterObserver(this, settingObserverToken)
    }
}
