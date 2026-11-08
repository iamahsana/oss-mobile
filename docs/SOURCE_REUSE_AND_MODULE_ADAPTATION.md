# OSS beta source reuse and module adaptation policy

## Goal

Ship the beta with its element set built into the APK while keeping every element behind the same stable module contract used by the future Extension Center. Reuse mature open-source implementations where they fit; adapt their UI and behavior to OSS rather than rebuilding commodity editing primitives.

## Non-negotiable integration boundary

- The scene document remains the single source of truth for editor and LIVE.
- Each placed element remains an independent layer with its own configuration, transform, visibility, z-order, and stable module ID.
- A reused editor library must not flatten the entire scene into one bitmap or replace the scene document.
- Libraries may provide a focused primitive (crop UI, image decoding, GIF decoding, gestures, drawing paths, sticker manipulation). OSS owns persistence, module identity, LIVE rendering, permissions, and the PixelLab/Streamingin workspace.
- All bundled beta elements are registered through the core module registry. Later, the same implementations can be packaged as downloadable extensions without changing saved scene documents.

## Candidate source components

These are candidates for selective adoption, not automatic wholesale integration. Before adding a dependency or copying source, verify the exact release, Android API compatibility, transitive dependencies, maintenance state, and the license text for that version.

| Area | Candidate | Intended reuse | Integration constraint |
|---|---|---|---|
| Crop UI | CanHub Android-Image-Cropper (Apache-2.0) | Mature crop/rotate interaction for imported image assets | Export a cropped asset or crop configuration for one image layer; never crop the full scene as a flattened image |
| Text, drawing, stickers | burhanrashid52/PhotoEditor (MIT) | Reuse focused gesture, drawing, text/sticker editing ideas or source components | Do not embed its full photo editor as OSS's scene editor; preserve independent OSS scene layers and direct LIVE document updates |
| Image and GIF loading | Glide (BSD / MIT / Apache components; verify notices) | Decode/cache still images, video thumbnails, and animated GIFs | Respect memory limits; editor preview and LIVE renderer must share media semantics without retaining unbounded bitmaps |
| Video | Android platform MediaMetadataRetriever / MediaCodec and existing RootEncoder integration | Thumbnail/frame extraction and existing encode/render path | Avoid a second competing encoder or duplicating decoder resources |
| Streaming | RootEncoder already in use | RTMP and GL composition primitives | Preserve verified RTMP/audio path; don't replace it during editor work |

## License and attribution checklist

1. Prefer Apache-2.0, MIT, or BSD components compatible with OSS's chosen distribution license.
2. Keep upstream copyright and license notices when source is copied or modified.
3. Record dependency coordinates, exact version, upstream URL, license, modifications, and rationale in THIRD_PARTY_NOTICES.md.
4. Do not copy code/assets from projects with unclear, missing, or incompatible licenses.
5. A dependency is not adopted until its exact release license and Android compatibility have been checked.

## Beta integration sequence

1. Complete the PixelLab-style workspace: full available canvas stage, top actions, adaptive bottom dock, separate canvas settings, template picker, and saved-scene picker.
2. Make the dock's Elements mode add built-in modules and its Properties mode edit the selected layer in place. Property controls must not cover or resize the canvas.
3. Adapt reusable primitives behind OSS-owned module adapters.
4. Complete text, image, sticker, shape, GIF, video, and Share Screen property workflows with consistent layer transforms and ordering.
5. Complete scene persistence, template application, and orientation changes without mutating normalized scene geometry.
6. Run static/build/unit validation during development, but defer user device testing until the whole beta package is ready for one combined session.

## Current implementation note

The app currently uses RootEncoder and OSS-owned scene/module contracts. Existing OSS text, image, shape, sticker, GIF, video, and Share Screen implementations remain the baseline until a candidate library passes the integration and license checks above. Do not add a library merely to claim reuse; use it only when it reduces maintenance without breaking the scene model.
