# CombinedAssault

Ground vehicles, aircraft, ships and fixed emplacements for **Minecraft 1.21.1 / NeoForge**, built on
[WFLib](https://github.com/Warfactory-Official/WF-Ballistics)'s ballistics and explosion framework. Every
vehicle is a content pack: a glTF model whose nodes carry the rig, colliders, hit modules and sockets, no
Java per vehicle required. 

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.248+ |
| Java | 21 |
| Rendering | GemRender (skinned/rigged glTF models) + [Flywheel](https://github.com/Jozufozu/Flywheel) 1.0.6 |
| License | GPL-3.0 (code); assets CC-BY-NC-4.0 + third-party, see [ASSETS.md](ASSETS.md) |

---

## Features

- **Vehicles as content packs** - ground, air, naval and fixed-emplacement vehicles, each a glTF pack whose
  node names drive rig, colliders, hit modules and sockets — [vehicle-pack-format](docs/vehicle-pack-format.md).
- **Rigid-body physics** - per-tick solver, convex hulls, suspension, contact/tumbling rules —
  [removed](REDACTED).
- **Ballistics, armor and system damage** on WFLib, surfaced through wflib UI-widget probe panels and map/HUD
  overlays — [hud](docs/hud.md).
- **Naval** - hull physics, decks, carrier parenting — [removed](REDACTED).
- **Towing**, cargo hold + airlift (parachutes, slings, drive-in bays), crew requirements, fuel/power.
- **Indirect fire** - map-based fire missions and terminal telemetry, plus an unmanned MIM-104-style
  air-defence battery on WFLib's shared interceptor system.
- **FPV and fixed UAVs** - stock drone airframes, camera feed + control tablet.
- **Driver-side client prediction**, server authoritative — [removed](REDACTED).
- **Fast-vehicle chunk/entity streaming** at speed, without unloading the flight/drive path —
  [removed](REDACTED).
- **Authoring tools** - Blender + Blockbench add-ons, pack validator, player reference —
  [asset-tools](docs/asset-tools.md).

Full doc index: [docs/README.md](docs/README.md).


## Building

NeoForge ModDevGradle project:

```bash
./gradlew build            # produces the mod jar in build/libs
./gradlew runClient        # launch a dev client
./gradlew runServer        # launch a dev server
./gradlew runData          # run data generators
```

A push to `main` builds a nightly jar via GitHub Actions (`.github/workflows/nightly.yml`), uploaded as a
build artifact.

# Fork note
This is a complete departure from upstream limitless, we will only merge in features we like. **All proprietary assets have been removed**

## License
* CombinedAssault
    - (c) 2026 - Present Warfactory
    - [![License](https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square)]()
* Art assets: [ASSETS.md](ASSETS.md)
    - MrNorwood (CC BY-NC 4.0), EMB4 (third-party, no license granted), LesRaisins-Studios decoration letter decals
