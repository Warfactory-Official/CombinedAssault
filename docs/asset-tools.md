# Asset tools

Model format: `vehicle-pack-format.md` §6. Shipped models are `.glb` (BIN chunk = buffer; `.gltf` + `.bin` also loads). Game reads
only `extras.resourceLocation` for textures (`uri` ignored); `uri` alone => untextured in game.

## Blender add-on (`tools/blender/`)

Install: `python3 tools/blender/build_addon.py` -> `build/combinedassault_tools.zip` (extension:
`tools/blender/blender_manifest.toml`); Blender > Edit > Preferences > Get Extensions > Install from Disk. Blender 4.2+
(tested 5.2).

| task | where |
|---|---|
| open a pack model | File > Import > combinedassault pack model, or Scene tab > Import pack model. Pick `assets/<ns>/models/**.glb` (or `.gltf`) inside the pack; Export writes `<model>.glb` (staging `.gltf` / `.bin` removed) |
| roles (body, part, collider, seat...) | Object tab > combinedassault: every §6 node key (frame limits, `follows_clips`; hit `system` / `side` / `unit` / `spin` / `health` / `rha` (`hit` only) / `swept` / `skip` / `road_wheel`; collider flags; socket `index`, light keys; scale-valued sockets show their value field) |
| texture | Material tab > Set up texture (PNG): Image Texture -> Base Color, pixel-sharp; Transparency Solid / Cut-out / See-through; damage / paint siblings listed |
| armour, hit effect, decor | Material tab: Armour plate (`armor:<id>`) / Hit effect / Decor = views of the material name (editing renames; drops a legacy `armor_<mm>`). Assign armour to faces (edit mode, plate id): per-face copy. Legacy / malformed plate token => check warning |
| socket | Scene tab > Add socket (3D cursor): `era` / `slat` quad, `sight_lens` quad, `track` loop as starter meshes; `cargo_bay` cube / `propeller` circle drawn at the value size |
| tyre hit surface | Scene tab > Make hit cylinder: circumscribed prism `hit_surface` child per selected node (= `gen_hulls.py --hit-cylinder`, regenerable by it) |
| screen | Scene tab > Add screen: `screen_<id>` quad (UV 0..1, faces the rider) + material `textureSlot`; bind in vehicle `screens` |
| lights | Scene tab > Preview lights: Blender spot per `light` socket (kind defaults resolved). Rebuilt per call; never exported |
| paint preview | Scene tab > Paint preview (dropdown = `assets/<ns>/paints/*.json`): `mix(base, base*pattern/maskMean, _paint.a)` in gamma space, `_paint_solid` swaps pattern for its mean; object-space box projection, scale `1/tile`. Nodes `ca_paint_*`; removed during Export, restored after. Live reload: 0.5 s mtime poll reloads every file-backed image |
| player size | Scene tab > Player standing / Player seated (at the 3D cursor; seated: cursor on the seat socket). Never exported |
| passenger views | Scene tab > Create passenger cameras: one camera per `seat` / `view` / `sight` / `camera` / `camera_zoomed` socket (eye = origin, -Y at yaw 0, 70 deg vertical); Previous / Next / per-socket buttons look through. Rebuilt per call; never exported |
| check | Scene tab > Check vehicle; red lines in Object / Material tabs |
| export | Scene tab > Export vehicle: check, write `.glb` + textures, then the validator on the written file |
| hardware kit | File > Import > combinedassault hardware kit, or Scene tab > Open hardware kit: pick `<pack>/combinedassault/<ns>/hardware/*.json`; its `model` imported as a pack model. Kit box: `weapons` / `fits` (comma lists), `install_ticks`, `item`, `replaces`, `station` (JSON object), belt link + pitch (empty link = no `belt`); Save kit json. Export also writes the kit json (`model` = export target when same pack); `belt` xor model `belt` sockets => warning |
| kit roles | kit open => role outside `ModelDataManager.KIT_ROLES` = check error, Object tab note |
| kit on a vehicle | Kit box: hardpoint dropdown (vehicle defs whose `hardpoint.accepts` meets `fits`; `overrides[kit].node` wins) > Preview: vehicle + part mounts imported as reference, hardpoint node rest frame = origin. Rebuilt per call; X clears; never exported |
| hand contact | Scene tab > Add hand contact: `hand` empty at the 3D cursor, child of the active object; Object tab: Hand right / left, Action (empty / `shift` / clip name) |
| arm reach | Scene tab > Arm reach: 0.586 sphere per seat socket (vehicle preview included). Rebuilt per call; never exported |

- Frame: 1 unit = 1 block, nose -Y, port +X, up +Z.
- Texture already in a pack => referenced in place (shared atlases, `_damaged` / `_wrecked` / `_paint` siblings keep
  working); elsewhere => copied to `textures/<model dir>/`.
