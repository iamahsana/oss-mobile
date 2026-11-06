# StreamingIn APK Audio Research

Reference artifact: `Streamingin-Android-full-usb (1).apk` (version 0.4.25, supplied by the project owner).

## Findings from static inspection

The APK contains references to these audio components:

- RootEncoder `MicrophoneSource`, `InternalAudioSource`, and `MixAudioSource`.
- RootEncoder `BufferAudioSource` and `SilenceAudioSource`.
- RootEncoder `MicrophoneManagerManual`.
- App components `MicShareService` and `MicShareGate`.
- Android `AudioRecordingConfiguration` / `getActiveRecordingConfigurations`.
- Audio mode and diagnostic strings mentioning `VOICE_RECOGNITION`, a mic being silenced by another app, and the mic returning to an echo-cancelled path after game capture resumes.

Important limitation: class names and strings in a compiled APK prove that the classes/resources are packaged, not by themselves that every class is used on every streaming path. Full control-flow confirmation requires DEX decompilation or runtime observation.

## RootEncoder 2.8.1 source behavior

The same RootEncoder 2.8.1 source tree contains:

- `BufferAudioSource`: a clocked PCM ring buffer. It emits regular PCM chunks, initially emits silence, and accepts producer PCM through `setBuffer`. The timestamp overload places PCM relative to a producer timestamp. Its configurable latency margin is designed to absorb producer jitter; the latency is compensated in output timestamps.
- `MicrophoneManagerManual`: exposes manual reads from the microphone's `AudioRecord`; the owner calls `read` when it needs samples.
- `InternalAudioSource`: uses Android playback capture with `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN`.
- `MicrophoneSource`: uses a separate microphone capture path.
- `MixAudioSource`: RootEncoder's built-in combined source. Its own documentation recommends echo cancellation and noise suppression to reduce microphone echo. It also documents that microphone behavior can vary when another app owns microphone capture.

## Comparison with current OSS

OSS currently uses:

- Direct `MicrophoneSource` for microphone-only mode.
- Direct `InternalAudioSource` for internal-playback-only mode.
- A custom `OssAudioMixerSource` for mixed mode, which starts both sources, queues their frames, pairs the oldest frames by timestamp, and forwards the software-mixed result.

The custom adapter is therefore not used by the two single-source modes. If those modes still appear to contain both sounds, the next investigation must distinguish:
1. acoustic playback leaking into the physical microphone;
2. microphone audio being played back by another app and therefore captured as playback;
3. Android/OEM capture arbitration or a silenced AudioRecord;
4. an actual source-selection or encoder-routing defect.

## Diagnostic build strategy

The diagnostic implementation added to OSS records:

- selected audio mode and actual source adapter;
- Android active recording configurations, client source, silenced state, client format, and input device;
- one-second PCM RMS/peak/frame-rate summaries for each selected source and each mixed input/output.

The diagnostic journal stores metadata and level statistics only; it does not store PCM audio.

Next decision should be based on the diagnostic report before changing the mixer algorithm. A future buffer-based design may use `BufferAudioSource` as a regular output clock, but independent producer frames must be aligned and software-mixed before they are written into a single output buffer; writing two sources directly into the same buffer would overwrite rather than sum them.
