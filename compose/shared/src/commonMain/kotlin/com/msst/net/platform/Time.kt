package com.msst.net.platform

/** Wall-clock time, milliseconds since the epoch. */
expect fun nowMs(): Long

/** Local date and time, e.g. `2026-10-19 13:05:12`. */
expect fun formatDateTime(epochMs: Long): String

/** Local time of day, e.g. `13:05:12`. */
expect fun formatTime(epochMs: Long): String