- Import keeps every node / material extra; panels override only what they show.
- Scene tab > Merge coplanar faces: selected colliders, dissolve at 1e-3 rad (fixes the "share a plane" warning).
- Make convex hull = hull triangle planes pushed over every point, intersected; face with 2 x area or an edge
  `< 4e-4` => drop the touching plane growing the solid least. Growth reported (antenna whip ~0.04). Angle dissolve
  FORBIDDEN: bends large faces past 1e-4, leaves bevel slivers under `ConvexHull` 2 x area 1e-4.
- Colliders round-trip face for face (one glTF primitive per face). One triangulated primitive => the game regroups
  by plane and sliver triangles fail its 1e-4 flatness (EMB4 hulls are convex only to ~2e-3 per sliver).
- Object tab: mount state node names (`ammo_can`, `round@<k>`, `barrel@<variant>`) labelled.
- Material problems the exporter would hide: texture not on Base Color, Base Color through Mix nodes, second UV map,
  Mapping node, never-saved image, Alpha linked with Transparency Auto.
- Kit json edits in place (`tools/jsonedit.py put` / `remove`): unchanged keys keep their bytes, new keys appended.
- Test: `blender -b --factory-startup --python "$PWD/tools/blender/test_combinedassault_tools.py"` (Flatpak:
  absolute path; its `/tmp` is private). Round-trips Humvee, C-5, T-90, Mavic, M777, M142, m2 kit (copy under
  `build/`); vocabularies parsed from the Java sources.

## Plates (`tools/def_sockets.py plates`)

| task | command |
|---|---|
| bind report | `plates <vehicle> [--pack DIR]`: ids mesh <-> `armor.plates`; per plate faces, area, declared (or fitted) normal, mesh deviation mean / max, faces > 10 deg; exit 1 on mismatch |
| assign faces | `plates <vehicle> --assign ID --node N --box x0,y0,z0,x1,y1,z1 [--dry]`: triangles of node N whose centroid is in the box (model frame, parts at rest, = F3 `Model x y z`) |
| assign by index | `--faces i,j,..`: triangle indices of node N's mesh, primitive order |
| unbind | `--assign -` |

- Material = copy of the face's material named `<base>_armor:ID`, so texture / paint / surface tokens survive; primitive split in place (index accessor shrunk, one primitive + accessor appended); every other byte of the glTF kept. `.gltf` keeps compact JSON.
- Node whose mesh other nodes share => refused. TRAP: meshes sharing one index accessor => `--assign` on one silently deletes triangles from the other (m113 cargo hatches, centauro hatch lids); un-share accessors first; compare per-node triangle counts before / after.
- `--assign` rebinds faces already carrying another plate. Mirroring world matrix => winding reversed for the normal.
- Deviation is two-sided: meshes carry coincident back-face duplicates, so a plate binds both copies (a hit reads whichever the BVH meets first).
- Box selects by centroid: thick volumes catch inner faces; check the report's coherence / deviation.

## Blockbench plugin (`tools/blockbench/combinedassault.js`)

Install: Blockbench 5.1 desktop > File > Plugins > Load Plugin from File. Menu and side panel CombinedAssault.

