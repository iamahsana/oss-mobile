package com.oss.mobile.diagnostics

import android.os.SystemClock

/**
 * Process-wide diagnostics entry point shared by streaming subsystems.
 * The journal is in-memory and bounded; it never stores media payloads.
 */
object OssDiagnostics {
    val center: DiagnosticCenter = DiagnosticCenter(
        maxEvents = DiagnosticCenter.DEFAULT_MAX_EVENTS,
        clock = { SystemClock.elapsedRealtime() }
    )
}
