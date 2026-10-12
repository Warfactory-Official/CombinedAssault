# HUD

wflib JSON layouts (wflib guide Part XXVI §96): `assets/combinedassault/wf_ui/` (`layouts/<preset>`, `components/hud/*`, palette `hud`). Data: `HudSubjects` / `HudSignals`, `indirect/client/IndirectView` (`indirect.*`). Java widgets (JSON types `combinedassault:<name>`, `CaHudPresets.registerWidgets`): `client/hud/widget/`.

| preset | shown |
|---|---|
| `combinedassault:hit` | always (hit marker at the aim point) |
| `combinedassault:vehicle` | any ride: crew, compass (not third person), systems panel, alerts, weapon station, payload, hit panel, debug dump |
| `combinedassault:rotary_wing` / `fixed_wing` / `ship` | + by hull class, driver only |
| `combinedassault:scope` | back layer, scope view |

- Selection by hull class (`CaHudSource.preset`).
- Instances rebuilt per boarded hull (`smooth()` lag never sweeps from the previous hull) and after a resource reload.
- Relative scaling (reference 270): 1080p auto GUI scale = old pixel sizes; other resolutions / GUI scales keep the screen proportion. Projected marks (aim, impact, locks, hit marker, scope sight) convert GUI px via `ev.fromHost`.
- Layout units, 16:9 = 480x270: compass half-width `clamp(w/2 - 160, 40, 120)` clears top-left panels (board 146, helm 126); RWR right edge (`w - 40`), clear of rotary tape/vspeed; operator pitch ladder `w/2 + 95` (left side hit systems panel + crew list). Gap: 4:3 (w 360) still overlaps.
- Indirect board = component `hud/indirect_board` + Java `range_scale` (labels dropped on overlap need measured text) + wflib `dial`. Legend fade, shot clock, solver caches = `IndirectView` signals (stateful => Java).
- Divergences from the replaced overlays:
  - ship engine order text = commanded throttle (was the lagged one); bar still lagged.
  - weapon tab names cut by width (`..`), was by `width / 7` characters.
  - hit marker in `wflib:hud` (below chat), was in the crosshair layer.
  - board title `INDIRECT` dropped (overlapped state badge); compass narrower, RWR + pitch ladder moved (above).
  - weapons with no `reload` block (kilo torpedo tubes, MIM-104) draw no reload ring; the old aim overlay threw NPE => client crash.
- Gaps: 48 B/frame = Flywheel 1.0.6 `GlStateManagerMixin` `@Inject` `CallbackInfo` on the canvas's GL binds (external jar). RWR and radar contact loops iterate `ConcurrentHashMap` / `HashMap` views => allocate while contacts exist; scope schematic + `IndirectView` call `RotatableUnit.worldRot` (allocates).
- Cost, `REDACTED stats=1`, 1280x720, 600-frame windows, 2026-10-12 (Java presets). JSON presets: same B/frame; us/frame re-measure pending (2026-10-12 run shared the machine with a game, 1.7-2x across every stage).

| case | us/frame | B/frame |
|---|---:|---:|
| m252a1 | 31 | 52 |
| m1a2 | 31 | 52 |
| riverine | 45 | 56 |
| kilo | 66 | 53 |
| f22a | 67 | 54 |
| ah64d | 81 | 52 |

- Parity harness: `REDACTED` (every view per seat; `stats=1` => `[wflib ui]` cost per case, `stats=detail` + allocating signals, `stats=jfr` + JFR CPU + allocation samples).
