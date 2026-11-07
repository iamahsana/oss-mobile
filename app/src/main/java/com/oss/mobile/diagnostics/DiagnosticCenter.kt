package com.oss.mobile.diagnostics

import java.util.ArrayDeque
import java.util.UUID

enum class DiagnosticCategory {
    AUDIO, VIDEO, STREAM, NETWORK, SYSTEM, APP, USER_ACTION
}

enum class DiagnosticSeverity {
    INFO, WARNING, ERROR, FATAL
}

data class DiagnosticEvent(
    val eventId: String,
    val elapsedRealtimeMs: Long,
    val sessionId: String?,
    val category: DiagnosticCategory,
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val attributes: Map<String, String>
)

data class DiagnosticSession(
    val sessionId: String,
    val startedAtElapsedMs: Long,
    val label: String,
    val endedAtElapsedMs: Long? = null,
    val outcome: String? = null
)

data class DiagnosticSnapshot(
    val session: DiagnosticSession?,
    val events: List<DiagnosticEvent>,
    val droppedEventCount: Long
)

/**
 * Bounded, in-memory diagnostics journal shared by OSS subsystems.
 *
 * This class is intentionally independent of Android UI, file I/O and networking.
 * It never receives or stores media payloads. Export is handled by a separate layer.
 */
class DiagnosticCenter(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
    }

    private val lock = Any()
    private val events = ArrayDeque<DiagnosticEvent>()
    private var activeSession: DiagnosticSession? = null
    private var lastSession: DiagnosticSession? = null
    private var droppedEvents: Long = 0

    fun beginSession(label: String): DiagnosticSession = synchronized(lock) {
        val session = DiagnosticSession(
            sessionId = UUID.randomUUID().toString(),
            startedAtElapsedMs = clock(),
            label = redact(label)
        )
        activeSession = session
        lastSession = null
        appendLocked(
            category = DiagnosticCategory.APP,
            severity = DiagnosticSeverity.INFO,
            code = "SESSION_STARTED",
            message = "Diagnostic session started",
            attributes = mapOf("label" to session.label)
        )
        session
    }

    fun endSession(outcome: String? = null) = synchronized(lock) {
        val session = activeSession ?: return@synchronized
        val safeOutcome = outcome?.let(::redact)
        appendLocked(
            category = DiagnosticCategory.APP,
            severity = DiagnosticSeverity.INFO,
            code = "SESSION_ENDED",
            message = "Diagnostic session ended",
            attributes = safeOutcome?.let { mapOf("outcome" to it) } ?: emptyMap()
        )
        lastSession = session.copy(endedAtElapsedMs = clock(), outcome = safeOutcome)
        activeSession = null
    }

    fun record(
        category: DiagnosticCategory,
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        attributes: Map<String, String> = emptyMap()
    ) = synchronized(lock) {
        val safeAttributes = attributes
            .filterKeys { it.matches(ATTRIBUTE_KEY) }
            .mapValues { (_, value) -> redact(value).take(MAX_ATTRIBUTE_LENGTH) }
        appendLocked(
            category = category,
            severity = severity,
            code = code.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64),
            message = redact(message).take(MAX_MESSAGE_LENGTH),
            attributes = safeAttributes
        )
    }

    fun snapshot(): DiagnosticSnapshot = synchronized(lock) {
        DiagnosticSnapshot(
            session = activeSession ?: lastSession,
            events = events.toList(),
            droppedEventCount = droppedEvents
        )
    }

    fun clear() = synchronized(lock) {
        events.clear()
        activeSession = null
        lastSession = null
        droppedEvents = 0
    }

    private fun appendLocked(
        category: DiagnosticCategory,
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        attributes: Map<String, String>
    ) {
        if (events.size >= maxEvents) {
            val lowSeverity = events.firstOrNull {
                it.severity == DiagnosticSeverity.INFO || it.severity == DiagnosticSeverity.WARNING
            }
            if (lowSeverity != null) {
                events.remove(lowSeverity)
            } else {
                events.removeFirst()
            }
            droppedEvents++
        }
        events.addLast(
            DiagnosticEvent(
                eventId = UUID.randomUUID().toString(),
                elapsedRealtimeMs = clock(),
                sessionId = activeSession?.sessionId,
                category = category,
                severity = severity,
                code = code.ifBlank { "UNSPECIFIED" },
                message = message,
                attributes = attributes.toMap()
            )
        )
    }

    private fun redact(input: String): String {
        var safe = input
        safe = RTMP_URL.replace(safe, "[REDACTED_RTMP_URL]")
        safe = SECRET_ASSIGNMENT.replace(safe) { match -> "${match.groupValues[1]}=[REDACTED]" }
        safe = BEARER_TOKEN.replace(safe, "Bearer [REDACTED]")
        safe = EMAIL.replace(safe, "[REDACTED_EMAIL]")
        safe = ABSOLUTE_PATH.replace(safe, "[REDACTED_PATH]")
        return safe
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 2_000
        private const val MAX_MESSAGE_LENGTH = 500
        private const val MAX_ATTRIBUTE_LENGTH = 256
        private val ATTRIBUTE_KEY = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
        private val RTMP_URL = Regex("(?i)rtmps?://[^\\s]+")
        private val SECRET_ASSIGNMENT = Regex(
            "(?i)(stream[_ -]?key|token|password|passwd|authorization|api[_ -]?key)(\\s*[:=]\\s*)[^\\s,;]+"
        )
        private val BEARER_TOKEN = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val ABSOLUTE_PATH = Regex("(?<![A-Za-z0-9])/(?:data|storage|sdcard|home)/[^\\s,;]+")
    }
}
