package com.oss.mobile.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCenterTest {

    @Test
    fun keepsEventBufferBoundedAndCountsDrops() {
        var now = 0L
        val center = DiagnosticCenter(maxEvents = 3, clock = { ++now })
        repeat(5) { index ->
            center.record(
                DiagnosticCategory.APP,
                DiagnosticSeverity.INFO,
                "EVENT_$index",
                "event $index"
            )
        }
        val snapshot = center.snapshot()
        assertEquals(3, snapshot.events.size)
        assertEquals(2L, snapshot.droppedEventCount)
        assertEquals("EVENT_2", snapshot.events.first().code)
    }

    @Test
    fun preservesErrorsAheadOfInformationalEvents() {
        var now = 0L
        val center = DiagnosticCenter(maxEvents = 2, clock = { ++now })
        center.record(DiagnosticCategory.APP, DiagnosticSeverity.ERROR, "ERR", "important")
        center.record(DiagnosticCategory.APP, DiagnosticSeverity.INFO, "INFO", "routine")
        center.record(DiagnosticCategory.APP, DiagnosticSeverity.FATAL, "FATAL", "critical")
        val events = center.snapshot().events
        assertEquals(2, events.size)
        assertTrue(events.any { it.code == "ERR" })
        assertTrue(events.any { it.code == "FATAL" })
    }

    @Test
    fun redactsCredentialsAndPersonalData() {
        val center = DiagnosticCenter()
        center.record(
            DiagnosticCategory.STREAM,
            DiagnosticSeverity.ERROR,
            "CONNECT_FAILED",
            "Failed rtmp://example.test/live/secret-key token=abc123 user@example.com"
        )
        val message = center.snapshot().events.single().message
        assertFalse(message.contains("secret-key"))
        assertFalse(message.contains("abc123"))
        assertFalse(message.contains("user@example.com"))
        assertTrue(message.contains("[REDACTED_RTMP_URL]"))
    }

    @Test
    fun associatesEventsWithActiveSession() {
        val center = DiagnosticCenter()
        val session = center.beginSession("live test")
        center.record(DiagnosticCategory.AUDIO, DiagnosticSeverity.WARNING, "AUDIO_WARN", "buffer warning")
        val snapshot = center.snapshot()
        assertNotNull(snapshot.session)
        assertEquals(session.sessionId, snapshot.events.last().sessionId)
        center.endSession("completed")
        val completed = center.snapshot().session
        assertNotNull(completed)
        assertEquals(session.sessionId, completed?.sessionId)
        assertEquals("completed", completed?.outcome)
        assertNotNull(completed?.endedAtElapsedMs)
    }
}
