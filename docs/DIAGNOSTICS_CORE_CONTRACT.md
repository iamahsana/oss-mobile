# OSS Diagnostics Core Contract

Status: initial implementation contract  
Branch: `feature/diagnostics-session-health`

## Purpose

Provide one app-wide diagnostics pipeline so users can export a single support report when OSS misbehaves. Diagnostics must reduce repeated manual testing and let maintainers correlate failures across device models and OSS versions.

## Design principles

- Diagnostics are observational: they must never control, restart, mute, or otherwise change streaming behavior.
- Collection is lightweight, bounded, and safe to run during a live session.
- No raw microphone audio, internal playback audio, camera frames, screen frames, stream keys, RTMP URLs containing credentials, chat, or personal content is collected.
- Reports are user-initiated exports. No automatic upload or background transmission.
- Sensitive values are redacted before serialization, not merely hidden in the UI.
- Every event includes a monotonic timestamp and a session-relative time where available.
- A diagnostics failure must never crash or block the stream.

## Architecture

```
Streaming / Audio / Video / Network / System modules
                    |
             Diagnostic API
                    |
        Bounded in-memory event store
                    |
          Session + health snapshots
                    |
       Redaction + report serializer
                    |
       User-triggered export (.zip)
          /                  \
    report.json          timeline.log
```

## Core data model

### DiagnosticEvent

Required fields:

- `eventId`: unique event identifier
- `timestampElapsedMs`: monotonic elapsed-realtime timestamp
- `sessionId`: nullable session identifier
- `category`: AUDIO, VIDEO, STREAM, NETWORK, SYSTEM, APP, or USER_ACTION
- `severity`: INFO, WARNING, ERROR, or FATAL
- `code`: stable machine-readable event code
- `message`: short human-readable summary
- `attributes`: allowlisted scalar metadata only

Never place secrets or arbitrary user-provided text in attributes.

### DiagnosticSession

- Unique random session ID and report ID
- OSS version/build and Android device metadata
- Start/end monotonic timestamps and session outcome
- The most recently completed session remains attached to the report after streaming stops
- Selected capture modes and encoder configuration (non-secret fields only)

### Health snapshots

Collect at a modest interval and on important state changes:

- Audio: source state, sample rate, channel count, RMS/peak, mute/enabled state, buffer underrun/overflow counters when available.
- Video: capture/encoder FPS, resolution, dropped-frame counters when available. FPS and dropped-frame counters remain pending until a stable supported RootEncoder callback/counter is verified; unavailable metrics must not be represented as zero.
- Stream: RTMP connection state, bitrate, reconnect count, connection duration.
- System: app memory, available memory, CPU/load and thermal status only where supported without extra permissions.
- Android thermal status uses platform-defined levels: NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, and SHUTDOWN. SEVERE and above are surfaced as warnings.

Unavailable metrics must be represented as unavailable, never fabricated as zero.

## Storage and performance

- Keep a bounded in-memory ring buffer; initial target: at most 2,000 events per session.
- Keep only the latest health samples needed for troubleshooting; do not retain every frame.
- Do not perform file, compression, or network I/O on audio/video callbacks.
- Serialize and compress on a background executor after the user requests export.
- If the event buffer fills, drop oldest low-severity events first and preserve recent WARNING/ERROR/FATAL events.
- Diagnostics collection must be disableable and must not materially affect stream latency.

## Export format

User action: Diagnostics → Export report.

Initial archive layout:

- `report.json`: schema version, report ID, device/app metadata, session summary, health summaries, counters, redaction summary.
- `timeline.log`: chronological events with elapsed timestamps.
- `manifest.json`: report schema version, file list, sizes, and SHA-256 checksums.

The export must not include raw media or credentials. A report ID must not encode a device identifier or account identity.

## Privacy and security

- Redact RTMP URLs, stream keys, authorization headers, tokens, passwords, email addresses, and filesystem paths before export.
- Do not collect Android account identifiers, advertising IDs, contacts, location, or installed-app inventories.
- Do not upload reports automatically.
- Show a brief contents/privacy explanation before sharing.
- Diagnostic files are stored only in app-controlled temporary/cache storage and are deleted according to a bounded retention policy after export.

## Integration contract

Each subsystem receives a small diagnostics interface and emits typed events/counters:

- Audio engine: source lifecycle, routing changes, buffer health, clipping/underrun warnings.
- Video pipeline: capture/encoder lifecycle, FPS and dropped frames.
- RTMP/network: connect/disconnect/reconnect, bitrate and error codes.
- App lifecycle: foreground/background transitions, process/service failures where observable.
- System monitor: sampled resource pressure and thermal throttling indicators.

Subsystems must not depend on the report exporter or UI.

## Initial acceptance criteria

1. One session produces one report with a unique report ID.
2. Audio, video, stream, and system modules can emit events through the same API.
3. Event timestamps remain ordered and session-relative.
4. The ring buffer remains bounded during long sessions.
5. Export produces valid JSON, timeline, manifest, and checksums.
6. RTMP keys and credential-bearing URLs are absent from every exported file.
7. No raw audio/video content is present.
8. Export does not interrupt an active stream.
9. Diagnostics errors cannot terminate streaming.
10. A report can be inspected offline by a maintainer without access to the user's device.

## Implementation order

1. Add the diagnostics API, typed event model, session manager, and bounded in-memory store.
2. Connect existing audio diagnostics to the shared API without changing the frozen mixer.
3. Add stream/network and video event adapters.
4. Add device/app metadata and sampled system health.
5. Implement redaction, JSON serialization, manifest, checksums, and ZIP export.
6. Add a simple Diagnostics screen and user-initiated share action.
7. Add unit tests for bounds, timestamps, redaction, schema stability, and export integrity.
