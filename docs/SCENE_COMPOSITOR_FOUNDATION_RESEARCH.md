# Scene Compositor Foundation Research

Status: research checkpoint; no runtime implementation or dependency change in this PR.
Target baseline: OSS `feature/live-stream-health-panel`, RootEncoder `2.8.1`.

## Decision summary

**Keep RootEncoder 2.8.1 as the first compositor candidate. Do not add a second rendering/encoding library yet.**

RootEncoder already owns the EGL/OpenGL render loop, input texture, filter chain, encoder surface, and optional preview surface. Its public GL interface supports multiple `BaseFilterRender` instances with add/insert/remove operations. The current OSS `OverlayCompositor` is already a small registry that orders and attaches those filters.

This is the lowest-risk path because OSS has already validated its RTMP/audio pipeline on the target Realme 13+ device. A separate compositor would add another EGL context, frame clock, texture handoff, synchronization, and encoder integration before we have proved the built-in path insufficient.

## Verified RootEncoder 2.8.1 capabilities

Source inspected at the exact `2.8.1` tag:

- `library/.../GlInterface.java`: `addFilter`, indexed `addFilter`, `removeFilter`, `clearFilters`, `filtersCount`, and `setFilter`.
- `encoder/.../MainRender.kt`: owns an ordered list of filters; each filter receives the previous filter's texture. Filters are drawn in list order and the final texture is sent to the screen/encoder output.
- `encoder/.../filters/object/ImageFilterRender.kt`: bitmap-backed image layer.
- `TextFilterRender.kt`: text layer with color, background, and Typeface support.
- `GifFilterRender.kt`: animated GIF layer.
- `SurfaceFilterRender.kt`: provides a SurfaceTexture-based input slot; the library demo connects a MediaPlayer Surface to it for video.
- `BaseObjectFilterRender.kt`: object layers have a Sprite transform and alpha.
- The demo's `FilterMenu.kt` demonstrates image, text, GIF and video-surface renderers and the optional SpriteGestureController.

The RootEncoder render loop in `GlStreamInterface.kt` draws the filter chain for an active encoder independently of the preview surface. Preview rendering is in a separate conditional block and occurs only when a preview surface is attached. This supports OSS's desired default: no always-visible preview, while streaming continues to render the same scene to the encoder.

Relevant upstream sources:
- https://github.com/pedroSG94/RootEncoder/tree/2.8.1
- https://github.com/pedroSG94/RootEncoder/blob/2.8.1/library/src/main/java/com/pedro/library/view/GlInterface.java
- https://github.com/pedroSG94/RootEncoder/blob/2.8.1/library/src/main/java/com/pedro/library/view/GlStreamInterface.kt
- https://github.com/pedroSG94/RootEncoder/blob/2.8.1/encoder/src/main/java/com/pedro/encoder/input/gl/render/MainRender.kt
- https://github.com/pedroSG94/RootEncoder/blob/2.8.1/encoder/src/main/java/com/pedro/encoder/input/gl/render/filters/object/ImageFilterRender.kt
- https://github.com/pedroSG94/RootEncoder/blob/2.8.1/encoder/src/main/java/com/pedro/encoder/input/gl/render/filters/object/TextFilterRender.kt
- https://github.com/pedroSG94/RootEncoder/blob/2.8.1/encoder/src/main/java/com/pedro/encoder/input/gl/render/filters/object/GifFilterRender.kt
- https://github.com/pedroSG94/RootEncoder/blob/2.8.1/encoder/src/main/java/com/pedro/encoder/input/gl/render/filters/object/SurfaceFilterRender.kt

## Existing OSS groundwork

Inspected on `feature/live-stream-health-panel`:

- `OverlayCompositor.kt` already registers visual renderers by stable ID, sorts enabled layers by z-index, and attaches/removes only its own filters.
- `OverlaySourceModule.kt` defines a source-module contract that can create a `BaseFilterRender`.
- `Scene.kt` currently stores only a scene name and basic source metadata. It does not yet persist position, size, rotation, crop, opacity, or layer order as editable geometry.
- `ScreenStreamService.kt` creates `OverlayCompositor(output.getGlInterface())` and changes the video source to RootEncoder `ScreenSource`. The compositor is currently not yet populated with user-editable scene layers.
- `MainActivity.kt` already attaches and detaches a RootEncoder preview `TextureView`; the service can keep the preview detached when the user does not need it.

No mixer or audio code needs to change for this work.

## Recommended architecture

Keep one authoritative scene model and one render path:

```text
Scene document (persisted, normalized geometry)
        |
        +--> Editor canvas (touch selection / move / resize / rotate)
        |
        +--> Source module registry
                    |
             RootEncoder layer renderers
                    |
             OverlayCompositor
                    |
             RootEncoder GL filter chain
                    |
          +---------+----------+
          |                    |
      Encoder output       Optional preview
          |
       RTMP stream
```

The editor canvas is a control surface, not a second video compositor. It draws a lightweight editing representation (source thumbnails/placeholders, selection outline, handles, guides) and edits the scene document. RootEncoder remains responsible for the actual stream pixels.

### Scene data contract (proposed; not implemented here)

Persist scene geometry in a normalized canvas coordinate space so the same scene can be used at 9:16, 16:9, and different output resolutions:

- Scene: stable ID, name, canvas aspect ratio / logical dimensions, ordered layers.
- Layer: stable ID, source ID/type, visible, locked, z-order, normalized x/y/width/height, rotation, opacity, crop/fit mode, optional source-specific settings.
- Runtime renderer instances, GL texture IDs, Surface objects, decoder instances, and permission tokens must never be persisted.
- A source may be referenced by more than one layer; duplicate presentation should not create a duplicate camera or decoder unless explicitly required.