| task | where |
|---|---|
| open a pack model | CombinedAssault > Import pack model. Pick `assets/<ns>/models/**.glb` (or `.gltf`) inside the pack; Export then writes back to that file |
| roles (body, part, collider, seat...) | select a group / element / locator > CombinedAssault panel: every §6 node key (frame limits, `follows_clips`, hit `unit` / `spin` / `road_wheel`, `hidden`, socket `index`, light keys, scale-valued socket `Value`) |
| socket | CombinedAssault > Add socket (locator at the selected group's pivot; `era` / `slat` / `track` / `sight_lens` as starter meshes) |
| hinge | CombinedAssault > Make hinge: `<name>_mount` group -> `<name>` group, selection moved in |
| collider from geometry | Make convex hull (selected cubes / meshes, first one's frame); Merge coplanar faces (shared face tag per plane) |
| tyre hit surface | Make hit cylinder: `hit_surface` prism per selected group (`genHulls` extras, regenerable by `gen_hulls.py --hit-cylinder`) |
| screen | Add screen: `screen_<id>` quad (UV 0..1, faces the rider) + material `textureSlot` |
| armour, hit effect, decor | Material tags: hit effect / decor, renames the texture (= material name). Plates: Assign armour plate to faces (per-face material `<stem>_armor:<id>`; `:` never in a texture name = resource path). Legacy / malformed plate token => Validate warning |
| part clip | Key part clip: start / end key (offsets from rest) on selected groups / locators, clip `<part id>[.<suffix>]` |
| lights | Preview lights: cone outline per `light` socket (kind defaults resolved). Rebuilt per call; never exported |
| player size | CombinedAssault > Add player reference (standing / seated, 15/16 model; seat locator selected => eye on it). Never exported |
| passenger views | CombinedAssault > View as passenger... (socket list), Next / Previous passenger view (Ctrl+PgDn / Ctrl+PgUp): preview camera to the socket eye, +Z local, FOV 70. Orbit leaves |
| check | CombinedAssault > Validate vehicle |
| export | CombinedAssault > Export to pack: check, write `.glb` + new textures; kit open => kit `model` synced |
| hardware kit | CombinedAssault > Open hardware kit: pick `<pack>/combinedassault/<ns>/hardware/*.json`; its `model` imported as a pack model. Hardware kit fields: `weapons`, `fits`, `install_ticks`, `item`, `replaces`, `station` (JSON), belt link + pitch (empty link = no `belt`); written in place. Validate: `belt` xor model `belt` sockets => warning |
| kit roles | kit open => panel note; roles outside `KIT_ROLES` = Validate error |
| kit on a vehicle | Preview kit on vehicle (hardpoint list as Blender): wireframe vehicle + part mounts, hardpoint node at the origin, arm reach spheres on its seats. Clear kit preview; never exported |
| hand contact | Add hand contact: `hand` locator at the selected group's pivot, inside it; panel Hand / Action |
| arm reach | Arm reach (seats): toggles a 0.586 wire sphere on each seat locator; never exported |

- Frame: 16 px = 1 block, nose south (+Z), port east (+X), up +Y.
- Frames = groups; collider / `hit` / `hit_surface` = cube or mesh; socket = locator (`era` / `slat` / `track` / `sight_lens`: mesh). `export` off => skipped.
- No body group => part / display model: rig rules skipped, part sockets may omit the interface (as the validator).
- Texture with a file under the pack => referenced in place; else written to `textures/<model dir>/<texture name>`.
- New material = its texture: name = texture name (armour / decor / effect tags go there), alpha mode from the pixels. Imported material kept per face (name, alpha, cutoff, sides, factors, normal / ORM / emissive maps).
- Import keeps every node extra; the panel overrides only what it shows. Node with mesh + children => group + `<name>_mesh` child.
- Node scale: none on groups (pushed into children and vertices); locator scale kept. Uneven parent scale over a rotation: mesh leaf takes the whole transform into its vertices; group / locator approximated, warned.
- Colliders: one Blockbench face set per glTF primitive (>4 corners: fan, one face tag); export one primitive per face, shared POSITION.
- Normals: imported per corner, kept until the face moves; else flat.
- Clips: Blockbench animations (part clips); keys = rest + offset, rotations as Euler.
- Blockbench holds one texture per file: materials sharing an image share it in the texture list.
- Flatpak sandbox: pack outside Documents / Desktop / Downloads => `flatpak override --user --filesystem=<pack> net.blockbench.Blockbench`.
- Test: `node tools/blockbench/test_combinedassault.mjs`. Live round trip: `node tools/blockbench/compare_roundtrip.mjs <source.glb> <exported.glb>`.
- Gaps vs Blender: Blender-only `posed` check, mount state node labels, material-node problems (N/A: Blockbench has no node trees). Gap: hinge axis = `<name>_mount` rotation (no cursor orientation), hit cylinder built in model axes (rotated ancestors => wrong). Gap: tools untested live in Blockbench (node tests cover the pure parts: panel keys, hull, prism, coplanar planes, tags, `.glb`, kit json edit, hardpoints, hand keys, belt sockets).

## Validator (`tools/blender/ca_validate.py`)

`python3 tools/blender/ca_validate.py [--kit] <model.gltf>...`; exit 1 on any error; `--kit`: `KIT_ROLES` only. No Blender needed. Mirrors `ModelRig`
(roles, body, frames, collider keys, hulls), `ModelDataManager` (node keys, `system`, `spin`, `rha` on `hit` only, no yaw on `pitch`; material `armor:<id>` = last token, legacy `armor_<mm>` => warning),
`VehicleLight.read`, `hand` (`hand` right / left, `action` non-empty string), `belt` (`index` 0 + 1 once each; outside a
kit => warning), GemRender materials (resourceLocation, PNG, TEXCOORD_0 only, cutoff 0 / 0.1 / 0.5, `textureSlot`
mesh UVs; image namespace without `assets/<ns>` in the pack = another mod's, unchecked) and clip names. `sight_lens` mesh: drawn, UV not required. No body node => part / display model: rig rules
skipped. Shipped pack: 75 models, 0 errors.

## Player reference

`tools/reference/player_reference.gltf` / `.obj` (`python3 tools/blender/ca_reference.py tools/reference`): standing
(+ hitbox 0.6 x 1.8, eyes 1.62) at x 0, riding pose at x 1.5; +Y up, faces +Z. Vanilla model at 15/16 = 1.875 tall.
