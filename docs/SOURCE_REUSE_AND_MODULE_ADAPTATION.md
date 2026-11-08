# OSS beta source reuse and module adaptation policy

## Goal

Ship the beta with its element set built into the APK while keeping every element behind the same stable module contract used by the future Extension Center. Reuse mature open-source implementations where they fit; adapt their UI and behavior to OSS rather than rebuilding commodity editing primitives.

## Non-negotiable integration boundary

- The scene document remains the single source of truth for editor and LIVE.
- Each placed element remains an independent layer with its own configuration, transform, visibility, z-order, and stable module ID.
- A reused editor library must not flatten the entire scene into one bitmap or replace the scene document.
- Libraries may provide a focused primitive (crop UI, image decoding, GIF decoding, gestures, drawing paths, sticker manipulation). OSS owns persistence, module identity, LIVE rendering, permissions, and the PixelLab/Streamingin workspace.
- All bundled beta elements are registered through the core module registry. Later, the same implementations can be packaged as downloadable extensions without changing saved scene documents.

## Beta scope freeze and proven-source-first policy

For the current beta milestone, freeze feature expansion of the element implementations that already exist. Do not add new element types or rewrite their renderers unless a confirmed integration defect blocks the complete beta workflow.

### Frozen element baseline

- Text, static image/gallery, sticker, shape, GIF, video, and Share Screen remain at their current implementation/API boundary.
- Preserve stable module IDs, serialized configuration keys, scene document compatibility, and existing renderer behavior.
- Bug fixes are allowed only for reproducible defects or integration blockers; avoid cosmetic rewrites and speculative feature additions.
- Defer camera source, audio source elements, browser/web widgets, automation triggers, and other new module types to later milestones.

### Proven-source-first integration order

1. Prefer components already present and exercised in OSS, especially RootEncoder 2.8.1 and Android platform APIs.
2. Reuse mature upstream libraries only when the exact version, license, Android API compatibility, and integration surface can be verified. Do not add dependencies just to increase the dependency count.
3. Where a source is not yet verified, isolate it behind an adapter and keep it out of the beta's critical path.
4. Prioritize editor workspace behavior, per-element property editing, scene/template persistence, orientation adaptation, lifecycle safety, and editor-to-LIVE consistency over new element features.
5. Keep audio/RTMP behavior unchanged; no refactor of the previously validated streaming/audio path as part of editor polish.

### Completion gate

The beta is ready for one combined user test only when the editor opens reliably, the canvas remains stable through portrait/landscape changes, bottom-dock module/property modes work without covering the canvas, saved scenes/templates persist, all frozen elements can be selected/configured/ordered, Share Screen remains a scene layer, and the same scene document reaches LIVE. CI build/unit tests are necessary but do not substitute for device validation.

## Candidate source components

These are candidates for selective adoption, not automatic wholesale integration. Before adding a dependency or copying source, verify the exact release, Android API compatibility, transitive dependencies, maintenance state, and the license text for that version.

| Area | Candidate | Intended reuse | Integration constraint |
|---|---|---|---|
| Crop UI | CanHub Android-Image-Cropper (Apache-2.0) | Mature crop/rotate interaction for imported image assets | Export a cropped asset or crop configuration for one image layer; never crop the full scene as a flattened image |
| Text, drawing, stickers | burhanrashid52/PhotoEditor (MIT) | Reuse focused gesture, drawing, text/sticker editing ideas or source components | Do not embed its full photo editor as OSS's scene editor; preserve independent OSS scene layers and direct LIVE document updates |
| Image and GIF loading | Glide (BSD / MIT / Apache components; verify notices) | Decode/cache still images, video thumbnails, and animated GIFs | Respect memory limits; editor preview and LIVE renderer must share media semantics without retaining unbounded bitmaps |
| Video | Android platform MediaMetadataRetriever / MediaCodec and existing RootEncoder integration | Thumbnail/frame extraction and existing encode/render path | Avoid a second competing encoder or duplicating decoder resources |
| Streaming | RootEncoder already in use | RTMP and GL composition primitives | Preserve verified RTMP/audio path; don't replace it during editor work |

## Community-created modules (first-class product requirement)

OSS must support modules created by ordinary users and community developers, not only modules published by the OSS maintainers. The initial beta may ship with built-in elements only; the extension architecture must not assume that all future modules come from the official team.

### Creator workflow

- Provide a documented, versioned OSS Module SDK/API and starter templates so developers can create an element without modifying the OSS core.
- A module declares a stable reverse-domain ID, display name, version, minimum OSS module API version, element/source type, capabilities, permissions, settings schema, and renderer/editor entry points.
- Provide a module manifest, validation tool, sample modules, and a build/package format that can be installed by the future Extension Center.
- Support modules that add both an element and its own property controls. The properties UI must render inside the same adaptive bottom dock and follow OSS interaction conventions.
- Support community modules for sources, overlays, effects, widgets, automation triggers, and integrations as the API matures.

### Security, compatibility, and trust

- Third-party modules must not receive unrestricted access to the app, files, microphone, camera, screen capture, network, or accessibility APIs by default.
- Use explicit capability/permission declarations and user consent. Reject undeclared capabilities.
- Prefer declarative manifests and sandboxed extension points. Native executable code requires stronger review, signing/provenance, and compatibility checks before distribution.
- Validate package integrity, module ID uniqueness, API compatibility, resource limits, and dependency/license metadata before installation.
- The Extension Center should distinguish official, community, and private/unlisted modules; show publisher, version, permissions, changelog, license, and user feedback.
- Provide disable, uninstall, update, and rollback paths. A broken module must not crash the editor or prevent the user from opening saved scenes.
- Saved scenes store stable module IDs and versioned configuration. If a module is missing or incompatible, preserve its layer as an unavailable placeholder and never silently delete it.

### Release staging

1. Beta: built-in modules only, but every built-in module uses the same manifest/registry contract intended for external modules.
2. Developer preview: publish the SDK documentation, starter project, manifest schema, and sample module.
3. Extension Center: allow community submission and installation, with validation, permissions, publisher identity, moderation/reporting, and version management.
4. Mature ecosystem: add discovery, ratings/reviews, dependency resolution, compatibility badges, and creator documentation.

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
