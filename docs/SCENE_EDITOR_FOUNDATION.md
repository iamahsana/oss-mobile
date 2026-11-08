# Scene Editor Foundation

Status: implementation in progress. This document defines the complete scene-editor foundation before the next combined device test.

## Already device-validated
- Fixed-ratio/custom canvas geometry and editor-only zoom/pan.
- Layer transform, rotation, opacity, snapping, precision editing, undo/redo.
- Text, static image import, image crop/masks/borders/flips, shapes, emoji stickers.
- Persistent scene documents and matching editor/LIVE render geometry.

## Added in this foundation batch
- Persistent layer group IDs in the scene document codec.
- Group/ungroup operations and moving grouped layers together.
- Multi-layer alignment (left/center/right/top/middle/bottom).
- Even horizontal/vertical distribution.
- Generic private media-asset storage for GIF and video inputs, using SAF-selected URIs, MIME allowlists, bounded file sizes, generated IDs, and no persisted temporary URI.

## Remaining media renderer work before calling the foundation complete
- Animated GIF: implemented with RootEncoder 2.8.1 GifFilterRender, parser preflight, frame/dimension/memory caps, GL-thread frame selection, editor poster thumbnail, and LIVE scene renderer.
- Video source: MediaExtractor/MediaCodec decode, audio-track exclusion by default, timestamp pacing, pause/loop controls, and lifecycle-safe release.
- Video source: MediaExtractor/MediaCodec decode, audio-track exclusion by default, timestamp pacing, pause/loop controls, and lifecycle-safe release.
- Editor: frame/poster thumbnails and playback indicators that never start a second media decoder during normal edit gestures.
- Multi-select canvas gestures and group resize/rotate as a single bounding-box transform.
- Scene background color/gradient and reusable alignment guides.
- Asset cleanup/reference counting when a scene or layer is deleted.

## Invariants
1. Scene documents store stable asset IDs, not content URIs or raw filesystem paths.
2. All visual source modules use the same normalized scene transform contract.
3. GL renderer mutation occurs only on the RootEncoder GL thread.
4. Animated media clocks are independent from editor UI refresh; no per-frame UI polling.
5. Video/GIF decode must be bounded and must not alter the frozen audio mixer.
6. LIVE and editor use the same crop/mask/shape/transform semantics.
7. Unsupported modules fail visibly and safely; they never silently render a misleading still frame as an animation.

## Combined device acceptance checklist
- Group, ungroup, group drag, save/reopen.
- Align and distribute selected layers, save/reopen.
- Layer visibility/lock/order/duplicate/delete and undo/redo.
- Static image/text/shape/sticker composition and transform parity.
- GIF playback, loop, frame timing, disposal, and LIVE parity.
- Video playback, loop/pause, portrait/landscape clips, and LIVE parity.
- Long scene edit session, repeated open/close, and memory stability.
- Existing RTMP and audio mixer smoke test only if renderer changes touch the stream lifecycle.
