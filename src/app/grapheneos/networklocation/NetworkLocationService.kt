package app.grapheneos.networklocation

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The network location service.
 */
class NetworkLocationService : Service() {
    private var mProvider: NetworkLocationProvider? = null

    override fun onBind(intent: Intent?): IBinder? {
        if (mProvider == null) {
            mProvider = NetworkLocationProvider(this)
        }

        return mProvider?.binder
    }

    override fun onDestroy() {
        super.onDestroy()
        mProvider?.stop()
        mProvider = null
    }
}
