package com.msst.net.state

import com.msst.net.model.ControllerView
import com.msst.net.model.UiNotice
import com.msst.net.platform.AccessRequestNotifier
import com.msst.net.platform.ControllerBackend
import com.msst.net.platform.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The UI's single source of Core state: the Controller view, re-read
 * whenever the backend says it changed.  Screens read from here and send
 * commands through the backend; they never keep their own copy of Core
 * state.  One instance per process, independent of screens, so navigating
 * never reconnects.  [scope] must be confined to the main thread.
 */
class ControllerStore(
    private val backend: ControllerBackend,
    private val scope: CoroutineScope,
    private val notifier: AccessRequestNotifier? = null,
    private val fallbackPollMs: Long = FALLBACK_POLL_MS,
) {
    private val _view = MutableStateFlow<ControllerView?>(null)
    val view: StateFlow<ControllerView?> = _view.asStateFlow()

    /** Error reading the view from the backend itself (e.g. WebUI unreachable). */
    private val _backendError = MutableStateFlow<String?>(null)
    val backendError: StateFlow<String?> = _backendError.asStateFlow()

    /** A user-requested retry is being submitted. */
    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    /** Every notice after the view was re-read (e.g. to reload an open panel). */
    private val _changes = MutableSharedFlow<UiNotice>(extraBufferCapacity = 16)
    val changes: SharedFlow<UiNotice> = _changes.asSharedFlow()

    private var seq = 0L
    private var applied = 0L
    private var job: Job? = null

    /** Start listening (idempotent). */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { refresh() }
            launch {
                backend.notices.collect { n ->
                    if (n is UiNotice.AccessRequest) notifier?.notify(n)
                    if (n == UiNotice.Changed) refresh()
                    _changes.tryEmit(n)
                }
            }
            // Safety net if a notification is missed (e.g. WebSocket reconnecting).
            launch {
                while (isActive) {
                    delay(fallbackPollMs)
                    refresh()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Re-read the view; responses older than one already applied are dropped. */
    suspend fun refresh() {
        val mine = ++seq
        try {
            val v = backend.view()
            if (mine < applied) return
            applied = mine
            _view.value = v
            _backendError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine < applied) return
            _backendError.value = e.userMessage()
        }
    }

    /** User-requested retry: reconnect now if not connected, otherwise
     *  re-read everything from Core; then re-read the view. */
    fun retry() {
        if (_retrying.value) return
        _retrying.value = true
        scope.launch {
            try {
                backend.retry()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The backend itself is unreachable; refresh() reports it.
            } finally {
                _retrying.value = false
            }
            refresh()
        }
    }

    companion object {
        const val FALLBACK_POLL_MS = 5_000L
    }
}
