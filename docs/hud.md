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
- Relative scaling (reference 270): 1080p auto GUI scale = old pixel sizes; other resolutions keep the screen proportion; GUI scale below auto shrinks by scale / auto. Projected marks (aim, impact, locks, hit marker, scope sight) convert GUI px via `ev.fromHost`.
- Layout units, 16:9 = 480x270: compass half-width `clamp(w/2 - 160, 40, 120)` clears top-left panels (board 146, helm 126); RWR right edge (`w - 40`), clear of rotary tape/vspeed; operator pitch ladder `w/2 + 95` (left side hit systems panel + crew list). Gap: 4:3 (w 360) still overlaps.
- Weapon card, weapon with `barrel`: heat bar (temp / max) + wear line under the ammo bar; ammo text -> `JAMMED` (blink) / `CLEAR n%` / `BARREL n%`. Signals `barrel heat temp wear jammed clearing changing` = `barrel/client/BarrelHud`.
- Indirect board = component `hud/indirect_board` + Java `range_scale` (labels dropped on overlap need measured text) + wflib `dial`. Legend fade, shot clock, solver caches = `IndirectView` signals (stateful => Java).
- Divergences from the replaced overlays:
  - ship engine order text = commanded throttle (was the lagged one); bar still lagged.
  - weapon tab names cut by width (`..`), was by `width / 7` characters.
  - hit marker in `wflib:hud` (below chat), was in the crosshair layer.
  - board title `INDIRECT` dropped (overlapped state badge); compass narrower, RWR + pitch ladder moved (above).
  - weapons with no `reload` block (kilo torpedo tubes, MIM-104) draw no reload ring; the old aim overlay threw NPE => client crash.
