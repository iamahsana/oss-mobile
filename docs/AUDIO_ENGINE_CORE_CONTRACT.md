# OSS Audio Engine Core Contract

Status: architecture baseline (not yet wired into the streaming service).

## Goals

- Keep microphone capture and Android playback capture as independent inputs.
- Mix, process, monitor, and route audio inside one core-owned engine.
- Keep audio processing extensions optional and isolated behind stable interfaces.
- Never let a plugin own the RTMP connection or stop the stream.
- Keep the initial implementation small: microphone + internal audio, per-channel gain/mute, and Live/Monitor routing.

## Core pipeline

```
Capture Sources
  -> Audio Frame Normalizer
  -> Channel Inputs
  -> Core Mixer
  -> Processing Graph (core stages + optional extensions)
  -> Output Buses
       -> Live Bus -> existing RTMP audio encoder
       -> Monitor Bus -> Android audio output (later milestone)
```

The Live and Monitor buses are independent destinations. A channel can be routed to either, both, or neither. Monitoring must not be implemented by feeding the RTMP output back into capture.

## Core interfaces

### AudioSource

A source owns one capture endpoint and emits timestamped PCM frames. Initial source types:

- `MicrophoneSource`
- `InternalPlaybackSource`

A source must report its actual state (starting, running, muted, unavailable, failed, stopped) and a useful failure reason. It must not silently substitute another source when capture is unavailable.

### AudioFrame

A frame carries PCM samples, sample rate, channel count, frame timestamp, and source identity. The first implementation should choose one internal PCM format and normalize both sources into it before mixing. No source may write directly to the RTMP encoder.

### AudioChannel

Each channel owns:
- stable channel ID and source ID
- enabled/muted state
- linear gain (with a safe bounded range)
- independent routing flags for Live and Monitor
- input level/meter data

A muted or unrouted channel must contribute silence to that destination. Muting Live must not mute Monitor, and vice versa.

### AudioMixer

The core mixer sums only channels routed to the requested bus, applies channel gain, and prevents integer overflow/clipping. It must handle missing/late frames without blocking the video/stream thread. Initial implementation should favor predictable low-latency behavior over advanced DSP.

### AudioBus

Initial buses:
- `LIVE`: feeds the existing encoded RTMP audio path.
- `MONITOR`: feeds local speaker/headphone output in a later milestone.

The monitor bus must respect Android audio focus and device routing. It must not be enabled by default until feedback/echo behavior is tested.

### AudioProcessor

Processors operate on normalized audio frames in a defined order. Built-in processing stages and extensions use the same contract. Processor failures must bypass/disable that processor and continue the stream with unprocessed audio where safe.

### AudioProcessorExtension

Extensions may declare supported input/output formats and processing latency. They receive audio frames only; they do not receive stream keys, RTMP controls, arbitrary filesystem access, or lifecycle ownership. A processor must have bounded execution time and must not block capture or encoding.

## Routing model

Each channel has two independent booleans:
- `toLive`
- `toMonitor`

Routing changes are applied at audio-frame boundaries to avoid partially updated frames. Default routing for the first test profile:
- Microphone: Live on, Monitor off
- Internal playback: Live on, Monitor off

Monitor output is a separate future milestone; this contract reserves the route without enabling acoustic playback prematurely.

## Lifecycle and failure rules

- The Audio Engine starts and stops with the streaming session, but capture sources can be started/stopped independently.
- Permission denial or Android capture-policy restrictions must produce a visible source error; never fall back from internal audio to microphone or vice versa.
- Source failure must not silently change the selected source.
- A failed optional processor is isolated and bypassed; it cannot stop RTMP.
- Audio callbacks must not perform disk/network I/O or wait on the UI thread.
- Release AudioRecord, playback-capture, and output resources deterministically.
- Track timestamps and dropped frames for diagnostics.

## Initial acceptance tests

1. Mic-only: microphone reaches Live; internal playback does not.
2. Internal-only: internal playback reaches Live; microphone does not.
3. Mix: both reach Live independently.
4. Per-channel mute affects only that channel.
5. Live routing and Monitor routing are independent.
6. Missing microphone permission does not silently route internal playback as microphone.
7. Unavailable internal playback capture does not silently route microphone as internal audio.
8. Processor crash/timeout bypasses that processor without terminating RTMP.
9. No audio feedback loop is created by monitoring.
10. Audio mixing does not block video frames or RTMP connection callbacks.

## Implementation sequence

1. Inspect and document the existing RootEncoder audio path and its exact PCM/source APIs.
2. Introduce the core contracts and a two-source frame normalization layer without changing the UI.
3. Connect the mixer to the existing RTMP audio encoder.
4. Verify Mic-only, Internal-only, and Mix with captured test recordings/logs.
5. Add per-channel gain/mute and Live routing controls.
6. Implement Monitor output only after device routing and echo tests.
7. Add the optional processor extension point after the core path is stable.

## Explicit non-goals for this milestone

- Voice changer, denoising, echo cancellation, EQ, compressor, and soundboard.
- Plugin marketplace or downloadable audio processors.
- UI redesign and preview changes.
- Bluetooth-specific monitoring optimizations.
