# Asset tools

Model format: `vehicle-pack-format.md` §6. Game reads only `extras.resourceLocation` for textures and buffers; a
plain glTF export (`uri`) => untextured in game.

## Blender add-on (`tools/blender/`)

Install: `python3 tools/blender/build_addon.py` -> `build/combinedassault_tools.zip` (extension:
`tools/blender/blender_manifest.toml`); Blender > Edit > Preferences > Get Extensions > Install from Disk. Blender 4.2+
(tested 5.2).

| task | where |
|---|---|
| open a pack model | File > Import > combinedassault pack model, or Scene tab > Import pack model. Pick `assets/<ns>/models/**.gltf` inside the pack; Export then writes back to that file |
| roles (body, part, collider, seat...) | Object tab > combinedassault |
| texture | Material tab > Set up texture (PNG): Image Texture -> Base Color, pixel-sharp; Transparency Solid / Cut-out / See-through |
| player size | Scene tab > Player standing / Player seated (at the 3D cursor; seated: cursor on the seat socket). Never exported |
| check | Scene tab > Check vehicle; red lines in Object / Material tabs |
| export | Scene tab > Export vehicle: check, write `.gltf` + `.bin` + textures, then the validator on the written file |

- Frame: 1 unit = 1 block, nose -Y, port +X, up +Z.
- Texture already in a pack => referenced in place (shared atlases, `_damaged` / `_wrecked` / `_paint` siblings keep
  working); elsewhere => copied to `textures/<model dir>/`.
- Import keeps every node / material extra; panels override only what they show.
- Colliders round-trip face for face (one glTF primitive per face). One triangulated primitive => the game regroups
  by plane and sliver triangles fail its 1e-4 flatness (EMB4 hulls are convex only to ~2e-3 per sliver).
- Material problems the exporter would hide: texture not on Base Color, Base Color through Mix nodes, second UV map,
  Mapping node, never-saved image, Alpha linked with Transparency Auto.
- Test: `blender -b --factory-startup --python "$PWD/tools/blender/test_combinedassault_tools.py"` (Flatpak:
  absolute path; its `/tmp` is private). Round-trips the Humvee and C-5.

## Blockbench plugin (`tools/blockbench/combinedassault.js`)

Install: Blockbench 5.1 desktop > File > Plugins > Load Plugin from File. Menu and side panel CombinedAssault.

| task | where |
|---|---|
| open a pack model | CombinedAssault > Import pack model. Pick `assets/<ns>/models/**.gltf` inside the pack; Export then writes back to that file |
| roles (body, part, collider, seat...) | select a group / element / locator > CombinedAssault panel |
| socket | CombinedAssault > Add socket (locator at the selected group's pivot) |
| player size | CombinedAssault > Add player reference (standing / seated, 15/16 model; seat locator selected => eye on it). Never exported |
| check | CombinedAssault > Validate vehicle |
| export | CombinedAssault > Export to pack: check, write `.gltf` + `.bin` + new textures |

- Frame: 16 px = 1 block, nose south (+Z), port east (+X), up +Y.
- Frames = groups; collider / `hit` / `hit_surface` = cube or mesh; socket = locator (`era` / `slat` / `track`: mesh). `export` off => skipped.
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
- Test: `node tools/blockbench/test_combinedassault.mjs`. Live round trip: `node tools/blockbench/compare_roundtrip.mjs <source.gltf> <source.bin> <exported.gltf>`.

## Validator (`tools/blender/ca_validate.py`)

`python3 tools/blender/ca_validate.py <model.gltf>...`; exit 1 on any error. No Blender needed. Mirrors `ModelRig`
(roles, body, frames, collider keys, hulls), GemRender materials (resourceLocation, PNG, TEXCOORD_0 only,
cutoff 0 / 0.1 / 0.5) and clip names. No body node => part / display model: rig rules skipped. Shipped pack:
53 models, 0 errors.

## Player reference

`tools/reference/player_reference.gltf` / `.obj` (`python3 tools/blender/ca_reference.py tools/reference`): standing
(+ hitbox 0.6 x 1.8, eyes 1.62) at x 0, riding pose at x 1.5; +Y up, faces +Z. Vanilla model at 15/16 = 1.875 tall.
