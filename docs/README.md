# CombinedAssault docs

Mod id `combinedassault`, package `com.wf.combinedassault`. Vehicles on WFLib; hard fork of YWZJ-Studio Limitless Vehicle (upstream changes hand-ported).

| doc | scope |
|---|---|
| [vehicle-pack-format](vehicle-pack-format.md) | pack layout, sources, defs, glTF node roles, colliders, hit geometry, sockets |
| [removed](removed) | ship physics, decks, carriers, trials |
| [removed](removed) | hitches, towables, couplings |
| [removed](removed) | per-tick rigid-body step: order, solver, contact rules, rewrite status |

Tools: `tools/convert_pack.py` (old Limitless layout -> pack), `tools/gen_hulls.py` (colliders), `tools/blender/combinedassault_tools.py` (authoring add-on), `tools/bake_addon_armor.py`, `tools/model_icon.py`, `tools/paint_layers.py`, `tools/damage_textures.py`, `tools/script_clips.py`, `tools/pack_scaffold.py`.
