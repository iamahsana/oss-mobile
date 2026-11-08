# OSS Community Module Developer Preview

## Beta status

The current OSS beta bundles its initial modules in the APK. The manifest below defines the planned first declarative community-module format; the beta does not yet install third-party packages. Do not distribute a package claiming runtime compatibility until the Extension Center loader and validator ship.

## First module format

A module package is a ZIP with:
- `manifest.json` at its root
- optional media under `assets/`
- `LICENSE` and optional `README.md`

Validate `manifest.json` against `manifest-v1.schema.json`. Use a reverse-domain module ID you control, semantic versioning, a stable publisher ID, and an explicit license.

## What creators can build first

The declarative preview supports compositions of existing OSS primitives:
- text labels and counters
- packaged images
- basic shapes
- normalized position, size, rotation, and opacity
- property controls (text, number, toggle, choice, and color)

A module's property controls map only to a declared element's `default` value or an allowed normalized `transform` field. Targets use paths such as `elements.title.default` and `elements.panel.transform.opacity`. Number controls must declare `min` and `max`; choice controls must declare a non-empty `choices` list. The host owns rendering and validation; a manifest cannot run arbitrary Kotlin, Java, JavaScript, shell commands, or native code.

## Security boundaries

- No network, microphone, camera, screen-capture, filesystem, or accessibility permission is granted to declarative modules.
- Assets must remain inside the package and obey package-size and decoded-image limits.
- Reject unknown manifest fields, duplicate IDs/keys, unsupported API versions, unsafe asset paths, invalid numeric ranges, invalid property target paths, and undeclared capabilities.
- Keep each package self-contained. A manifest cannot reference an arbitrary filesystem path or mutate another element's undeclared properties.
- Validate ZIP entry count, uncompressed size, per-asset size, compression ratio, and path traversal before extracting any package.
- Keep a missing module's scene layer as an unavailable placeholder; never silently delete user content.
- Native executable extensions, if ever supported, require a separate reviewed/signed execution model and must not be treated as ordinary declarative modules.

## Example

See `examples/stream-label/manifest.json`. This example is a format demonstration, not an installable module for the current beta.
