# HUD

wflib JSON layouts (wflib guide Part XXVI §96): `assets/combinedassault/wf_ui/` (`layouts/<preset>`, `components/hud/*`, palette `hud`). Data: `HudSubjects` / `HudSignals`, `indirect/client/IndirectView` (`indirect.*`). Java widgets (JSON types `combinedassault:<name>`, `CaHudPresets.registerWidgets`): `client/hud/widget/`.

| preset | shown |
|---|---|
| `combinedassault:hit` | always (hit marker at the aim point) |
| `combinedassault:vehicle` | any ride: bottom bar, compass (not third person), alerts, weapon station, hit panel, debug dump |
| `combinedassault:rotary_wing` / `fixed_wing` / `ship` | + by hull class, driver only (aircraft: flow arrow, roll wings, pitch ladder) |
| `combinedassault:scope` | back layer, scope view |

- Selection by hull class (`CaHudSource.preset`).
- Instances rebuilt per boarded hull (`smooth()` lag never sweeps from the previous hull) and after a resource reload.
- Relative scaling (reference 270): 1080p auto GUI scale = old pixel sizes; other resolutions keep the screen proportion; GUI scale below auto shrinks by scale / auto. Projected marks (aim, impact, locks, hit marker, scope sight) convert GUI px via `ev.fromHost`.
- Layout units, 16:9 = 480x270: compass half-width `clamp(w/2 - 160, 40, 120)` clears top-left panels (board 146, helm 126); RWR right edge (`w - 40`); operator pitch ladder `w/2 + 95`. Gap: 4:3 (w 360) still overlaps.
- Weapon card, weapon with `barrel`: heat bar (temp / max) + wear line under the ammo bar; ammo text -> `JAMMED` (blink) / `CLEAR n%` / `BARREL n%`. Signals `barrel heat temp wear jammed clearing changing` = `weapon/client/BarrelHud`.
- Bottom bar (all bottom-anchored, `x` = layout units):

| piece | where |
|---|---|
| systems panel (`hud/systems`) | centre, 150 wide, margin 6 |
| `hud/readouts` chips | top of the panel column, centred: drone payload lines (0.6); driver: gear (`vehicle.transmission`), rotary RPM / COL / ALT / V/S / FUEL, fixed THR / ALT / FUEL |
| radio, scope schematic (`local.scope`), crew | column right-aligned to `w/2 - 83`, bottom `h - 22gu - 4` (hidden hotbar band: Ultimate-Weight icon left of it) |
| key hints / weapon card | screen corners |

- Speed chips dropped: panel speed (green = fixed-wing `can_lift_off`). Rotary vspeed tape -> V/S chip.
- Key hints: `hud/key_hints` (bottom left, seat + hull) / `hud/key_hints_weapons` (column with `ammo_list`, above weapon card); wflib `key_hint`. Left: width `>= 440`; 4:3 hidden.
- Weapon card: `ammo_glyph` per load of `primary.round_variants`, before the round name; non-cartridge rounds none. `ammo_list` rows: same chips (max 3), `AmmoGlyph.drawChip`.
- Colours: white on black panels; state only amber/red. Palette `hud`; Java copies: `CaDraw`, `ShipWidgets`, `AmmoList`. Team colour, tip chips, cyan own-part box, probe status colours exempt.
- Indirect board = component `hud/indirect_board` + Java `range_scale` (labels dropped on overlap need measured text) + wflib `dial`. Legend fade, shot clock, solver caches = `IndirectView` signals (stateful => Java).
- Divergences from the replaced overlays:
  - ship engine order text = commanded throttle (was the lagged one); bar still lagged.
  - weapon tab names cut by width (`..`), was by `width / 7` characters.
  - hit marker in `wflib:hud` (below chat), was in the crosshair layer.
  - board title `INDIRECT` dropped (overlapped state badge); compass narrower, RWR + pitch ladder moved (above).
  - weapons with no `reload` block (kilo torpedo tubes, MIM-104) draw no reload ring; the old aim overlay threw NPE => client crash.
- Wheel arrow (`vehicle.json`, `local.wheel*`): wheeled driver, fades with actual `turnAngle` (not the wanted lock). Mouse steering = OPERATOR view, seat without weapon, outside free look (`LocalVehiclePlayer.wheelDriver`); third person stays A/D. A/D released => recentre; mouse-set lock holds.
