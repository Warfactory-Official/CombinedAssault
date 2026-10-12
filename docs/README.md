# CombinedAssault docs

Mod id `combinedassault`, package `com.wf.combinedassault`. Vehicles on WFLib; hard fork of YWZJ-Studio Limitless Vehicle (upstream changes hand-ported).

| doc | scope |
|---|---|
| [vehicle-pack-format](vehicle-pack-format.md) | pack layout, sources, defs, glTF node roles, colliders, hit geometry, sockets |
| [removed](removed) | ship physics, decks, carriers, trials |
| [removed](removed) | hitches, towables, couplings |
| [removed](removed) | per-tick rigid-body step: order, solver, contact rules, rewrite status |
| [hud](hud.md) | HUD presets on wflib UI: selection, divergences, gaps |
| [removed](removed) | fast-vehicle chunk/entity streaming: budget, rulings, status |
| [removed](removed) | driver-side prediction protocol, snapshot, what's not predicted |
| [asset-tools](asset-tools.md) | Blender/Blockbench add-ons, validator, player reference |
| [testing](removed) | commands, GameTests, harness scenarios: index only |

Tools: `tools/convert_pack.py` (old Limitless layout -> pack), `tools/gen_hulls.py` (colliders), `tools/blender/` (authoring add-on, validator; `asset-tools.md`), `tools/bake_addon_armor.py`, `tools/model_icon.py`, `tools/paint_layers.py`, `tools/damage_textures.py`, `tools/script_clips.py`, `tools/pack_scaffold.py`, `tools/def_sockets.py`, `tools/split_stores.py`.

`tools/internal/`: one-off per-vehicle MCHeli/EMB4/warty staging scripts, `REDACTED`, dev test-harness drivers (`REDACTED`, `REDACTED`, `REDACTED`, `REDACTED`), profiling (`REDACTED`, `REDACTED`), `REDACTED`. Not part of the general content-authoring path; a new vehicle pack does not need them.
