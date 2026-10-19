package com.msst.net.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun nowMs(): Long = System.currentTimeMillis()

actual fun formatDateTime(epochMs: Long): String =
    dateTime.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

actual fun formatTime(epochMs: Long): String =
    time.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
