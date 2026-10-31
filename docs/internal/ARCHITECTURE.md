# Architecture

NeoForge 1.21.1, Java 21, ModDevGradle. Deps: WFLib (all wflib access via `api`, `WflibApiBoundaryTest`), AHF, GemRender (client), Sable/Create/IR/GTCEu/UltimateWeight optional.

## Systems
`combinedassault-systems.toml` (STARTUP); `ArchitectureDocTest` == `CombinedAssault.SYSTEMS`. Package root == system id (`mixin.<sys>` for mixins); `CombinedAssault` ctor = `SYSTEMS` + `packs()` host.
<!-- systems:begin -->
| system | kind | requires |
|---|---|---|
| camo | switchable | - |
| energy | switchable | - |
| cargo | switchable | wflib:magazine, wflib:physics |
| props | switchable | - |
| render | mandatory | wflib:armor, wflib:physics |
| hud | mandatory | wflib:armor, wflib:magazine |
| indirect | switchable | - |
| uav | switchable | aircraft, wflib:drone |
| weapon | mandatory | wflib:barrel, wflib:countermeasure, wflib:drone, wflib:laser, wflib:magazine, wflib:packs, wflib:probe, wflib:sight |
| ballistics | mandatory | wflib:armor, wflib:physics, wflib:probe |
| radar | switchable | - |
| airdefense | switchable | radar, wflib:probe |
| tow | switchable | wflib:physics |
| naval | switchable | wflib:physics |
| aircraft | switchable | - |
| deck | switchable | wflib:physics |
| dynamics | mandatory | wflib:physics |
| vehicle | mandatory | wflib:armor, wflib:drone, wflib:laser, wflib:nvg, wflib:physics, wflib:probe |
| pack | mandatory | wflib:barrel, wflib:magazine, wflib:packs |
<!-- systems:end -->
- Mandatory = one block (mutual deps, `SystemBoundaryTest`); leaves depend on own + `requires()` + mandatory.
- Own ids bare in `requires()`, wflib ids `wflib:`-qualified; needed-but-off => kernel auto-enables with warning.
- Off removes: items, entities, menus, payloads (`<sys>-N` registrar in `<Sys>System`), commands (`RootCommand.SUBCOMMANDS`), config, part/data/display types, gametests (`GameTestSetup.tests/testsNeeding`).
- Pack defs naming an off type skipped, one warning per system (`OffSystemDefs.OWNER`). New leaf type => add there.
- Data naming off content: `neoforge:conditions` `{"type":"wflib:system","system":"combinedassault:<sys>"}`; tag entries `required:false`. Boot with each leaf off MUST log 0 parse errors.
- Client UI naming leaf-only keys/signals ships in a conditional resource pack (`resource_packs/indirect`, `IndirectClient`); `wf_ui` has no conditions.
- `EnergySystem.active()` off => no fuel burn, no tank contents. `ContainerCraft` hold (ammo stores) stays; cargo menu needs cargo on.

## Guarded edges
Core/leaf references onto a switchable system it does not require; inert while the target is off (instanceof, data-driven, `WFSystems.enabled` guard). `SystemBoundaryTest.GUARDED`; each entry backed by leaf-off boot + gametest sweep, not proof.
- Cut instead of guard: part types (`AircraftPartTypes`, `RadarPartTypes`, `FpvPayloadUnitType.PAYLOAD`) register from their leaf.
- TODO: `vehicle.entity.AbstractVehicle` + `render`/`hud` name most leaves; split = redesign.

## Config
| file | owner | type |
|---|---|---|
| `combinedassault-vehicle.toml` | vehicle | COMMON |
| `combinedassault-vehicle-server.toml` | vehicle | SERVER |
| `combinedassault-dynamics.toml` | dynamics | COMMON |
| `combinedassault-aircraft.toml` | aircraft | COMMON |
| `combinedassault-radar.toml` | radar | COMMON |
| `combinedassault-{camo,energy,props}.toml` | own | COMMON |
- Immutable snapshot rebuilt on load. Exceptions (live values): `VehicleConfig.crewRequired()` (gametests set it), `AircraftConfig.smokeR/G/B` (modding tool screen writes them).

## Packs
- wflib `packs` host: `CombinedAssault.packs()`; defs `combinedassault/<ns>/...`; format in `vehicle-pack-format.md`.
- Type registries `pack.PackTypes` (`WFRegistry`): vehicle data, display, weapon, part unit. Built-ins from `PackSystem`; leaf types from their `<Sys>System.register`.
- Load: `CommonAssetsManager.load` collects all problems => one exception. `VehicleCatalogue.DEF_PATCHES` edits defs before parse.

## External API (wfcore)
`vehicle.api.VehicleCatalogue` {`ids`, `isKnown`, `name`, `footprint`, `summon`, `DEF_PATCHES`}; `vehicle.api.client.VehicleSlots` {`texture`, `renderQuad`}. Everything else internal.

## Testing
- Gametests: `./gradlew runGameTestServer` (`-PwithRail` + IR for flatcar); leaf off => set `<sys> = false` in `run/gametest/config/combinedassault-systems.toml`.
- Headless client: mc-harness `scenarios/ca_systems_off.mjs <repo> <sys,sys|all|none>`.
- `/combinedassault naval spawn <id> <pos>` spawns any vehicle (path shared with `naval`; harness scripts use it).
