package app.grapheneos.networklocation

import android.util.Log
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

inline fun verboseLog(tag: String, msg: () -> String) {
    if (Log.isLoggable(tag, Log.VERBOSE)) {
        Log.v(tag, msg.invoke())
    }
}

/**
 * Implements exponential back off math. It's up to the consumer of this class to call the
 * `advance()` method for `currentDuration` to get advanced.
 *
 * `currentDuration` is initially equal to `initialDuration`.
 */
class ExponentialBackOff(
    val initialDuration: Duration = 1.seconds,
    val exponent: Float = 1.5F,
    val maximumDuration: Duration = 30.seconds
) {
    private var advanceCount: Int = 0
    private var _currentDuration: Duration = initialDuration
    val currentDuration
        get() = _currentDuration

    fun advance() {
        if (currentDuration < maximumDuration) {
            advanceCount += 1
            setCurrentDuration(
                (initialDuration.inWholeMilliseconds * exponent.pow(advanceCount))
                    .toLong().milliseconds.coerceAtMost(maximumDuration)
            )
        }
    }

    fun reset() {
        advanceCount = 0
        setCurrentDuration(initialDuration)
    }

    private fun setCurrentDuration(newDuration: Duration) {
        _currentDuration = newDuration
    }
}
