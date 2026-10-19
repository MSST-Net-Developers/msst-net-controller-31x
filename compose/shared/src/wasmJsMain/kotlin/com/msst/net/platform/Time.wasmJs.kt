@file:OptIn(ExperimentalWasmJsInterop::class)

package com.msst.net.platform

private fun jsNow(): Double = js("Date.now()")

private fun jsLocalParts(ms: Double): JsString = js(
    """(() => { const d = new Date(ms); const p = (n) => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' +
               p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()); })()"""
)

actual fun nowMs(): Long = jsNow().toLong()

actual fun formatDateTime(epochMs: Long): String = jsLocalParts(epochMs.toDouble()).toString()

actual fun formatTime(epochMs: Long): String = formatDateTime(epochMs).substringAfter(' ')
