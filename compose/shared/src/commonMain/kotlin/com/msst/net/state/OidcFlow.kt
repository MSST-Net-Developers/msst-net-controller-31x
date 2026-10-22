package com.msst.net.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msst.net.model.AuthPollResult
import com.msst.net.model.AuthStartResult
import com.msst.net.model.Req
import com.msst.net.platform.ControllerBackend
import com.msst.net.platform.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MSST-Net access authentication (OIDC device flow, 3.0.x "接入认证").  Success
 * is only shown once Core's Health reports a valid authentication — a
 * finished poll means Core stored *a* result, not that it is valid.
 */
class OidcFlow(
    private val backend: ControllerBackend,
    private val store: ControllerStore,
    private val scope: CoroutineScope,
    private val openUrl: (String) -> Unit,
    private val verifyTimeoutMs: Long = 15_000,
) {
    enum class Status { Idle, Starting, Pending, Verifying, Authorized, Error }

    var status by mutableStateOf(Status.Idle)
        private set
    var userCode by mutableStateOf("")
        private set
    var verificationUri by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set

    private var job: Job? = null

    fun start() {
        job?.cancel()
        status = Status.Starting
        error = ""
        job = scope.launch {
            val started = attempt { backend.query<AuthStartResult>(Req.authStart()) }
            val res = started.getOrElse { return@launch fail(started.errorMessage!!) }
            userCode = res.userCode
            verificationUri = res.verificationUri
            status = Status.Pending
            if (res.verificationUri.isNotEmpty()) runCatching { openUrl(res.verificationUri) }

            var interval = (res.interval ?: 5).coerceAtLeast(1)
            while (true) {
                delay(interval * 1000L)
                val polled = attempt { backend.query<AuthPollResult>(Req.authPoll(res.sessionId)) }
                val p = polled.getOrElse { return@launch fail(polled.errorMessage!!) }
                if (!p.waiting) break
                if (p.status == "slow_down") interval += 5
            }

            // Core stored the result; wait for Core to confirm via Health.
            status = Status.Verifying
            repeat((verifyTimeoutMs / VERIFY_INTERVAL_MS).toInt()) {
                store.refresh()
                if (store.view.value?.state?.health?.isAuthenticated == true) {
                    status = Status.Authorized
                    return@launch
                }
                delay(VERIFY_INTERVAL_MS)
            }
            fail("认证页面已完成，但客户端核心尚未确认认证结果。请稍后查看首页状态，或重试。")
        }
    }

    private fun fail(message: String) {
        status = Status.Error
        error = message
    }

    private companion object {
        const val VERIFY_INTERVAL_MS = 500L
    }

    fun cancel() {
        job?.cancel()
        job = null
        status = Status.Idle
    }
}
