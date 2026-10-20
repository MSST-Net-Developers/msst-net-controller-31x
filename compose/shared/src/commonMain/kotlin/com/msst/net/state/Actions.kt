package com.msst.net.state

import com.msst.net.platform.ApiException
import com.msst.net.platform.userMessage
import kotlinx.coroutines.CancellationException

/** Run [block]; a failure becomes its user-facing message.  Cancellation
 *  is not a failure and propagates. */
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** The user-facing message of a failed [attempt], or null on success. */
val Result<*>.errorMessage: String? get() = exceptionOrNull()?.userMessage()

/** Core's (or the Controller's) error code of a failed [attempt]. */
val Result<*>.errorCode: String? get() = (exceptionOrNull() as? ApiException)?.code

/** Progress of a form submission (`idle → submitting → success | error`). */
enum class Phase { Idle, Submitting, Success, Error }
