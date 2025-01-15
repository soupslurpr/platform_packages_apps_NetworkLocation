package app.grapheneos.networklocation.misc

/**
 * Kotlin does not have good error handling. Let's kinda emulate Rust's approach instead.
 */
sealed class RustyResult<out T, out E> {
    data class Ok<out T>(val value: T) : RustyResult<T, Nothing>()
    data class Err<out E>(val error: E) : RustyResult<Nothing, E>()
}