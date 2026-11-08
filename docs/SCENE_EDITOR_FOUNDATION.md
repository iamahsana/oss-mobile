# Scene Editor Foundation

Status: implementation in progress. This document defines the complete scene-editor foundation before the next combined device test.

## Already device-validated
- Fixed-ratio/custom canvas geometry and editor-only zoom/pan.
- Layer transform, rotation, opacity, snapping, precision editing, undo/redo.
- Text, static image import, image crop/masks/borders/flips, shapes, emoji stickers.
- Persistent scene documents and matching editor/LIVE render geometry.

## Added in this foundation batch
- Persistent layer group IDs in the scene document codec, with backwards-compatible reads for earlier scenes.
- Group/ungroup operations, group drag, and multi-selection mode on the canvas.
- Shared bounding-box resize/rotation for selected layers.
- Multi-layer alignment (left/center/right/top/middle/bottom) and even horizontal/vertical distribution.
- Generic private media-asset storage for GIF and video inputs, using SAF-selected URIs, MIME allowlists, bounded file sizes, generated IDs, and no persisted temporary URI.
- Animated GIF source using RootEncoder 2.8.1 GifFilterRender, with parser preflight, frame/dimension/memory caps, editor poster thumbnail, and LIVE scene rendering.
- Video source using RootEncoder SurfaceFilterRender + Android MediaPlayer, with private SAF import, poster thumbnail, muted audio, looping playback, file/resolution/duration caps, and renderer lifecycle cleanup.

## Remaining foundation work
- Scene background color/gradient and reusable alignment guides.
- Asset cleanup/reference counting when a scene or layer is deleted.
- Final integration review of video/GIF renderer lifecycle and combined device acceptance testing.

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
