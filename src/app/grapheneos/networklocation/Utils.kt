package app.grapheneos.networklocation

import android.util.Log

inline fun verboseLog(tag: String, msg: () -> String) {
    if (Log.isLoggable(tag, Log.VERBOSE)) {
        Log.v(tag, msg.invoke())
    }
}
