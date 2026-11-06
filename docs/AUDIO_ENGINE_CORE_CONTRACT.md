# OSS Audio Engine Core Contract

Status: active architecture baseline; initial microphone + internal playback adapter is connected to the RTMP audio path.

## Goals

- Keep microphone capture and Android playback capture as independent inputs.
- Mix, process, monitor, and route audio inside one core-owned engine.
- Support dynamically registered audio channels, not a fixed two-input mixer.
- Keep audio processing extensions optional and isolated behind stable interfaces.
- Never let a plugin own the RTMP connection or stop the stream.
- Keep the initial implementation small: microphone + internal audio, per-channel gain/mute, and Live/Monitor routing.

## Core pipeline

```
Capture Sources
  -> Audio Frame Normalizer
  -> Dynamic Channel Inputs
  -> Core Mixer
  -> Processing Graph (core stages + optional extensions)
  -> Output Buses
       -> Live Bus -> existing RTMP audio encoder
       -> Monitor Bus -> Android audio output (later milestone)
```

The Live and Monitor buses are independent destinations. A channel can be routed to either, both, or neither. Monitoring must not be implemented by feeding the RTMP output back into capture.

## Core interfaces

### AudioSource

A source owns one capture or playback endpoint and emits timestamped PCM frames. Initial source types:

- `MicrophoneSource`
- `InternalPlaybackSource`

Future independent sources may include a request-music player, soundboard, or other app-owned audio producer. These sources must enter through the same normalized-frame/channel contract; they must not be mixed directly into the microphone or internal-game capture.

A source must report its actual state (starting, running, muted, unavailable, failed, stopped) and a useful failure reason. It must not silently substitute another source when capture is unavailable.

### AudioFrame

A frame carries PCM samples, sample rate, channel count, frame timestamp, and source identity. The first implementation should choose one internal PCM format and normalize all sources into it before mixing. No source may write directly to the RTMP encoder.

### AudioChannel

Channels are registered dynamically by stable channel ID and each channel maps to a source ID. Each channel owns:

- stable channel ID and source ID
- enabled/muted state
- linear gain (with a safe bounded range)
- independent routing flags for Live and Monitor
- input level/meter data

A muted or unrouted channel must contribute silence to that destination. Muting Live must not mute Monitor, and vice versa. Removing a channel must not affect other channels.

### AudioMixer

The core mixer sums only channels routed to the requested bus, applies channel gain, and prevents integer overflow/clipping. Channel registration, mute, enable, gain, and routing changes must be independent of other channels. It must handle missing/late frames without blocking the video/stream thread. Initial implementation should favor predictable low-latency behavior over advanced DSP.

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

Routing changes are applied at audio-frame boundaries to avoid partially updated frames. Example future routing profile:

| Channel | Live | Monitor |
|---|---:|---:|
| Microphone | On | Optional |
| Internal game audio | On | Optional |
| Request music | On | Off by default |
| Soundboard | On | Off by default |

The Request Music row is an architectural example only; the player and audience request workflow are not implemented in this milestone.

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
5. Per-channel gain affects only that channel.
6. Live routing and Monitor routing are independent.
7. A third registered source can be routed without changing microphone/internal channel configuration.
8. Removing one channel leaves other channels unchanged.
9. Missing microphone permission does not silently route internal playback as microphone.
10. Unavailable internal playback capture does not silently route microphone as internal audio.
11. Processor crash/timeout bypasses that processor without terminating RTMP.
12. No audio feedback loop is created by monitoring.
13. Audio mixing does not block video frames or RTMP connection callbacks.

## Implementation sequence

1. Inspect and document the existing RootEncoder audio path and its exact PCM/source APIs.
2. Introduce the core contracts and a two-source frame normalization layer without changing the UI.
3. Connect the mixer to the existing RTMP audio encoder.
4. Verify Mic-only, Internal-only, and Mix with captured test recordings/logs.
5. Add per-channel gain/mute and Live routing controls.
6. Generalize the capture adapter to feed arbitrary registered sources into the dynamic mixer.
7. Implement Monitor output only after device routing and echo tests.
8. Add the optional processor extension point after the core path is stable.

## Explicit non-goals for this milestone

- Request Music player, audience request queue, or music catalog.
- Voice changer, denoising, echo cancellation, EQ, compressor, and soundboard.
- Plugin marketplace or downloadable audio processors.
- UI redesign and preview changes.
- Bluetooth-specific monitoring optimizations.