Do not migrate the current Scene format until a round-trip migration test and rollback path exist.

### Editor interaction contract

PixelLab-like behavior is the UX target:

- Tap an object to select it and show a transform box.
- Drag the selected object to move it.
- Drag corner/edge handles to resize; a separate handle rotates.
- Pinch gestures zoom/pan the canvas, not the selected object when the gesture begins outside its handles.
- Layer list supports reorder, hide/show, lock/unlock, duplicate, and delete.
- Snap-to-center/edges and alignment guides assist placement.
- Undo/redo works on scene-document edits, not GL renderer objects.
- A small source toolbar adds text, image, GIF, video, and later camera/browser sources.
- Preview is an explicit optional action. Leaving the editor detaches preview; live rendering remains active.

RootEncoder's `SpriteGestureController` is useful as a reference for direct sprite manipulation, but should not be the editor's source of truth. The editor needs multiple selectable layers, handles, snapping, undo/redo, and persistent normalized geometry. Those belong in OSS's scene document/editor layer.

## Important constraints and risks

1. **Filter-chain ownership:** only `OverlayCompositor` should own scene-layer filter insertion/removal. Avoid multiple features calling `clearFilters()` or independently rebuilding the chain.
2. **Render-thread lifecycle:** GL filter creation, upload, mutation, and release must follow RootEncoder's queued GL operations. Never manipulate GL resources directly from the UI thread.
3. **Rebuild cost:** the current `OverlayCompositor.setLayers()` removes and re-adds every layer. It is acceptable as an initial registry, but the editor should not call it for every pointer movement. Add transform-update APIs or diff-based updates after the renderer contract is tested.
4. **Video decode:** `SurfaceFilterRender` supplies a surface slot; it is not by itself a complete file-picker/decoder/lifecycle manager. A video source module must own MediaExtractor/MediaCodec or a proven player, timestamps, looping, audio routing, and cleanup. Do not decode multiple videos without device capability and memory tests.
5. **GIF memory:** RootEncoder's GIF renderer loads frame bitmaps/textures. Limit dimensions, frame count, and concurrent GIF layers; measure memory before allowing arbitrary large GIFs.
6. **Text:** RootEncoder's built-in text renderer covers basic text. Rich typography (stroke, shadow, gradients, multi-line layout) may need a module-owned bitmap renderer, generated only when text/style changes rather than every frame.
7. **Preview lifecycle:** preview attach/detach must not start/stop the stream or rebuild the scene. Verify screen-capture self-capture behavior remains protected.
8. **Existing screen source:** the first visual proof should use the existing `ScreenSource` + one static image overlay. This validates filter insertion without introducing a new camera or decoder source.
9. **Thermal/performance:** uploaded device diagnostics showed a thermal SEVERE condition in a prior test. Keep the first proof to one static overlay and measure temperature, FPS, bitrate, memory, and dropped frames before adding animated/video layers.

## Reference project review

[Vivid](https://github.com/thoser666/Vivid) is an Android streaming project built on RootEncoder and provides useful reference material for scene geometry, editor gestures, layer models, and a custom scene filter. Its scene-compositor RFC explicitly separates design from device verification and records open GLES/device evidence. Therefore it is a design/code reference, **not proof that its full compositor is production-verified on OSS's target device**. Inspect individual files and commit/license before any code reuse. Do not import the entire app architecture.

Useful reference paths:
- https://github.com/thoser666/Vivid/blob/develop/docs/architecture/scene-compositor.md
- https://github.com/thoser666/Vivid/blob/develop/feature-streaming/src/main/java/com/vivid/feature/streaming/scene/SceneLayerFilterRender.kt
- https://github.com/thoser666/Vivid/blob/develop/feature-streaming/src/main/java/com/vivid/feature/streaming/ui/scene/SceneEditorCanvas.kt

## Implementation gates

### Gate 0 — documentation and API inspection (this checkpoint)
- Confirm RootEncoder 2.8.1 APIs and current OSS wiring.
- No dependency changes, no mixer changes, no runtime behavior changes.

### Gate 1 — one static image layer
- Add one image source through the existing `OverlayCompositor` to the existing screen stream.
- Validate z-order, alpha, placement, output resolution, start/stop cleanup, and no preview attached.
- Verify preview can be attached temporarily and shows the same composition as the encoder.
- Run CI and a short on-device RTMP test. Keep the feature behind a disabled-by-default test flag until verified.

### Gate 2 — editable transform model
- Add serializable normalized transforms and pure geometry tests.
- Add diff-based runtime transform updates, without GL calls from UI thread.
- Do not build the full PixelLab-like editor until transform round-trip tests pass.

### Gate 3 — PixelLab-like canvas editor
- Build the touch editor over the scene document: selection, drag, resize, rotate, pinch zoom, snapping, layer list, undo/redo, autosave.
- Keep preview optional and separate from live output.
- User will provide Streamingin screenshots when ready; use them to refine the simple scene-creation flow.

### Gate 4 — more source types
- Add text and still images, then GIF, then video, then camera/browser sources.
- Each source type gets a lifecycle contract, targeted tests, and a separate on-device proof.

## Acceptance criteria for the first proof

- No changes to the frozen OSS audio mixer.
- No new encoder, EGL context, or video-frame CPU copy.
- One static overlay appears in the RTMP output at the expected position/scale/alpha.
- Preview detached: RTMP output continues.
- Preview reattached: preview matches the encoded scene.
- Layer removal and service stop release resources without crashes.
- CI build/tests pass; user verifies the APK on the Realme 13+ before the next source type is added.
