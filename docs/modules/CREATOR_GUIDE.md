# OSS Module Creator Guide (API v1 preview)

> This guide describes the declarative module format being prepared for the OSS Extension Center. The current beta includes built-in elements only; it does not yet install ZIP packages. A package made today is a developer-preview artifact, not an installable OSS extension.

## 1. What you can build

The first declarative format composes existing OSS primitives:
- `text`: a text element with an editable default value
- `image`: an image asset bundled inside the module package
- `shape`: a basic rectangle/circle/line-style visual primitive

Each element has its own stable key and normalized transform. Multiple elements form one reusable overlay/widget/template composition. The module can expose controls that edit only its own declared element values or transform fields.

## 2. Package layout

Create a ZIP with this structure:

```text
my-module/
├── manifest.json
├── LICENSE
├── README.md
└── assets/
    └── panel.png
```

Keep all assets inside `assets/`. Do not use absolute paths, parent-directory segments (`..`), symbolic links, or external file references.

## 3. Minimal manifest

```json
{
  "manifestVersion": 1,
  "id": "community.example.my-overlay",
  "name": "My Overlay",
  "description": "A reusable stream title and panel.",
  "version": "1.0.0",
  "minCoreApi": 1,
  "publisher": {
    "id": "example-community",
    "displayName": "Example Community"
  },
  "license": "MIT",
  "kind": "overlay",
  "capabilities": ["visual.render", "property.controls"],
  "permissions": [],
  "elements": [
    {
      "key": "title",
      "type": "text",
      "default": "LIVE NOW",
      "transform": {
        "x": 0.08,
        "y": 0.06,
        "width": 0.84,
        "height": 0.10,
        "rotation": 0,
        "opacity": 1
      }
    }
  ],
  "properties": [
    {
      "key": "title_text",
      "label": "Judul",
      "control": "text",
      "target": "elements.title.default"
    }
  ]
}
```

Validate the manifest against `manifest-v1.schema.json`. The full sample is in `examples/stream-label/`.

## 4. Coordinate and property rules

- `x`, `y`, `width`, and `height` use normalized scene coordinates, not screen pixels.
- `x=0`, `y=0` is the top-left of the scene canvas. Width and height are fractions of the canvas.
- Rotation is in degrees; opacity is between 0 and 1.
- Property targets are limited to `elements.<key>.default` and `elements.<key>.transform.<field>`.
- Number controls must declare `min` and `max`; choice controls must declare a non-empty `choices` array.
- Keep property keys unique within the module and element keys unique within `elements`.

## 5. Compatibility and updates

- Use a reverse-domain module ID that you control. Do not change it between releases.
- Follow semantic versioning. Increment the version when publishing a change.
- Set `minCoreApi` to the oldest OSS module API your package actually supports.
- Keep saved configuration backward-compatible where possible. If a breaking configuration change is unavoidable, provide a migration plan before the Extension Center loader is released.
- Include a real license file and retain notices for all bundled assets.

## 6. Security and review

Declarative modules cannot execute arbitrary Kotlin, Java, JavaScript, shell commands, or native code. Do not request broad device permissions. The future Extension Center will validate package integrity, API compatibility, declared capabilities, archive limits, asset paths, and publisher metadata before activation.

A missing or incompatible module must remain a visible placeholder in saved scenes; it must never cause user content to be silently deleted.

## 7. Current limitation

The beta's module API and manifest are foundations for community authors. Runtime package installation, property-control rendering for third-party manifests, publisher verification, signing, moderation, and Extension Center distribution are still future milestones. Do not tell users that a ZIP is installable until those components ship.
