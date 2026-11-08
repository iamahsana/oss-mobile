# RootEncoder PCM Output Bridge Research

Status: research decision; no production audio code changed.

Date: 2026-11-07

## Decision

Keep the existing OSS mixer frozen. Do not replace it with MWEngine, Klarinet, or a second Oboe-based capture stack at this stage.

The most compatible next experiment is to evaluate RootEncoder's own BufferAudioSource as an encoder-facing PCM output clock/buffer, while leaving independent microphone/internal capture and the existing OSS mixer contract intact.

This is a proposed isolated experiment, not an approved production migration.

## Why BufferAudioSource

OSS already pins:

    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")

RootEncoder 2.8.1 includes BufferAudioSource (listed in the 2.8.1 release notes). The source implementation:

- Accepts signed PCM16 data through setBuffer.
- Supports timestamped writes in microseconds.
- Uses a bounded ring buffer with configurable capacity and latency margin.
- Emits regular PCM frames on its own clock.
- Emits silence when no producer data is queued.
- Compensates the configured latency margin in emitted frame timestamps.

This gives OSS a library-provided bridge between its mixed PCM output and RootEncoder's encoder input, instead of making the custom mixer adapter also own the encoder-facing output clock.

## Proposed isolated pipeline

    RootEncoder MicrophoneSource ─┐
                                  ├─> existing OSS frame normalization/mixer
    RootEncoder InternalAudioSource┘
                                          |
                                          v
                                 mixed PCM16 + timestamp
                                          |
                                          v
                             RootEncoder BufferAudioSource
                                          |
                                          v
                                existing AAC/RTMP path

The microphone and internal audio must remain separate inputs until after per-channel controls and routing have been applied. Do not replace them with a single pre-mixed capture source.

## Important boundaries

1. BufferAudioSource improves output pacing and absorbs producer jitter; it does not fix Android/OEM capture arbitration.
2. It does not separate sources or implement the OSS channel mixer. Per-channel gain, mute, balance, routing, meters, and buses remain OSS responsibilities.
3. It is not a reason to open another microphone or playback-capture device.
4. Timestamp alignment still needs validation. The existing pairer and its 150 ms pairing window must not be assumed correct merely because a downstream ring buffer is added.
5. Missing/late input frames need explicit behavior. A single unavailable channel must not accidentally stall all output.
6. The BufferAudioSource ring buffer is bounded. Overflow/drop behavior and latency must be observed under real device load.
7. Monitor output is out of scope for this experiment.

## RootEncoder findings

- RootEncoder's built-in MixAudioSource captures microphone and Android playback audio together. It exposes separate microphone/internal volume properties, but it emits one combined source; this does not meet OSS's independent channel routing and per-channel mute requirements.
- RootEncoder's CustomAudioEffect receives PCM from the source's processing path. In the built-in mix mode it is applied after microphone and internal PCM have been combined, so it is not by itself a substitute for independent source channels.
- RootEncoder's maintainer has documented OEM-specific cases on Android 16 where both AudioRecord instances initialize and read valid-sized buffers, but internal playback PCM remains silent when microphone + game capture are active. The discussion points toward Android/OEM audio policy, not a mixer arithmetic problem. BufferAudioSource cannot repair a silent capture input.

## Why not swap in another engine now

### MWEngine

It has a mature native audio engine and effect chains, but its architecture is oriented around its own audio driver/render engine and music/sequencer channels. It is not a drop-in PCM mixer for RootEncoder's independently captured live inputs.

### Klarinet

It offers low-latency streams and native effects, but its documented callback model uses a worker thread behind a FIFO, and effects are attached to Klarinet streams. Using it as another input/output engine risks introducing a second audio I/O lifecycle. No verified direct adapter for RootEncoder's existing PCM frames has been established.

### Oboe

Oboe is a strong Android low-latency audio I/O layer, not a complete mixer. Introducing it now would add another device I/O path without solving the Android capture-policy issue. Revisit only if measurements show RootEncoder's existing I/O path is the limiting factor.

## Experiment plan

1. Build a separate adapter/prototype on this research branch only; do not edit the frozen mixer implementation.
2. Feed known PCM test buffers into BufferAudioSource and confirm output cadence, sample rate, channel count, timestamps, and silence behavior.
3. Test short, continuous, delayed, and bursty producers; record underruns, dropped bytes, and end-to-end audio delay.
4. Compare output against the current OSS custom adapter using the same captured input frames.
5. Run device tests on the target Realme 13+ / Android 16.
6. Test mic-only, internal-only, and mixed modes separately. If internal PCM is already silent at the capture callback, stop the mixer experiment and investigate Android audio policy instead.
7. Only consider migration after the prototype passes tests and preserves the current stream stability.

## Sources

- RootEncoder repository: https://github.com/pedroSG94/RootEncoder
- RootEncoder 2.8.1 release notes: https://github.com/pedroSG94/RootEncoder/releases
- BufferAudioSource API: https://pedrosg94.github.io/RootEncoder/encoder/com.pedro.encoder.input.sources.audio/-buffer-audio-source/index.html
- RootEncoder PCM/mixing discussion: https://github.com/pedroSG94/RootEncoder/discussions/1972
- RootEncoder Android 16 mixed-capture discussion: https://github.com/pedroSG94/RootEncoder/discussions/2131
- MWEngine: https://github.com/igorski/MWEngine
- Klarinet: https://github.com/vectencia/Klarinet
- Oboe: https://github.com/google/oboe