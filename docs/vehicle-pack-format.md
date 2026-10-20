# Vehicle pack format

Every field the mod reads from a pack: unit, default. Defaults from the deserialisers, not from shipped content.

---

## 1. Where a pack lives

### 1.1 Layout

```
<pack>/pack.mcmeta
<pack>/combinedassault/<ns>/vehicles/<path>.json      vehicle def (§3)
<pack>/combinedassault/<ns>/weapons/<path>.json       weapon def (§5)
<pack>/combinedassault/<ns>/hardware/<path>.json      hardware kit (§21)
<pack>/combinedassault/<ns>/decorations/<path>.json   {model, icon} (§6.8)
<pack>/assets/<ns>/models/**.gltf|.bin|.glb           vehicle glTF: drawn, rigged, collided, hit (§6)
<pack>/assets/<ns>/textures/**.png                    model images, slot icons, paint masks, damage tiles
<pack>/assets/<ns>/animation_controllers/<id>.json
<pack>/assets/<ns>/scripts/<id>.js
<pack>/assets/<ns>/paints/<name>.json
<pack>/assets/<ns>/sounds.json + sounds/**.ogg, lang/<locale>.json
<pack>/data/<ns>/recipe/<id>.json                     vehicle printing (§6.2)
```
- Def id = `<ns>:<path>`, `<path>` = file path under the type dir minus `.json`. Several namespaces per pack; several packs per namespace.
- Pack = also a vanilla resource + data pack (lang, sounds, recipes, tags merge as usual).

### 1.2 Sources

| origin | location |
| --- | --- |
| jar | `combinedassault_packs/<dir>/` in any mod jar; built-in = `combinedassault_packs/combinedassault` |
| directory | `<gamedir>/combinedassault/packs/<dir>/` |
| archive | `<gamedir>/combinedassault/packs/<name>.zip` |

- Read in place, never copied. Pack id clash => load error.
- `/reload`: packs rescanned, defs reparsed, clients sent the new list. Any def/model error => load error naming every problem.
- Client <-> server: sha256 per pack in the configuration phase; mismatch => client rereads its packs once; still mismatched => disconnected.
- Old layout (`vehicle_pack.meta.json`, `display/`, Bedrock structure + hull json) -> this one: `tools/convert_pack.py <old> <new>`.

### 1.3 `pack.mcmeta`

| field | type | req | notes |
| --- | --- | --- | --- |
| `pack.pack_format` | int | y | 34 |
| `pack.description` | string | y | creative tab title: `[CA] <Name>` |
| `combinedassault.id` | string | y | handshake key; unique across installed packs |
| `combinedassault.requires` | string[] | n | pack ids this pack references; missing => load error |
| `combinedassault.license`, `.authors` | | n | informational |

---

## 2. Conventions

**Vec3** = `[x, y, z]`, never an object.

**Frame**: blocks, right-handed, `+Z` forward, `+Y` up, `+X` **port (left)**. glTF bow on `+Z` draws unmirrored. Offsets measured from the vehicle origin (glTF `body` node).

**Ingredients**: item id string or vanilla `{"item": …}` / `{"tag": …}`.

**Names**: vehicle = translation key `entity.<ns>.<path>`; part = its `name`.

**Enums are not spelled consistently**: some lower-case names, some Gson's upper-case constant. Each enum field states its values.

---

## 3. The vehicle file — `combinedassault/<ns>/vehicles/<path>.json`

### 3.1 Type

`type` selects the deserialiser and the physics model. One of:

| `type` | model |
| --- | --- |
| `combinedassault:generic` | no drivetrain; parts and weapons only |
| `combinedassault:wheeled_vehicle` | force-driven ground vehicle |
| `combinedassault:tracked_vehicle` | acceleration-driven ground vehicle, with track geometry |
| `combinedassault:rotary_wing_vehicle` | helicopter / multirotor |
| `combinedassault:fixed_wing_vehicle` | aeroplane, with an aerodynamic model |
| `combinedassault:ship` | surface ship or submarine; held up by the water, steered by a rudder. See [§13](#13-ships) |
| `combinedassault:vessel_vehicle` | upstream's small boat: buoyancy + forward/backward force in water. `density > 1` forced to `0.3` |

### 3.2 Common fields

| field | type | default | notes |
| --- | --- | --- | --- |
| `max_health` | float | `100` | |
| `model` | id | *required* | glTF asset, `<ns>:models/…/x.gltf` ([§6](#6-model-display-recipes)) |
| `display` | object | — | client half: icon, controller, sounds ([§6.1](#61-display)) |
| `hitbox` | bool | `true` | `false` => no hit mesh: colliders are the hit surface, default pools only |
| `center_offset` | Vec3 | `[0,0,0]` | the point the hull rotates about, and the origin of the deck frame; glTF `pivot` socket |
| `parts` | array | `[]` | see [§4](#4-parts) |
| `uav` | bool | `false` | operated remotely (wflib PDA, UAVs app); the operator's body stays behind, standing; no storage over the link |
| `autonomy` | object | absent | `uav` only; wflib autopilot body — [§19](#19-autonomy) |
| `pickup` | bool | `false` | probe hull `Pick up` -> spawn item with save data (`VehicleState`; autonomy dropped, engine off); crewed / operated / carried / wrecked => refused |
| `loadouts` | array | `[]` | payload configurations (modding tool) — [§20](#20-loadouts) |
| `can_wade` | bool | `false` | |
| `with_warning_receiver` | bool | `false` | carries an RWR |
| `protect_passenger` | bool | `true` | |
| `experimental` | bool | `false` | |
| `view_info` | object | — | [§3.3](#33-view_info) |
| `energy_info` | object | — | fuel or electricity — [§3.4](#34-energy_info), [§10](#10-fuel-and-electricity) |
| `physics_info` | object | — | [§3.5](#35-physics_info) |
| `defense_stats` | object | — | [§3.6](#36-defense_stats) |
| `armor` | object | — | [§3.7](#37-armor) |
| `storage` | object | — | [§3.8](#38-storage) |
| `addon_armor` | array | `[]` | ERA/slat mount points: [§16](#16-era-and-slat) |
| `cargo` | object | — | vehicle hold: [§17](#17-cargo-holds-and-parachutes) |
| `mount_points` | array | `[]` | [§3.9](#39-mount_points-and-mountable) |
| `mountable` | object | — | [§3.9](#39-mount_points-and-mountable) |
| `attributes` | object | — | per-type and required for all but `generic`, which has none — [§3.10](#310-attributes-per-type) |

- Link: synced `LINK` = wflib `Datalink` from the operator's parked body to seat 0 sight (`UavCamera.RANGE` 4000). Picture `Degrade`, `DroneCamHud` meter. Lost => inputs neutral (both sides), operator fire refused, `autonomy` body flies its program or holds. Leaving a remote UAV with the PDA in hand => its feed full screen; attack there re-CONNECTs. EMP / portable jammer => link 0 (wflib `Datalink`). wflib `drone_net` pins UAVs <= 25 kg (`UavCamera.NET_MAX_KG`).
- UAV controls: leave = `leave_vehicle` (sneak = DOWN); payload arm / safe = `arm_payload`, trigger = vehicle shoot. `free_camera` toggles on UAVs (held elsewhere): mouse turns the seat 0 sight only, heading held, Q/E still yaw. Feed lens = seat 0 `sight` socket (Mavic: gimbal camera face).
- UAVs = wflib PDA Drones app via `RemoteBody` (`uav/UavTablet`; `uav_links` component and CA app removed, no migration). Sneak + use PDA on a UAV => wflib `CHANNELS` (cap = wflib camera channel config). Feed: wflib `CameraSources`, kind `MOUNTED`, lens = seat 0 sight, else view. Entry: systems cells, ammo, fuel/charge, link; CONNECT = remote ride (side's allies, a free seat). `autonomy` => DISPATCH to it directly.

### 3.3 `view_info`

| field | type | default |
| --- | --- | --- |
| `third_person_center_offset` | Vec3 | `[0,3,0]` |
| `third_person_distance` | float | `3` |
| `third_person_center_offset_zoomed` | Vec3 | `[0,5,0]` |
| `third_person_distance_zoomed` | float | `0` |
| `passenger_view_rot.rot_by_vehicle_in_third_person` | bool | `false` |
| `passenger_view_rot.rot_by_vehicle_in_operator` | bool | `true` |

- Boarding => first person (operator view = seat part's `view` socket); `V` / F5 cycle third person / scope; vanilla perspective pinned first person while riding. First-person eye must sit outside opaque geometry: no cockpit interior => above the skin.
- Aircraft operator view (fixed wing incl. UAVs, manned rotary): eye fixed in the airframe. Pilot mouse: fixed wing = stick (x roll, y pitch; recentres), rotary = commanded pitch / heading. `free_camera` (held; UAV toggles) turns the head, stick neutral; release recentres. Third person keeps mouse-aim.
- Rotary UAV: `V` / F5 = lens at seat 0 `sight` socket (world-frame look, horizon level, steers as third person) <-> third person; no SCOPE view. Sighted lens = scope: `switch_scope` / `magnification_change` zoom ×2 steps to `zoom_max` then back (mouse / zoom), `cycle_sight_mode`, reticle at screen centre (station may lack turn groups), range; FPV flight mode => §8.2 cameras.

### 3.4 `energy_info`

| field | type | default | notes |
| --- | --- | --- | --- |
| `energy_type` | string | `"fuel"` | `"fuel"` burns a fluid; `"electric"` holds a charge. Also accepts `electricity`, `battery`, `ev`, `fe` |
| `energy_capacity` | float | `80` | energy units. 1 unit = 1 kg fuel = 1 mB, and adds 1 kg of mass |
| `energy_consumption_per_tick` | float | `0.001` | units per tick at full power, before fuel efficiency |
| `fuels` | array | `[]` | which fluids burn and how well — [§10.2](#102-declaring-fuels). Empty inherits the `fuelNameWhiteList` config |
| `tank_capacity` | int | — | tank size in mB, if you would rather say it that way. Overrides `energy_capacity` |
| `fe_per_unit` | int | `20` | electric only: Forge Energy worth one unit, so the buffer holds `energy_capacity × fe_per_unit` FE |
| `charge_rate` | int | `4096` | electric only: ceiling on FE/t going in |
| `energy_density` | float | `1` | **unused** — read by the loader, consumed by nothing |
| `engine_particle_offsets` | Vec3[] | `[]` | exhaust emitters |

`energy_capacity` and `tank_capacity` are the same number. Declare one.

### 3.5 `physics_info`

SI: mass kg, force N. Per tick `Δv = F / (mass × 400)`.

| field | type | default | notes |
| --- | --- | --- | --- |
| `mass` | float | `1000` | the **curb weight**, kg. Total mass is this plus fuel plus cargo — see [§7](#7-mass-and-cargo-weight) |
| `density` | float | `8` | drives buoyancy against the displaced fluid |
| `friction` | float | `2000` | N of contact friction |
| `suspension_response` | float | `2` | drive/brake pitch and roll gain on `suspension` parts; clamped at 6° pitch, 8° roll of static spring deflection. Tumbling on: tyre load transfer (`m·a·h`, `a` ≤ 0.8 g) wins when larger => hard cornering can roll a tall hull |
| `liquid_damping` | float | `0.1` | |
| `center` | Vec3 | `center_of_mass` socket, else volume centroid of the solid colliders (rest pose) | centre of mass, blocks, vehicle frame. Aircraft: ahead of the main-gear box's front face, else a nose-up touch rests on the tail for good (pitch eases level only under `atan(0.5/depth)`). Inertia: uniform density over the solid colliders ([§6.5](#65-colliders)) about this point |
| `can_destroy_block` | bool | `false` | |
| `can_tumble` | bool | `true` | `false` (or config `tumbling = false`) => no spin from impacts or rams; sprung hulls snap level past 75° when config `selfRighting` |
| `radar_cross_section` | float | `1` | |
| `destroy_explosion_velocity` | float | `0.5` | |

### 3.6 `defense_stats`

Also valid on an individual part, where it overrides the vehicle's.

| field | type | default |
| --- | --- | --- |
| `damage_threshold` | float | `50` |
| `impact_multiplier` | float | `0.1` |
| `impact_speed_threshold` | float | `10`; aircraft `5` |
| `damage_transfer_coefficient` | float | `1` |
| `laser_resistance` | float | `1` | scales mass/armour term of wflib `LaserDamage` |

Impact: `dv` = contact-cancelled velocity (m/s). `dv > impact_speed_threshold` => `0.5 * mass_t * dv^2 * impact_multiplier` at the contact centroid (struck system included).

### 3.7 `armor`

mm RHAe vs kinetic rounds, slope included. **Every hull face zero => not armoured**: stock damage model.
- Hull face = main collider face the shot line entered through; struck point outside that box + 0.25 m => dominant vehicle axis of the reversed shot. LOS `value x wear / cos(shot, face axis)`.
- Part listed in `parts`: its faces, face = dominant axis of the reversed shot in the part's rest frame (turns with the turret).
- Precedence: `armor_<mm>` material > first matching zone > part faces > hull faces.
- Real plate (`armor_<mm>`, `plate` zone): LOS `mm x wear / cos(shot, mesh normal)`.
- Mesh normal: ricochet always. Gap: surface past `ricochet_angle` (M1A2 upper glacis ~79 deg) bounces anything not overmatching the LOS.
- F3: plate under the crosshair (face, part, zone, KE/CE, model coords at rest); F3+B: hit mesh coloured by KE LOS from the camera.

| field | type | default |
| --- | --- | --- |
| `front` / `side` / `rear` / `top` / `bottom` | float | `0` | hull, KE |
| `ce` | faces | none | vs HEAT / tandem; face 0 => KE value |
| `parts` | map part id -> faces (+ `ce`) | `{}` | part id must exist |
| `zones` | list | `[]` | first match wins |
| `ricochet_angle` | float | `70` | degrees from the normal past which a shot skates off, unless overmatched; WFLib KINETIC / SMALL_ARMS rounds then fly on deflected (WFLib guide §29e; shooter's readout RICOCHET), others do no damage |
| `wear_floor` | float | `0.6` | fraction of the plate a fully worn region keeps |

Zone: `name`, `min` / `max` (glTF model coords, part at rest), `ke` (> 0), `ce` (0 => `ke`), `part` (listed in `parts`; absent => hull), `face` (absent => any), `plate` (false). Measure with F3 `Model x y z`.

Shipped values: open-source estimates (fprado armorsite M1A2 SEP KE 940 / CE 1320-1620 turret, hull 560-650 / 510-1050; Wikipedia T-90 / T-90A with Kontakt-5 800-910 / 1150-1450; Stryker 14.5 mm front, 7.62 mm all round; Centauro 25 mm front, 14.5 mm all round; M1151 armour kit vs 7.62 mm). Merkava 4 and T-84 Oplot: extrapolated, no consistent public figures.

- Wear per region (6 faces of the main collider x 3x3 cells, `ArmorRegions`); thickness `nominal x lerp(1 - wear, wear_floor, 1)`. Hull health no longer thins armour.
- Region durability `4 x nominal mm` points; every positioned hit adds `damage x share`: rounds 0.3, blasts 0.2 (+50% to same-face neighbours), thermite 1.2 (+35%), collisions 0.05 (+50%), other 0.2.
- Probe: node per worn face (lowest cell %); "Patch armour" = one 25% band off the face, hull tool, 3 s.
- Thermite (WFLib `ThermalAttackEvent`): hull point nearest the charge takes `heat` as `combinedassault:thermite` (no threshold, no armour answer, struck system); charge low and outboard under a track => that track x1.5.
- Barrel charge: weapon part with a member >= 75 mm (round in it, else reload default) => muzzle probe node while a thermite charge is carried; 1.5 s to drop it in. After its fuse: part health 0, sparks out of the muzzle, `LOADER` seat occupants burn (`combinedassault:breech_fire` passes `protect_passenger`).

### 3.8 `storage`

| field | type | default | notes |
| --- | --- | --- | --- |
| `slots` | int | `54` | **any count from 0 to 9999.** `0` means no hold at all |
| `cargo_mass_per_kg` | float | `1` | physics kg per kg carried; `0` opts out of load-dependent handling |
| `max_payload` | float | `0` | cargo kg; past it engine output × `1/overload²`, HUD `OVERLOADED n%`; `0` = no limit |

- Open: hull probe node "Open storage" (on foot); sneak + empty-hand click (not UAVs); wreck: any click. Ride-inventory key over a UAV link: refused.
- Screen sized to the hold (5 slots = one row of 5, 54 = six rows); deeper => widens in steps of 9 columns, then scrolls (wheel, shift = window, bar drag). Shift-click fills the whole hold.

Changing `slots` on a pack that is already in a world resizes existing vehicles on load. Growing
keeps everything; shrinking drops whatever no longer fits rather than spilling it into the world.

### 3.9 `mount_points` and `mountable`

A **mount point** is a declared bracket on this vehicle where another vehicle can be bolted down —
a mortar on a pickup bed. This is not the same as a deck: a deck is geometry another vehicle parks
itself on and can be shaken off, whereas a mount is stated by the pack and holds through the engine
starting, someone taking the controls, and the vehicle being shot. Only an explicit unmount, or the
carrier being destroyed, releases one.

`mount_points[]`:

| field | type | default | notes |
| --- | --- | --- | --- |
| `id` | string | `""` | unique within the vehicle; also what a mounted vehicle records to find it again |
| `offset` | Vec3 | `[0,0,0]` | where the mounted vehicle's origin sits, in the carrier's frame |
| `yaw` / `pitch` / `roll` | float | `0` | facing relative to the carrier, in degrees |
| `accepts` | string[] | `[]` | vehicle ids, or `#tag` to match a tag the mountable declares. Empty accepts anything mountable |
| `max_mass` | float | `0` | heaviest `physics_info.mass` this point takes; `0` is no limit |

`mountable`:

| field | type | default | notes |
| --- | --- | --- | --- |
| `enabled` | bool | `false` | opt-in. A vehicle that says nothing cannot be mounted anywhere |
| `tags` | string[] | `[]` | names a point can match with `#tag` |

Placing: hold the vehicle's spawn item and right-click the carrier while looking at the point. The
point has to be within about 2 blocks of your line of sight, so two brackets a block apart are still
two brackets. Removing: sneak and right-click the *mounted* vehicle with an empty hand.

A mount is written into the mounted vehicle's save data and re-established when its carrier turns up
again after a reload. Mounting a vehicle onto something it is already carrying is refused.

A mounted vehicle keeps ticking its fuel and power even though its physics are off, so an emplaced
weapon can spin up its traverse and fire. What it does not do is solve movement — its pose is the
carrier's.

#### Worked example: SPG-9 on a carrier deck

`spg9` ships `"mountable": {"enabled": true, "tags": ["emplacement"]}`; no shipped land carrier has a point. Carrier side:

```json
"mount_points": [
  { "id": "rear_deck", "offset": [0, 1.78, -1.7], "accepts": ["#emplacement"], "max_mass": 200 }
]
```

`offset` y = deck top (SPG-9 origin = tripod feet).

### 3.10 `attributes`, per type

**`wheeled_vehicle`**

| field | type | default |
| --- | --- | --- |
| `brake_force`, `forward_force`, `backward_force` | float | — | N |
| `max_speed_forward`, `max_speed_backward` | float | — |
| `turn_step`, `max_turn` | float | — |
| `track_size` | float | `0.1` |

**`tracked_vehicle`**

| field | type | default |
| --- | --- | --- |
| `brake_acceleration`, `forward_acceleration`, `backward_acceleration` | float | — |
| `max_speed_forward`, `max_speed_backward` | float | — |
| `turn_acceleration`, `max_turn` | float | — |
| `track_size` | float | `0.3` |

Both ground types also take a top-level `landing_gear_part_id` where applicable.

**`rotary_wing_vehicle`** — plus top-level `landing_gear_part_id`

| field | type | default | notes |
| --- | --- | --- | --- |
| `main_rotor_force` | float | `13720` | N |
| `ceiling` | float | `256` | |
| `x_rot_speed_acceleration` / `_max` | float | — | pitch rate authority and cap |
| `y_rot_speed_acceleration` / `_max` | float | — | yaw |
| `z_rot_speed_acceleration` / `_max` | float | — | roll |
| `max_air_speed` | float | — | blocks/tick; arcade drag reaches it at 20° nose-down |
| `fast_roping` | bool | `false` | |
| `fast_roping_door_id` | string | — | |
| `fpv` | object | absent | acro control; absent means the stock helicopter model — [§8](#8-multirotor-and-fpv-flight) |
| `multirotor` | object | absent | multirotor force law replaces `main_rotor_force`/arcade aero — [§8](#8-multirotor-and-fpv-flight) |

**`fixed_wing_vehicle`** — plus top-level `landing_gear_part_id`, `airbrake_part_id`, `thrust_part_id`

| field | type | default |
| --- | --- | --- |
| `thrust` | float | `8000` | N |
| `thrust_k` | float | `1.5` |
| `ceiling` | float | `512` |
| `x_rot_input_step` | float | `0.2` |
| `y_rot_input_step` | float | `0.5` |
| `z_rot_input_step` | float | `0.2` |
| `air_drag_k_min` | float | `2` | N per (b/s)² |
| `air_drag_k_max` | float | `8` | N per (b/s)² |
| `lift_to_drag_k` | float | `6` |
| `angle_of_attack_min` | float | `-10` |
| `angle_of_attack_max` | float | `25` |
| `x_rot_input_drag_k` | float | `1` |
| `y_rot_input_drag_k` | float | `0.25` |
| `z_rot_input_drag_k` | float | `0.125` |
| `turn_rate_by_speed` | float | `0.4` |
| `x_turn_rate` | float | `2` |
| `y_turn_rate` | float | `3` |
| `z_turn_rate` | float | `8` |
| `vtol_turn_rate` | float | `0` | hover pitch/roll floor: share of `x/z_turn_rate` × power × \|sin(thrust part pitch)\| (reaction controls); needs `thrust_part_id`. Hover: `thrust` > 9.8 × mass |
| `vortex_offsets` | Vec3[] | `[]` |
| `afterburner_offsets` | array | `[]` | each `{"offset": Vec3, "scale": float}` |
| `aerobatic_smoke_offsets` | Vec3[] | `[]` |

- `rotor` systems (props, proprotors): `thrust` × Π effectiveness. Drawn under a part frame => part hit, no pool: tilting rotors = body nodes posed by script (`mv22`).

Takeoff, gear down, full fuel (m = mass + fuel, v in m/s, k(θ) = k_min + (k_max − k_min)·sin θ):
- ground cap `v² = (thrust − friction) / (k_min·(1 + gear drag_k))`; liftoff at 10° `v² = 9.8·m / (k(10°)·(lift_to_drag_k + 0.8))`. Liftoff ≥ cap => never leaves the ground.
- Upstream jets: `k_min ≈ m/700`, `k_max = 4·k_min`, `friction = 2·mass` => 150–300 block roll, top `√(thrust/k_min)` 60–87. `friction` is a constant force => thrust/weight < ~0.45 needs less than `2·mass` (B-2, C-5, A-10).

---

## 4. Parts

`parts` is a flat list. Each entry has a `type` and then the fields of the base part plus whatever
that type adds — there is no nested `data` object.

### 4.1 Part types

| `type` | what it is |
| --- | --- |
| `combinedassault:generic` | a plain part; seats are generic parts with `is_seat` |
| `combinedassault:switchable` | a part with an on/off state; probe node like `door` |
| `combinedassault:rotatable` | a part that turns; adds `rot_info` |
| `combinedassault:door` | adds `door_for_seat_id`; probe node (look + use / G, on foot); part click never toggles; destroyed = jammed. Node name: `name` if a lang key, else `part.combinedassault.<id>`, else id spelled out |
| `combinedassault:landing_gear` | adds `drag_k` |
| `combinedassault:airbrake` | adds `drag_k` |
| `combinedassault:flaps` | notched deflection = part xRot; §4.6 |
| `combinedassault:weapon_bay` | a bay that opens before its weapons fire |
| `combinedassault:missile_holder` | a pylon; adds `accepts`, `capacity` |
| `combinedassault:weapon` | a crewed weapon station |
| `combinedassault:auto_weapon` | an automatic one |
| `combinedassault:radar` | adds the radar block |
| `combinedassault:thrust` | a steerable thrust source; takes `rot_info`. Pitch -90 = thrust up; pilot steps it 5° per functional up/down |
| `combinedassault:variable_sweep` | wing whose yaw follows airspeed; `rot_info` speed; its bone's hull/hits turn with it |
| `combinedassault:linked_hinge` | bone swung open while another switchable part is on (ramp with its doors); `rot_info` speed |
| `combinedassault:rope` | a winch; adds `max_length` |
| `combinedassault:suspension` | sprung wheel; `part` frame = wheel, its parent frame the sprung one; probe = the frame's colliders |
| `combinedassault:track` | track geometry and a `suspension` of its own; adds `tracks`, `track_width`, `road_wheels` |
| `combinedassault:payload` | drone ordnance hardpoint |

### 4.2 Fields every part has

| field | type | default | notes |
| --- | --- | --- | --- |
| `id` | string | *required* | unique within the vehicle |
| `name` | string | `part.combinedassault.default_name` | translation key |
| `max_health` | float | `-1` | `-1` means it cannot be destroyed separately |
| `defense_stats` | object | inherits | as [§3.6](#36-defense_stats) |
| `component` | string | inferred from `id`/type | a system kind ([§6.6](#66-hit-geometry) table); `generic`/`crew` = not a system |
| `render_bone` | string | `suspension`: its `part` frame node; else none | glTF node that is this part: hidden when detached (drawn by the part entity instead); suspension travel moves it |
| `detachable` | bool | `true` | may be blown off as its own entity; `suspension` at 0 health on a live vehicle pops off, repair refits |
| `is_seat` | bool | `true` | **defaults to true** — a part that is not a seat must say so |
| `seat_rot` | float | — | |
| `seat_offset` | Vec3 | `[0,0,0]` | where the rider's eyes go |
| `dismount_offset` | Vec3 | none | |
| `pose` | string | driver seat: `drive` (`pilot` on aircraft); else `sit` | rider pose: `sit` / `drive` / `pilot` / `stand` / `kneel` (`assets/combinedassault/player_animator/seat_*.json`, AHF pose set: drawn = hit rig); unknown => load error |
| `protect_passenger` | bool | vehicle `protect_passenger` | seat override; `false` on exposed stations (shipped `stand` seats) => AHF rig hits land |
| `passenger_can_use_item` | bool | `false` | |
| `owner_view_offset` | Vec3 | none | camera position when operating this part; weapon => load error (`operator_view_offset`) |
| `sensor` | string | none | seat: weapon part id whose sight it looks through and slews (SCOPE; WSO); fires nothing; reticle = boresight. Spotter seated => owner's SCOPE view-only, other views stop aiming it. `crew.required` => only this seat aims the station (empty => holds); station seat fires (SPG-9: `sight` aims, `gun` fires + loads) |
| `crew_roles` | enum[] | derived | `driver`, `gunner`, `commander`, `loader`; gate stations under server config `crew.required`; driver seat always. Absent => seat 0 `driver` (+ `gunner` if a weapon station), other weapon seat `gunner`, else `commander`. Driver = first occupied `driver` seat (`crew.required` off: none occupied => seat 0 drives too); boarding takes an empty `driver` seat first; free seat probe node boards that seat |
| `display_id` | id | — | |
| `display_offset` | Vec3 | — | |
| `mounts` | object[] | `[]` | `{"model": glTF id, "node": vehicle glTF node}` each: separate models carried by the part (e.g. shield on the yaw node + gun on the pitch node). Grafted under `node`: drawn (own instance, vehicle paint), hit ([§6.6](#66-hit-geometry)), sockets read ([§6.7](#67-sockets)). Unknown/duplicate `node` => load error. Kit mounts: added by `hardpoint` ([§21](#21-hardware-kits)), never authored. Mount clips appended to the vehicle's ([§6.4](#64-part-clips) naming), driving the mount instance |
| `sub_part_unit_ids` | string[] | `[]` | parts carried by this one |

### 4.3 `rot_info` — `rotatable`, `weapon`, `auto_weapon`, `thrust`

| field | type | default |
| --- | --- | --- |
| `x_rot_speed` / `y_rot_speed` | float | `0` |
| `x_rot_max` | float | `90` |
| `x_rot_min` | float | `-90` |
| `y_rot_max` | float | `+MAX` (free traverse) |
| `y_rot_min` | float | `-MAX` | arc may cross ±180 (rear mount `110`..`250`) |

- Limits also from frame node keys ([§6.7](#67-sockets)): `x_rot_*` on the `pitch` frame (none: `part`), `y_rot_*` on the `part` frame. Json wins.
| `x_rot` / `y_rot` | float | `0` | resting angles |
| `need_power` | bool | `true` |

### 4.4 Weapon stations — `weapon`, `auto_weapon`

| field | type | default | notes |
| --- | --- | --- | --- |
| `weapons` | array | `[]` | see [§4.5](#45-the-weapons-list); with `hardpoint` => load error (kits bring them) |
| `hardpoint` | object | absent | swappable gun kits, fitted in the field: [§21](#21-hardware-kits) |
| `bolts` | array | none | each `{"offset": Vec3, "barrel_length": float, "x_rot": float, "y_rot": float}` — the muzzles |
| `ammo_capacity` | int | `-1` | `-1` is unlimited |
| `firing_mode` | enum | `ripple` | `ripple`, `salvo`, `full_salvo` |
| `cold_launch_time_tick` | int | `0` | ejects before ignition when non-zero |
| `cold_launch_velocity` | Vec3 | `[0,-1,0]` | |
| `parent_weapon_unit_aim` | bool | `false` | slaved to the parent station's aim |
| `optical_sight_offset` | Vec3 | `[0,0,0]` | |
| `operator_view_offset` | Vec3 | `[0,0,0]` | |
| `operator_on_weapon_unit` | bool | `true` | |
| `fire_control_sensor_type` | enum | `none` | `none`, `ir`, `rf`, `eo`, `loc`, `ccip`. `loc`: no shot until coordinates are entered (`N`) |
| `indirect_fire` | object | absent | absent => direct fire only. `{}` enables laying ([§9](#9-indirect-fire)); `map` (`false`): operator orders fire missions on the map; `launch_elevation` (`45`): degrees up a self-guided round leaves at |
| `air_defense` | object | absent | interceptor launcher: hull node settings, automatic interception, `R` lock pick ([§18](#18-air-defence)) |
| `optical_sight_type` | enum | `none` | `none`, `operator`, `optical_scope`, `crt`. `crt` scope => WFLib `wf_cam_optical` chain; a UAV gets it in every non-third-person view |
| `with_stabilizer` | bool | `false` | |
| `with_focus_locker` | bool | `false` | |
| `with_thermal_imager` | bool | `false` | `crt` only; WFLib `wf_cam_thermal` chain |
| `loading` | enum | `self` | `self`, `autoloader`, `manual`; read only under `crew.required`. `manual`: no `loader` aboard => no reload. `autoloader`: loader aboard => reload * `crew.loaderReloadMultiplier`. Loader aboard on either => ammo type (multi switch) is the loader's, cycling every served station |
| `with_night_vision` | bool | `false` | `crt` only; WFLib `wf_cam_intensifier` chain (image intensifier) |
| `with_ir` | bool | `false` | `crt` only; WFLib `wf_cam_ir` chain (active IR: own illuminator, sees through smoke). Illuminator synced per seat (`AbstractVehicle.IR_SIGHTS`) => other players' goggles see it. Cycle key: optical -> thermal -> night vision -> IR, unfitted skipped |
| `zoom_min` / `zoom_max` | float | `1` / `8` | |
| `crosshair_style` | enum | `circle` | `none`, `circle`, `square`, `cross`, `big_cross` |
| `handling` | enum | `remote` | `manual`: operator's own recoil, aim kicked by round momentum (`backblast` => x0.1), recovers; `remote`: visual jolt only. Local operator, client only. `manual` + `sight` socket + (`operator` sight or `optic`) => SCOPE = ADS: eye operator view -> `sight` (rides the pitch frame) over 0.25 s, world FOV / `zoom` x `max(1, fov / 70)`; `magnification_change` (RMB) tap toggles, hold = x1.4 zoom (MineAssault controls) |
| `optic` | object | absent | ADS picture-in-picture on the `sight_lens` mesh (WFLib `PipLens`): `zoom` float[] non-empty (magnification levels), `reticle` RL png (absent: duplex), `reticle_size` MOA (`<= 0`: lens fill), `reticle_zoom` (second focal plane), `mask` RL png, `eye_relief` m (absent: `sight` -> lens), `objective` m (picture pass origin ahead of the eye; clears the kit's own mesh, Flywheel-drawn => not hideable) |

### 4.5 The `weapons` list

Entry: string (one weapon id) or object. Array form REMOVED: ammo kinds = one weapon's `reload.ammo` (§5.3).

- **object**:

  | field | type | notes |
  | --- | --- | --- |
  | `id` | id | one weapon |
  | `part_unit_id` | string | the part the weapon is actually mounted on |
  | `weapon_bay_unit_id` | string | a bay that must open first |
  | `secondary` | bool | fired by the secondary trigger |
  | `full_salvo` | bool | |
  | `save_id` | string | defaults to `part_unit_id`; keeps ammo state stable across pack edits |
  | `round_bones` | string[] | model bones showing loaded rounds, firing order; bone i shown while `i >= bones - remaining`. Loaded round with a WFLib airframe, not `reload.ammo`'s default => that airframe at the bone (origin = round centre, +Z = nose), baked mesh hidden; else baked mesh. Name not a model node => load error. Jets: `store_<part>_<i>` nodes (`tools/split_stores.py`) |

### 4.6 Other part types

| type | field | type | default |
| --- | --- | --- | --- |
| `door` | `door_for_seat_id` | string | — | exit: door's `dismount_offset` (feet) else pivot − eye/2; nearest door serves seats without their own |
| `landing_gear`, `airbrake` | `drag_k` | float | `0.5` |
| `missile_holder` | `accepts` | enum[] | all | any of `SMALL`, `MEDIUM`, `LARGE`, `HUGE`, `POD` (MLRS cell), `POD_LARGE` (ATACMS) — upper case; item tags `wflib:missile/<size>`; `[]` = loads nothing |
| | `capacity` | int | station count | slots; slot i = `station` socket i ([§6.7](#67-sockets)); slots past the last station: internal (no draw, launch from the shot). < station count => load error |
| | `pods` | ingredient | — | WFLib pod items it seats; set => `accepts` unused, slots = seated pod's tubes ([§15](#15-pylon-missiles)) |
| | `default_pod` | item id | — | pod seated, empty, at spawn; not a pod => load error |
| | `pod_swap_time` | int | `600` | ticks, crew-scaled |
| `rope` | `max_length` | float | `20` |
| `variable_sweep` | `sweep_angle` | float | `0` | part yaw at full sweep (`rot_info` sign) |
| | `sweep_start` / `sweep_end` | float | `1.5` / `3` | airspeed b/t: forward at or below start, full sweep at or above end |
| `flaps` | `stages` | float[] | `[0, 15, 30]` | deflection per notch, degrees; first 0; part xRot follows at `rot_info.x_rot_speed` (default 1°/t); drawn by [part clip](#part-clips) `flaps` |
| | `lift_gain` | float | `0.35` | lift x (1 + gain) at full deflection |
| | `drag_k` | float | `0.6` | like `airbrake`, at full deflection |
| | `stall_shift` | float | `3` | stall AoA lowered by this at full deflection, degrees |
| | `max_speed` | float | `2.0` | b/t; faster => retracts to 0 |
|  |  |  |  | every `flaps` part of a fixed-wing vehicle is used (terms = mean); keys `]` down / `[` up (driver) |
| `weapon_bay` | `need_power` | bool | `true` | `false`: hand-worked; no power needed, probe access node toggles (M1129 roof hatches) |
| `linked_hinge` | `link` | string | — | switchable part id (door, weapon_bay, landing_gear); required |
| | `open_x_rot` / `open_y_rot` | float | `0` | part pitch/yaw while the link is on |
| `suspension`, `track` | `spring_stiffness` | float | `200000` | N per block |
| | `damping` | float | `800` | N per b/s |
| | `rest_length` | float | `0.1` | recalibrated at spawn so curb weight sits the hull 0.01 above ground |
| | `max_compression` | float | `0.1` | raised to cover static deflection if smaller |
| | `max_extension` | float | `0.025` | |
| | `max_droop` | float | none | droop below bind pose; none => `max_extension` alone limits |
| | `stow_when_towed` | bool | `false` | landing leg: towed => reach 0, drawn at bind (modelled stowed); else reaches the ground; 2 s stroke |
| `track` | `tracks` | Vec3[][] | `track` sockets — each inner list is one closed track path |
| | `track_width` | float | `0.5` | collision box width per track |
| | `road_wheels` | `{node, track, center}`[] | `road_wheel` nodes | one probe per wheel (centre x/z, track-box bottom), own spring row; node drawn at its travel; lower run below axle height lerps between wheels, fades to 0 toward loop ends. None => probe per block of run, track drawn at unit travel |
| `payload` | `payload` | object | absent | `{"type": id, ...}` fixed payload; with `loadouts` leave out (loadout equips it) |
| | `attach_offset` | Vec3 | `[0,0,0]` | blast / drop origin |
| `radar` | `radar_type` | string | `""` |
| | `scan_sector_angle` | float | `45` |
| | `max_scan_distance` | float | `256` |
| | `anti_ground` | bool | `false` |
| | `ui_hide` | bool | `false` |
| | `recon_role` | enum | `NODE` (upper case). `NODE` sweeps and shares, `RECEIVER` listens only and stays dark, `BOTH` does both |

---

## 5. Weapons — `data/<ns>/weapons/<id>.json`

Launcher only. Every round is a WFLib item; the loaded one owns flight, warhead, damage, penetration, threat.
Rounds have no recipes in CA or WFLib; crafting = pack (GregTech).

### 5.1 Type

| `type` | extra |
| --- | --- |
| `combinedassault:cannon` | CCIP / indirect fire laid for the loaded round |
| `combinedassault:grenade` | smoke key fires grenade weapons that take `wflib:smoke_grenade` |
| `combinedassault:rocket` | |
| `combinedassault:aerial_bomb` | |
| `combinedassault:missile` | [5.4](#54-missile) |
| `combinedassault:decoy_flare` | flare key |
| `combinedassault:laser` | `power` (rated wflib laser damage/tick, `1`), `range` (`96`), `energy_per_tick` (vehicle energy, `0.0005`); no ammo/reload; one beam tick per shot, first entity on the aim line |

### 5.2 Fields every weapon has

| field | type | default | notes |
| --- | --- | --- | --- |
| `name` | string | `vehicle.weapon.unknown` | translation key |
| `inaccuracy` | float | `0.5` | triangle spread `0.0172275 x (inaccuracy + preset dispersion)` per axis |
| `recoil` | float | `0` | sprung hull pitch / roll kick; unsprung (emplacement, trailer) => none |
| `shoot_interval` | long | `100` | milliseconds; `< 50` => `50 / shoot_interval` rounds per shot (fractional carry), launched `k / n` into the tick (shots arrive <= 1/tick) |
| `max_capacity` | int | `64` | rounds ready |
| `reload.time` | int | — | ticks |
| `reload.ammo` | ingredient | *required* (except `holder_only`) | item, `#tag`, or a list of either; list order = creative default first |
| `reload.variants` | id[] | any | WFLib ammo variants a cartridge must carry |
| `reload.magazines` | ingredient | — | WFLib magazine items it takes; set => magazine-fed |
| `reload.loose` | bool | `false` | with `magazines`: loose rounds too |
| `independent` | bool | — | |
| `sounds` | key -> [sound](#610-audio) | — | `fire` (pivot; server sends it past tracking range), `shell` (half a cycle later; single-round reload: half the reload, <= 1 s), `reload` |
| `casing_delay` | float | `0` | s from shot to spent case out of the bolt's `eject` socket; case = round's wflib calibre `casing` (`none` => nothing; `cannon` drawn at case length / 571 mm) |
| `backblast` | object | — | cone out of the bolt origin, opposite the bore: `length` (`8`), `half_angle` (`30`), `damage` (`12`), `knockback` (`0.8`, blocks/tick); x (1 - d/length)(1 - angle/half_angle); needs line of sight; own vehicle + riders spared |
| `barrel` | object | none | wflib `BarrelSpec` ([barrels](../../WF-Ballistics/docs/barrels.md)); absent => no heat, jams, cook-off, barrel change. Spread added to `inaccuracy`; jam roll per round (salvo = 1 roll); `cookOff.value` 0 = open bolt; `type` => spare barrel item, name key `barrel_type.<ns>.<path>` |
| `unjam_time` | float | `1.5` | s to clear a jam; `<part>.unjam` clip => its length |

Barrel runtime:
- Jammed / clearing / changing => no fire (client dry click). Clear: one round discarded.
- Cook-off: chambered, idle >= 0.5 s, not reloading => one round through the station, no operator.
- Change on foot: muzzle probe node (spare of `type` carried or barrel hazy), `swap.coop` while another player mans the station, else `swap.self`. Seated: `change_barrel` key, `swap.self`, spare from inventory or stores. Coolest spare first.
- Keys: `reload_clear_jam` (6): jammed => clear, else partly fired => breech back to stores + reload; `change_barrel` (7).

Muzzle light peak `clamp(6 caliber, 30, 900)`, `1.5 + caliber/40` ticks; caliber = loaded round's, floor 20.

### 5.3 Rounds

| item | fires as |
| --- | --- |
| `wflib:cartridge_<calibre>` (+ `wflib:ammo_variant`) | kinetic preset under the variant id; motor => interceptable rocket entity |
| `wflib:shell_*`, `combinedassault:rocket_*`, `combinedassault:bomb_*` (dumb) | kinetic preset; motor => rocket entity; speed 0 => dropped at vehicle velocity |
| `wflib:missile_*`, `combinedassault:missile_*`, `combinedassault:bomb_gbu_*` | WFLib missile to the aim point / lock; beam riders + SAR guided by the firing unit |
| `wflib:flare_cartridge`, `smoke_grenade`, `aps_grenade` | WFLib countermeasure entity |

- WFLib catalogue rounds exist only once requested: every `wflib:` item / `#wflib:` tag named in a pack def and every size tag a `missile_holder` accepts is requested at mod construction (`compat/wflib/Ordnance.java`); a tag requests the members of WFLib's own tag file.
- Same calibre, any variant: `"ammo": "wflib:cartridge_20x102"` takes HEI, API and every pack-registered 20x102 load.
- Size/calibre tags = mount compatibility: `#wflib:missile/<small|medium|large|huge|pod>`, `#wflib:bomb/<small|medium|large>`, `#wflib:rocket/<70mm|80mm>`, `#wflib:artillery_shells`. Third-party round tagged => loadable.
- Loose: one kind per load: reload takes the picked kind, else the first accepted stack (=> new pick); then only same item + components (variant).
- Magazine-fed: reload seats a stores magazine holding rounds: pick's kind (item + variants, any fill) with most rounds, else the fullest (=> new pick); loose pick + `loose` => loose. Empty / unloaded magazine => stores. Shot = top round (mixed belt fires in order). Capacity = magazine's; none seated => `max_capacity`.
- WFLib magazine item: use = fill from the inventory (first calibre match), sneak-use = unload. Shipped: `wflib:ammo_can_7_62x51` (200), `ammo_can_12_7x99` (100), `ammo_can_40x53` (48), `drum_20x102` (1550) on `gun_7.62mm`, `gun_12.7mm`, `mk19`, `m61a1`, all `loose`.
- Pick: `F` (tap = next kind; held + wheel = scroll; release commits). Different kind in the breech => back to the stores (variant kept, overflow dropped), reload. Loader aboard on a `manual` / `autoloader` station => loader picks.
- List above the weapon card: stores' kinds + counts (synced every 10 ticks), ≤ 5 rows, cursor name scrolls sideways; fades 3 s after the last change. Magazine row: its variants, count = magazines. Rack: "Any loaded" + each racked kind; no unload.
- `ammo_creative` in the hold: full load of the pick, else the first magazine / `reload.ammo`'s first item / first `variants` entry; list = each magazine full of each accepted variant + every accepted variant if loose, ∞.
- Sync: item + variant (+ magazine contents) only.

### 5.4 `missile`

| field | type | default | notes |
| --- | --- | --- | --- |
| `x_rot_max` / `x_rot_min` | float | `10` / `-10` | launch limits |
| `y_rot_max` / `y_rot_min` | float | `10` / `-10` | |
| `missile_holder` | string | `""` | holder whose racked rounds fire first (lowest loaded slot) |
| `holder_only` | bool | `false` | racked rounds only: ammo = rounds racked, no reload |

Next round (rack slot of the selected kind, else magazine) sets the lock rules:

| round seeker | needs lock (non-EO station) | lock search |
| --- | --- | --- |
| `INFRARED`, `OPTICAL` | yes | IR in the round's seeker cone |
| `SEMI_ACTIVE_RADAR` | yes | main radar lock in the cone |
| `ACTIVE_RADAR` | yes | radar contacts in the cone |
| `BEAM_RIDING`, `DESIGNATED` | no | — |

High-altitude `DESIGNATED` round: coordinate screen previews climb 64 / dive 128 blocks to its cruise altitude.

---

## 6. Model, display, recipes

One glTF per vehicle (`model`): drawn by GemRender, and the only skeleton. Frames, colliders, hit modules and sockets are glTF nodes tagged in `extras`; node names are labels only, never matched.

| `extras` key | value |
| --- | --- |
| `combinedassaultRole` | role (tables below); unknown => load error |
| `combinedassaultInterface` | role target: part id (frames, part sockets) or entry id (`era`, `slat`, `mount_point`, `hitch`) |
| `combinedassault` | role parameters; unknown key => load error |
| `gemrenderHidden` | `true` => not drawn (colliders, sockets, hit modules) |

### 6.1 `display`

Vehicle def `display` block; client only.

| field | notes |
| --- | --- |
| `icon` | slot texture; `tools/model_icon.py <rig>` renders it from the model |
| `controller` | `assets/<ns>/animation_controllers/<id>.json`; graph nodes `merge`, `blend`, `layered_blend`, `additive`, `script`, `bone_binding` |
| `engine` | engine audio ([§6.10](#610-audio)) |
| `cabin_muffle` | 0..1, default `0`: outside sound low-passed for a first-person rider of a protected seat; own door open x0.25, others x(1 - 0.5 open fraction) |
| `tab_index` | creative tab order |
| `description` | |
| `paint` | paint id ([paint variants](#paint-variants)) |
| `track` | `{"model": glTF id, "module_length": blocks}`: one link per pitch along each `track` part loop |
| `cabin_model` | glTF drawn instead of `model` from the operator view |
| `variants` | `{"<id>": {"paint": …, "tab_index": …}}`: extra spawn items, same model, own paint |


Ship script context: `getShaft()` (telegraph summed per powered tick, lerped), `getThrottle()`, `getRudder()`.

### 6.2 `data/<ns>/recipe/<id>.json`

`combinedassault:vehicle_printing`; result = `vehicle_spawn_item` carrying the vehicle id:

```json
{
  "type": "combinedassault:vehicle_printing",
  "materials": [
    { "count": 16, "ingredient": { "tag": "c:ingots/copper" } },
    { "count": 12, "ingredient": { "item": "minecraft:redstone" } }
  ],
  "result": {
    "id": "combinedassault:vehicle_spawn_item",
    "count": 1,
    "components": { "minecraft:custom_data": { "CombinedAssaultId": "combinedassault:mavic" } }
  },
  "printingTime": 300
}
```

### 6.3 Frames

| role | interface | meaning |
| --- | --- | --- |
| `body` | — | hull frame; exactly one; world = identity (model origin, unrotated, unscaled) |
| `part` | part id | the part turns/moves this subtree (turret yaw, wheel, door); one per part |
| `pitch` | part id | weapon elevation; child of the part's frame; one per part |

- Role node outside the `body` subtree => load error. Untagged nodes ride the nearest frame above them.
- Weapon station: traverses on `part`, elevates on `pitch`; no `pitch` => elevates on `part`.
- Drawn: weapon / radar `part` + `pitch` frames = rest * Ry(-yaw) * Rx(pitch) of the unit; a joint the controller / script poses itself is left to it.
- Part with no `part` frame: inert (no collision, a suspension never grounds).
- Controller / script bone names = glTF node names; pose translation in 1/16 block.

### 6.4 Part clips

glTF animations, scrubbed by part progress p ∈ [0,1], never played on a clock: clip time = p × clip length.

| part | p |
| --- | --- |
| switchable (`door`, hatch, `landing_gear`, `weapon_bay`, `airbrake`, …) | → on ? 1 : 0 at 1/span per s, per tick, both sides; span = the part's longest clip |
| `flaps` | deflection / deepest notch |
| `linked_hinge` | rotation / open rotation (server hinge speed; collision and picture agree) |
| `weapon`, clip `<part id>.fire` | time since current weapon's last shot / clip length; never fired => 1 (end key = rest) |
| `weapon`, clip `<part id>.reload` | current weapon's reload progress; idle => 0; no `.unjam` clip => max with jam clear progress |
| `weapon`, clip `<part id>.unjam` | current weapon's jam clear progress; idle => 0 |

- Name `<part id>` or `<part id>.<suffix>`; no json. Weapon parts: suffix `fire` / `reload` / `unjam` only. Other names: warned once at bind, never played.
- Weapon clips: key nodes under the pitch frame, never the frames (frames = aim); `.fire` and `.reload` key disjoint nodes.
- Start key = default state (p 0); end = other state, or full deflection.
- After the controller graph: clip wins on the channels it keys. Toggle sound: trapdoor.
- Hit mesh follows clips (server + client). Colliders: rest pose; moving collision = `linked_hinge`.
- `linked_hinge` collider turns by `+open_x_rot`; a script drawing the bone at `-xrot` swings the collider opposite the picture (C-5, CH-47 ramps rose into walls). Hinge frame rest rotation: same split (GemRender cancels it, colliders keep it).
- Script `if (context.isPartOn(..)) setRotation(..)`: snaps, no hit motion => bake to a clip (`tools/script_clips.py`).
- Author: Blender add-on Key part clip; staged models `tools/mqo.py` `append_clip` / `hinge_keys`.

### 6.5 Colliders

Role `collider`: a mesh node, the convex solid it bounds, in its frame. Collision only; hits: [§6.6](#66-hit-geometry).

| `combinedassault` key | type | default | notes |
| --- | --- | --- | --- |
| `main` | bool | `false` | physics hull; `body` frame only; at most one. Absent => largest solid `body` collider. Must span the ground footprint: support polygon = main hull; feet on other pieces => COM unsupported, tips |
| `solid` | bool | `true` | false => station / probe marker: bounds a part (missile station, suspension probe, gear leg), never collided |
| `hit` | bool | `false` | also a hit surface override ([§6.6](#66-hit-geometry)): replaces its parent's drawn subtree |
| `deck` | bool | `false` | others may stand / park / drive on it (wheels probe it; tilted piece = oriented box, lip <= 1 climbable) |
| `world` | bool | `true` | false => vehicles only: blocks, hill fill, providers pass through. Ramp opened past the ground (C-5, CH-47: lip under the ground, no lift of the carrier) |
| `name` | string | node name | readout label |

- Every collider a convex flat-faced solid; checked on load, the bad face named. Closure unchecked: T-junction fine, missing face = hole. Mesh with >1 primitive => one face per primitive (CoACD quads); else coplanar triangles merged.
- Compound: ground supports = downward faces (normal y < -0.3) of every solid `body` collider; other solid pieces posed in the main hull's frame. Turn geometry uses the `body` colliders' Z extent (not the main piece).
- Debug view (`/combinedassault debug true` / inspect key): colliders white, solid if the solve meets the world through it; hit surface faint, `hit` modules, rotor discs.

**Generating** (`tools/gen_hulls.py`, CoACD; setup in its header): per-node convex decomposition, written into the glTF as colliders; `--merge NAME`: `--nodes` decomposed as one mesh; `--main`: largest piece => `main` (another `main` present => exit); `"locked": true` keeps a hand-made piece through regeneration. `/reload` rereads. Blender add-on, Blockbench plugin, validator, player reference: `asset-tools.md`.

### 6.6 Hit geometry

Where shots land, what they damage, plate thickness; from the vehicle glTF at load, both sides. Vehicle def `"hitbox": false` => colliders are the hit surface, default pools only.

| glTF | meaning |
| --- | --- |
| drawn node | hit surface: exact triangles (BVH), true face normal => ricochet, `armor_<mm>` incidence |
| node with rotor-kind `system` (self or ancestor) | surface = disc: spin axis = thinnest axis, radius = furthest vertex, height from blade span (> 50% radius), min 0.3 |
| role `hit` | module: never drawn, not surface; pool = `system` + `side` |
| role `hit_surface` (or collider `hit: true`) | surface override: replaces the parent node's drawn subtree (`hit` modules inside kept); own materials (`armor_<mm>`), inherits `system`/`side`. Nested override inside a replaced subtree => load error |
| material `armor_<mm>` (`armour` too) | that face's real plate, mm RHA; else the vehicle's aspect value. Needs an `armor` block ([§3.7](#37-armor)) |
| material token `decor` (`paint_decor`, `decor.cloth`; `_ . space` delimited) | drawn only: not hit surface, not in generated colliders |
| material token `rubber` / `glass` / `soft` (`fabric`, `cloth`, `canvas`) | hit effect material ([Hit effects](#hit-effects)); untagged = steel |

Node frame => its part: a node under a part's `part`/`pitch` frame is that part's hit. `combinedassault` keys on frames, `hit` and untagged nodes:

| key | effect |
| --- | --- |
| `system` | system kind (table) or `none`; inherited by the subtree; required on `hit` |
| `side` | `left` / `right` pool split |
| `unit` | pool split by name (`FL`); inherited; HUD `ROTR FL` |
| `spin` | `cw` / `ccw`, rotor `unit` only; required there |
| `health` | pool size; `0` = 30% of `max_health`; larger of shared nodes wins |
| `swept` | force / forbid the rotor disc |
| `skip` | node has no hit geometry |
| `road_wheel` | untagged mesh only: road wheel of the track path with nearest mean x; no `track` socket => load fails |

| `system` | HUD | step s | effect |
| --- | --- | --- | --- |
| `hull` | HULL | 10 | vehicle pool (`max_health`); always present |
| `turret` | TRRT | 10 | traverse, ring |
| `barrel` | GUN | 8 | weapons |
| `track` / `wheel` | L-TR / R-TR, WHL | 10 / 6 | mobility; side by `side` |
| `tail_rotor` | TAIL | 10 | yaw authority; `1 - effectiveness` of torque (8°/tick x power x collective) yaws nose right airborne; BROKEN => stops spinning |
| `rotor` | ROTR | 12 | thrust, translational lift, attitude authority; BROKEN => no lift, stops spinning |
| `wing` | WING | 12 | wing, tail, control surface |
| `engine` | ENG | 12 | BROKEN => engine will not run |
| `fuel` / `ammo` / `optics` | FUEL / AMMO / OPT | 8 / 8 / 6 | |

`tools/gen_hulls.py <vehicle> --hit-cylinder a,b [--sides 12] [--material rubber]`: circumscribed prism `hit_surface` per node (tyres: tread detail = noise; figures: memory `ywzj-vehicle-hit-geometry`).

Damage routing (projectile line, or blast: burst -> hull core): first `hit` module along the line => its pool; else the struck surface node's system; else defaults. Struck system takes the hit unscaled; the hull takes it with core-line falloff. Scripts read `context.getRotorLift()` / `getRotorAntiTorque()`.

#### Hit effects

Per round strike, every player tracking the vehicle (hit indicator config regardless): server sends struck point, face normal, material, armour outcome, round kind, caliber. Size and count x caliber / 30 mm (0.35..4). Client: 16 strikes / tick with particles, 128 blocks; past budget => hole decal only.

| struck | effect |
| --- | --- |
| steel, `RICOCHET` | spark fan along the reflected path, flash |
| steel, `ABSORBED` / no armour answer | spark cone round the normal, embers, paint chips, grey puff, flash; hole |
| steel, `PARTIAL` / `PENETRATION` / `OVERMATCH` | entry sparks + spall cone inside along the shot, black wisp, flash; `OVERMATCH` + metal chunks; hole |
| steel, small arms (< 20 mm) | few sparks |
| steel, HEAT / tandem | big flash, molten droplets round the normal, spall jet, black smoke ring |
| HE round | none here (warhead FX) |
| `rubber` | black bits + dust, no sparks, no hole |
| `glass` | shards + glint, no hole |
| `soft` | dust + fibres, no hole |

Damaged systems (every 3 ticks, 160 blocks), at a live hit node of that system (none: hull top):

| system | `DAMAGED` | `BROKEN` |
| --- | --- | --- |
| `engine` | grey smoke | black smoke + fire |
| `fuel` | — | fire + black smoke |
| `ammo` | — | cook-off ember bursts |
| `rotor`, `tail_rotor`, `wing` | grey smoke | black smoke |

#### Systems and repair

- Cell = every part, hit-model pool and default pool of one kind (+ side). State = worst member.
- Defaults, only when nothing of that kind is declared: `engine` (every type but `generic`), `left track` + `right track` (tracked), `wheels` (wheeled). No geometry: hit in the main collider's low third => running gear on that side; above => 25% to the engine.
- Hull damage past 0 overflows evenly onto every member still above 0.
- **Destroyed iff every cell is BROKEN** (< 15%). Hull at 0 alone != dead.
- `track`, `wheel`, `engine` feed mobility; a broken `turret`/`barrel` part stops traversing/firing.
- Repair: node per damaged cell at its member nearest the player; no geometry (hull, implied pools) => hull node row. Use key / G, alt+scroll. One step = one band up (BROKEN -> DAMAGED -> WORN -> full), timed; moving, being hurt, the vehicle moving or being hit stops it.
- Tool: any carried item in `#combinedassault:<code>_tool` (codes: `hull engine track wheel turret gun fuel ammo optics rotor tail_rotor wing`); costs durability per step. Every `_tool` tag includes `#combinedassault:all_tool` (= `repair_tool`).
- Parts: a step from BROKEN also takes one `#combinedassault:<code>_parts`, **only if that tag has members**; shipped empty.
- Damage tiles: `<image>_damaged.png` / `<image>_wrecked.png` beside a model image => its nodes wear them at DAMAGED / BROKEN, per system. `tools/damage_textures.py` derives both from the base colour.
- `/combinedassault ballistics components` lists colliders, hit nodes, pools and cells; `ballistics system <HUD label> <ratio>` sets one.

### 6.7 Sockets

Role nodes read at load (rest pose, vehicle frame). Each fills a vehicle def key the def leaves out; a key the def has wins (a list wins whole). Unknown part or id => load error. Load log: `json repeats its glTF mounts` (equal: delete from def), `json overrides` (differs).

| role | interface | def key | from the node |
| --- | --- | --- | --- |
| `seat` | part | part `seat_offset`, `seat_rot` | origin = rider eye; yaw |
| `dismount` | part | part `dismount_offset` | origin |
| `view` | part | weapon: `operator_view_offset`; else `owner_view_offset` | origin |
| `sight` | part | `optical_sight_offset` (weapon only); `handling: manual` => ADS eye | origin |
| `sight_lens` | part | none: lens mesh of the station `optic` (weapon only); drawn | the mesh itself |
| `bolt` | part | part `bolts` (by `index`) | origin = breech, +Z = bore, Z scale = `barrel_length` |
| `eject` | part | part `ejects` (by its bolt's `index`; 0..n-1) | origin = case port, +Z = throw, Z scale = speed (blocks/s); pitch frame, rest pose (recoil clips ignored) |
| `era`, `slat` | entry id | `addon_armor` entry: `kind`, `bone`, `center`, `normal`, `up`, `width`, `height` | quad mesh: normal = winding; `up` = edge nearest +Y (flat: nearest +Z); size/centre = extents. `surface` present => fills it instead |
| `mount_point` | entry id | `mount_points` entry: `offset`, `yaw`, `pitch`, `roll` | origin, rotation |
| `hitch` | entry id | `towing.hitches` entry `offset` | origin |
| `towable`, `axle` | — | `towing.towable.offset` / `.axle` | origin |
| `exhaust` | — | `energy_info.engine_particle_offsets` (by `index`) | origin |
| `vortex`, `smoke` | — | `attributes.vortex_offsets` / `aerobatic_smoke_offsets` (by `index`) | origin |
| `afterburner` | — | `attributes.afterburner_offsets` (by `index`) | origin |
| `camera`, `camera_zoomed` | — | `view_info.third_person_center_offset[_zoomed]` | origin |
| `light` | — | none: GemRender spot | posed frame; beam +Z, cookie up +Y |
| `propeller` | — | `attributes.propellers` (ship) | origin = hub; mean scale = radius |
| `station` | part (`missile_holder`) | holder station (by `index`) | origin = round centre, +Z = nose, Z scale = length (launch = origin − length/2 along +Z); rides the holder's `part` frame |
| `cargo_bay` | — | `cargo.bay_center`, `cargo.bay_size` | origin; scale = full size |
| `center_of_mass` | — | `physics_info.center` | origin |
| `pivot` | — | `center_offset` | origin |
| `track` | part (`track` type) | part `tracks` (by `index`) | mesh outline = path (edges of one triangle), winding order from the first triangle's outline edge; a face or fan |
| frame keys | — | part `rot_info` `x_rot_min/max` (`pitch` frame, none: `part`), `y_rot_min/max` (`part` frame); `suspension` part `render_bone` = its `part` frame name | `combinedassault.*` on the frame node |

- `combinedassault.index`: position in a list field; absent => file order.
- Weapon view / sight: relative to the part frame; bolt, eject: in its `pitch` frame (none: `part`).
- Addon `bone` = the socket's frame name.
- `light` keys (`combinedassault.*`): `kind` `head` (default) \| `search`, `color` `#rrggbb`, `range`, `intensity` (E ~1 at `sqrt(intensity)` blocks), `inner`/`outer` half-angle deg (`0 < inner <= outer < 89`), `shadow`, `cookie`. Defaults per kind: `VehicleLight.Kind`. Toggle key (default `\`): driver -> `head`, other crew -> `search`; vehicle with one kind => that kind. Destroyed => off.
- Part `mounts` model: its part-target sockets with no interface => the carrying part. Kit mount `bolt` / `sight` / `sight_lens` replace the part's while that kit is fitted. Station sockets (gunner seat, view) belong in a mount, not the shared hull model: an unarmed sibling then loads.
- Model shared by two vehicles: its sockets apply to both; a part one lacks fails that one's load => per-vehicle offsets in the def.
- Def geometry -> sockets: `tools/def_sockets.py move` (shared model: only values every def agrees on), load, `prune <log>` deletes what the load reports repeated.
- Stays json: tuning (springs, forces, mass, health), crew, weapons.

### 6.8 Decorations

`combinedassault/<ns>/decorations/<path>.json`: `{"model": glTF id, "icon": png}`. Placed on a vehicle by the decoration item: hit-mesh trace => frame + offset; drawn in that frame.

### 6.9 GemRender specifics

- One instance per hull on Flywheel; posed through a bone palette on the GPU. Flywheel backend off => no hull.
- Material with colour and no texture draws white: give it a sheet (one cell per material, UVs pinned to cell centres, linear -> sRGB).
- Lighting per instance: no per-node fullbright.
- Suspension: `render_bone` node translated by the wheel's travel. Detached part: the vehicle model isolated to the part's `render_bone` subtree.

#### Paint variants

Variant = `display.variants` entry, same model, own `paint`; modding tool lists them.

| file | content |
| --- | --- |
| `<image>_paint.png` beside the base colour | alpha = coverage, rgb = colour the base is painted in. `tools/paint_layers.py mask <png> [#RRGGBB]` |
| `<image>_paint_solid.png` | optional; white = paint's mean colour instead of its pattern (placards, small panels), shading kept. `tools/paint_layers.py solid <png> x0,y0,x1,y1 ...` |
| `<image>_damaged_paint.png`, `_wrecked_paint.png` | per damage tile; else the base mask. `tools/damage_textures.py` writes both (and copies `_solid`) when a mask exists |
| `assets/<ns>/paints/<name>.json` | `{"texture": "<png>", "tile": <blocks per repeat, default 4>}` or `{"color": "#RRGGBB"}` |

- Masked texel = base * paint / mask rgb => base shading kept; `paint` unset = authored texture.
- Pattern projected from each part's rest pose (triplanar): no per-model UV authoring; pinned to its part.
- Shader pack on => authored texture. Tracks, decorations: unpainted.
- Tileable sheets: `tools/paint_layers.py patterns` (woodland, winter, urban). Shipped: `m1a2_*`, `t84_*`, `ah64d_*`, `ah_6_*` x `woodland winter urban olive_drab`.

---

### 6.10 Audio

Played by WFLib (`com.wf.wflib.audio`): own distance curve, travel delay + Doppler at 343 m/s, air absorption, cabin low-pass. Guide §31a4.

Sound = event id string, or:

| field | default | notes |
| --- | --- | --- |
| `event` | *required* | sound event (`sounds.json`) |
| `volume` | `1` | 0..1 |
| `pitch` / `pitch_random` | `1` / `0` | pitch + U[0,1) x random |
| `range` | `16` | blocks; gain 1 to `max(0.15 range, from)`, exponential to 0 at `range` |
| `from` | `0` | > 0: silent below `from/2`, full at `from` (far layer) |
| `delay` | `0` | s after the trigger, before travel time |

`engine`: `start` / `stop` sounds on the engine on/off edge; `loops`:

| field | default | notes |
| --- | --- | --- |
| `sound` | *required* | |
| `input` | `rpm` | `power` (spool), `rpm` (ground: engine speed, 0.6 = idle; air: = power), `throttle` (jets: > 1 afterburner; helicopters: collective), `speed` (ground: forward / `max_speed_forward`; jets: / 300 m/s; else / 100 m/s) |
| `volume`, `pitch` | `1` | curve of `input`: number or `[[x, y], ...]`, linear, clamped |
| `aspect` | `1` | curve of the angle (deg) nose -> listener; 0 ahead |
| `gate` | `1` | curve of `power`, x volume |
| `fade` | `0.1` | gain slew / tick |

## 7. Mass and cargo weight

```
mass = physics_info.mass          (curb weight)
     + fuel                       (electric: the full battery, whatever the charge)
     + fitted parts               (payloads, addon armour: ERA brick 5.6 / 13 kg, slat 25 kg/m²)
     + cargo kilograms × storage.cargo_mass_per_kg
```

- Battery mass constant from full to flat; tank lightens as it burns.
- Mass divides thrust in every drivetrain; no separate handling stat.
- `load = mass / (curb + fuel + parts)` divides pack kinematics declared unladen: tracked accel / brake / turn
  accel, helicopter rotation accel, fixed-wing pitch / roll / yaw rate, multirotor `tilt_rate`, FPV key rates
  (mouse exempt). Fuel burn × load.
- Cargo kg per item: Ultimate Weight (`wfweight`) value if > 0, else built in: block item = 1 L of material
  (tags: `c:storage_blocks/<metal>` by density, wool 0.1, leaves / hoe 0.3, axe 0.6, shovel 1.6, pickaxe 2.6,
  else 1 kg/L); `c:ingots|gems` 0.114 L, `c:raw_materials` 1.5×, `c:nuggets` /9, `c:dusts` /2; wflib
  cartridge / charge = own mass; filled bucket 0.3 kg + 1 L fluid; container / bundle contents counted
  (depth 4); else 0.25 kg. Hold recounted <= 1/s, only on change.
- Parts + cargo synced (`PART_MASS`, `CARGO_MASS`) => client prediction agrees.

---

## 8. Multirotor and FPV flight

### 8.1 `attributes.multirotor`

wflib `Multirotor` force law: thrust along the tilted disc only, airframe drag, gravity owned by the model
(vehicle `noGravity`). `thrust / (mass·9.8)` = live T/W => payload and fuel mass cost climb and top speed.
Skips `main_rotor_force`, arcade aero, `max_air_speed`.

| field | default | notes |
| --- | --- | --- |
| `thrust` | required | N, all rotors at full output |
| `max_tilt` | `45` | deg; with drag sets top speed |
| `tilt_rate` | `10` | deg/tick |
| `linear_drag` / `quadratic_drag` | `0.04` / `0.02` | per tick, × v / × v² (blocks/tick) |
| `velocity_gain` / `vertical_gain` | `0.8` / `0.28` | STABILIZED velocity loop |
| `max_climb` / `max_descent` | `0.6` / `0.45` | blocks/tick; unpowered or FPV => none (drag only) |
| `max_speed` / `climb_speed` | `0.9` / `0.3` | STABILIZED stick authority, blocks/tick |
| `yaw_rate` / `yaw_response` | `8` / `0.35` | deg/tick cap; fraction of mouse-yaw error per tick |
| `mode` | `stabilized` | `stabilized` \| `fpv`; hover key toggles |
| `fpv` | `{}` | FPV acro, §8.2 |

- STABILIZED: WASD/space/ctrl = velocity setpoint in heading frame, mouse = heading. Same controller as autopilot.
- FPV: §8.2.
- Not client-predicted; FPV attitude excepted (§8.2).
- ≥2 rotor `unit` pools => per-rotor thrust: max thrust share holdable at zero roll/pitch/yaw torque, each rotor ≤ its effectiveness (LP). Quad: any dead rotor => 0 => uncontrolled; rotors at power × last collective × effectiveness tumble it toward the dead one (yaw from spin), FPV attitude server-owned. Hexa/octa: hover on reduced share. <2 units => lumped `rotor` pool.

### 8.2 `attributes.fpv`

Acro: body rates, no self-levelling, no attitude limit. Mouse = body pitch, heading about world vertical (sign by
body up => screen-right inverted too; tilt turns with it); W/S pitch, A/D roll, Q/E body yaw;
collective = throttle along the disc. Camera: OPERATOR = seat 0 `sight` socket fixed to the body; THIRD_PERSON = chase on
body heading.

| field | default | notes |
| --- | --- | --- |
| `pitch_rate` / `roll_rate` / `yaw_rate` | `12` / `15` / `8` | deg/tick, keys |
| `camera_tilt` | `0` | deg lens uptilt |

- Body yaw alone leaves thrust axis => no change of course.
- Attitude: operator client steps at input sample (previous keys, the ones render led by); server adopts every report, no own step. Gap: server-side tumble overwritten by next report.
- Absent => stock model.

---

## 9. Indirect fire

- Per station: `indirect_fire` ([§4.4](#44-weapon-stations--weapon-auto_weapon)); absent => `Y` refused. Shipped: `m252a1`, `m1129` (map), `krupp_c34`, `oto127`, `m142` (map).
- Solved for the loaded round ([§11](#11-indirect-fire-and-ccip-rounds)). Missile station = self-guided: laid on the bearing at `launch_elevation`; the round flies its own guidance to the point.
- `Y` cycles `DIRECT` -> `MANUAL` -> `AUTO` -> `DIRECT`; a missile station skips `MANUAL`.

| mode | laid by |
| --- | --- |
| `MANUAL` | the keys ([§9.1](#91-laying-a-gun)) |
| `AUTO` | a point: coordinates (`N`) or a map fire mission ([§9.3](#93-fire-missions-from-the-map)) |

- `m252a1` numbers: elevation `-55`..`-85` (bipod screw runs out below ~53°; 45° buys +6% range); `wflib:81mm/he` (6 b/t, drag 0.01) => 50..324 blocks; one bolt `[0,0,0]`, `barrel_length: 1.31` = tube.

### 9.1 Laying a gun

`Page Up`/`Page Down` elevation, `,`/`.` traverse, `;` ladder, `'` arc.

| rung | a press moves |
| --- | --- |
| `10°` `1°` `0.1°` | the barrel, by that angle |
| `50m` `10m` `1m` | the fall of shot, by that many blocks |

- 0.1° on the 81mm ~0.8 block of impact, inside dispersion => finer rungs in blocks.
- Block rung: elevation keys add/drop range, traverse keys shift left/right; off the end of reach or onto the other arc => refused.
- Laid on a point => the point moves (ground height kept); hand-laid => level-ground range.

### 9.2 The gunner's board

Top left, `MANUAL`/`AUTO` only: quadrant (stops, barrel, laid mark), ELEV vs GUN, state `LAID`/`LAYING`/`AT STOP`, bearing tape, reach scale (window the stops leave lit), fire mission (target, time of flight, apex), standing map mission, splash countdown per round in the air.

### 9.3 Fire missions from the map

- Operator of a `map` station, on a map host (wflib `client/map`: JourneyMap, Xaero's World Map): toolbar "Fire mission" armed => click designates; right-click menu => round kind (loaded count) -> 1 / 2 / 4 / all (Xaero: second menu at the cursor).
- Mission: `AUTO` laying, round selected, each round fired once laid (missile 1°, gun 0.1°) and ready; ends at the count or when the kind runs out.
- Call for fire (wflib PDA carrier, any map host incl. the PDA's embedded map): menu "PDA: call for fire here" -> `CallForFirePacket` -> server: PDA + uplink (`Uplink.of != NONE`) else refused -> `FireRequestPacket` to same-side (`MarkerStore.side`) players seated at a `map` station, same dimension. Operator: request cross + observer name on the map, menu "Fire request <obs>: designate", PDA alert; firing stays the operator's order. Requests live 120 s, 8 kept.
- Target height: rack rounds from an `indirect_fire` station take the surface server-side once the target chunk loads (map heights over unexplored ground read 64).
- Default key clashes, rebind one of each: JourneyMap fullscreen `J` = leave-vehicle `J`; Xaero map `M` = tow-couple `M` (and wflib TV view); Xaero minimap settings `Y` = indirect mode `Y`.
- Xaero minimap default top left = indirect panel: move one.
- Mixed rack: `F` picks the next round (§5.3); the server fires only the selected kind ([§15](#15-pylon-missiles)).

---

## 10. Fuel and electricity

### 10.1 Units

1 unit = 1 kg fuel = 1 mB at efficiency 1. Gauge = energy / `energy_capacity`. One hour at full
power: `consumption = capacity / 72000`.

A fuel vehicle's tank is a real Forge fluid handler on the entity. Anything that can see a tank —
a bucket, the fuel can, another mod's pump — sees this one.

### 10.2 Declaring fuels

```jsonc
"energy_info": {
  "energy_capacity": 4000,
  "energy_consumption_per_tick": 0.02,
  "fuels": [
    { "fluid": "#combinedassault:fuel", "efficiency": 1.0 },
    { "fluid": "minecraft:lava",     "efficiency": 0.25 }
  ]
}
```

| field | type | default | notes |
| --- | --- | --- | --- |
| `fluid` | id or `#tag` | *required* | a fluid id, or a fluid tag with a leading `#` |
| `efficiency` | float | `1.0` | range multiplier |

Entries are matched **in order**, so a specific fluid listed before a tag it belongs to wins.
An id nothing registered is dropped rather than matched, so a typo refuses that fuel instead of
accepting every fluid in the game.

`#combinedassault:fuel` is a tag this mod ships: its own fuel, plus optional entries for `#c:diesel`,
`#c:gasoline`, `#c:fuel` and a few named fluids from other mods. Entries that do not resolve are
skipped, so the tag is exactly as wide as the modpack it is in.

**Efficiency is fuel economy, not power.** A fuel at 0.5 makes no less power and accelerates no
worse; it burns twice as fast for the same work, so the tank lasts half as long. That is the whole
of it — the endurance readout on aircraft already accounts for what is in the tank.

**A vehicle that declares no `fuels` inherits the `fuelNameWhiteList` config** (`fuel`, `gas`, `lava`
by substring, at efficiency 1), which is what every vehicle did before fuels were data-driven. None
of the shipped vehicles declares any, so shipped content behaves exactly as it did. Declaring
`fuels` opts one vehicle into the stricter, rated list.

### 10.3 One fluid at a time

A tank holds one fluid. While there is anything in it, only more of the same goes in — a bucket of
the wrong thing is refused with a message rather than quietly mixed, and a pump underneath is
refused the same way. **Running the tank dry clears it**, so switching fuels needs no drain: burn it
off, or sneak-click with any container to pull the rest back out.

### 10.4 Getting fuel in

| gesture | effect |
| --- | --- |
| click with a full container | pours it in |
| sneak-click with any container | draws fuel back out |
| click with the fuel can | the same — the can is an ordinary fluid container |
| park over a tank | fills from the block directly below, 50 mB/t |

The parked route exists because no fluid or energy system pushes into an *entity*: pipes and cables
address blocks. A vehicle therefore pulls from the block it is standing on, which makes any mod's
tank a fuel depot without either mod knowing about the other. The block has to *offer* a fluid
handler — a tank does, a pool of lava does not, so parking on a lava lake is not a fuel source.
Config: `parkedResupply`.

### 10.5 Electric vehicles

```jsonc
"energy_info": {
  "energy_type": "electric",
  "energy_capacity": 250,
  "energy_consumption_per_tick": 0.01,
  "fe_per_unit": 20,
  "charge_rate": 2048
}
```

That is a 5000 FE battery — `0.25 × 20000` — good for a bit over twenty minutes at full power, which
is about right for a quadcopter. No `fuels`, and no fuel goes in: a jerry can gets a message saying
so.

The vehicle exposes an `IEnergyStorage` **entity capability**, receive-only, so a charger can fill one
but a cable cannot quietly drain a parked one. Charge does not change mass ([§7](#7-mass-and-cargo-weight)).

| gesture | effect |
| --- | --- |
| click with an FE battery | empties as much of it as fits |
| click with a GregTech battery | the same, converted at GregTech's own EU:FE ratio |
| park over an FE block | charges from the block below at `charge_rate` |
| park over a GregTech block | the same, if that face outputs energy |

**GregTech EU** is bound reflectively and is absent-safe: with no GregTech installed the bridge
answers zero and nothing else changes. It converts at `FeCompat.ratio`, GregTech's own converter
setting, so a vehicle charges at the rate a GT energy converter would give it — 4 FE per EU by
default. Both routes exist because GregTech, like every other energy system, has no way to push into
an entity.

### 10.6 Making a shipped vehicle electric

- None shipped electric: no FE mod => no charging. Drones are the candidates.
- Own pack: `energy_info` above into `mavic.json`. Stored energy kept across the switch (units either way).

---

## 11. Indirect fire and CCIP rounds

- Firing solution + CCIP pipper: loaded round's muzzle speed, linear + quadratic drag, gravity, life; CCIP adds motor.
- Quadratic drag => stepped flight, unreachable past `life`; linear only => closed form, life ignored.
- Empty breech => laid for the weapon's default round (`reload.ammo` first item / first variant).
- Calibre `TANK` kind (tank, naval, mortar) loads chunks it flies into; others lost over unloaded ground.

---

## 13. Ships

`combinedassault:ship` is a hull the water holds up. Its motion is a surge, sway, yaw, heel, trim and
heave model whose size dependence comes from the hull itself; the
physics engine only ever applies gravity to it while it is afloat, and the contact stage handles the
seabed and the banks the way it handles walls. `can_wade` is forced on and `physics_info.density` is
forced to 0 for every ship.

### 13.1 `attributes`

| field | type | default | notes |
| --- | --- | --- | --- |
| `waterline` | float | `0` | local Y that sits at the surface when level and surfaced |
| `draft` | float | to the physics hull's underside | blocks from the waterline down to the keel |
| `max_speed_forward` | float | `0.5` | blocks per tick |
| `max_speed_backward` | float | `0.2` | |
| `acceleration_time` | float | `10` | seconds: `v(t) = v_max tanh(t / this)` at full throttle from rest; the hull's mass ratio (curb over loaded) scales it |
| `throttle_rate` | float | `0.5` | telegraph travel per second while W or S is held; released, the telegraph stays where it is |
| `rudder_rate` | float | `1` | rudder travel per second; it self-centres at the same rate |
| `turn_rate` | float | `10` | degrees per second, steady turn at full speed and full rudder; scales with the speed actually kept in the turn |
| `turn_lag` | float | `1` | yaw time constant as a multiple of `L / v`: a hull needs about a length of travel to establish a turn |
| `low_speed_turn` | float | `0` | rudder authority at rest as a fraction of `turn_rate` (water jets, bow thrusters) |
| `drift` | float | `0.15` | sway time constant as a fraction of the yaw one; larger slides more in turns |
| `heel` | float | `1` | degrees of heel per degree per second of turn at full speed; positive heels outward (ships), negative inward (planing boats). Capped at 30 |
| `trim` | float | `2` | degrees of bow-up pitch at full forward thrust; capped at 15 |
| `heave_period` | float | `3` | seconds; the hull settles onto its waterline critically damped at this period |
| `righting_rate` | float | `4` | degrees per second heel and trim move toward their targets |
| `min_keel_clearance` | float | `1` | blocks under the keel below which the water reads as shallow |
| `shallow_drag` | float | `2` | drag multiplier reached at zero clearance |
| `submarine` | object | absent | present makes the hull a submarine: |
| `submarine.max_depth` | float | `40` | deepest setting, blocks below the surface |
| `submarine.crush_depth` | float | `60` | past this the hull loses 2% of its health a second |
| `submarine.dive_rate` | float | `0.15` | blocks per tick the setting moves while space or shift is held |
| `submarine.max_speed_submerged` | float | `0.4` | replaces `max_speed_forward` once the hull is under |
| `submarine.dive_angle` | float | `12` | pitch adopted while changing depth |

### 13.2 Controls and readouts

W and S step the telegraph, A and D put the rudder over, space and shift move a submarine's depth
setting. The helm HUD shows telegraph, rudder, speed in knots, heading, water under the keel (or
`SHALLOW WATER`, `AGROUND`, `BEACHED`) and, for a submarine, depth against setting. Passengers of a
submerged hull do not drown.

`/combinedassault naval spawn <id> <pos> [yaw]` spawns any vehicle fuelled from a console;
`naval status` prints the nearest ship's state; `naval helm <throttle> <rudder>`, `naval depth`,
`naval engine on|off`, `naval fuel` drive it without a player; `naval fire <preset> <from> <target>`
launches a WFLib missile or torpedo preset.

### 13.3 Grounding

The physics hull should run from the keel to the main deck and no wider than the hull at the
waterline: what it spans is what stops it. A seabed shallower than the draft or a bank in the way
stops the hull dead through the ordinary contact stage, a ship climbs nothing (`maxUpStep` is 0), and
the readout says `AGROUND` while the telegraph is set and the hull is not going anywhere. A probe
column ahead of the bow reports shoals before they are met.

### 13.4 Decks, mounts, weapons

A `deck` bone makes the ship a carrier: vehicles that park on it ride it and sleep, players walk it.
Emplacements are ordinary `mount_points`; torpedo tubes are a `missile_holder` feeding a
`combinedassault:missile` weapon, and WFLib' torpedo items are in the `missile/medium` tag.
The shipped hulls are `riverine` (patrol boat, 3 gun stations), `nimitz` (carrier, six `#naval_emplacement`
points, a flight deck) and `kilo` (submarine, six tubes).

---

## 14. Towing

A vehicle can be hung off another and dragged along behind it, either on a rigid hitch or on a
cable. Both are declared in one block on the vehicle file, `towing`, and both are optional: a vehicle
that says nothing here cannot tow and cannot be towed.

```json
"towing": {
    "hitches": [
        {
            "id": "fifth_wheel",
            "name": "hitch.combinedassault.fifth_wheel",
            "offset": [0, 1.45, -2.65],
            "modes": ["hitch"],
            "accepts": ["#semi_trailer"],
            "max_mass": 4000
        }
    ],
    "towable": {
        "enabled": true,
        "tags": ["semi_trailer"],
        "modes": ["hitch", "cable"],
        "offset": [0, 1.45, 0],
        "axle": [0, 0, -7.22]
    }
}
```

### 14.1 `hitches[]` — what this vehicle can pull with

| field | type | default | notes |
| --- | --- | --- | --- |
| `id` | string | `""` | unique within the vehicle; a saved coupling records it |
| `name` | string | `""` | translation key for the readout; falls back to the id |
| `offset` | Vec3 | `[0,0,0]` | where the joint is, in the vehicle's own frame, like `seat_offset` |
| `modes` | enum[] | `["hitch"]` | which couplings this point makes: `hitch`, `cable`, `sling` |
| `accepts` | string[] | `[]` | vehicle ids, or `#tag` against the towed vehicle's own tags. Empty accepts anything towable |
| `max_mass` | float | `0` | heaviest train this point will take, kg. Zero is no limit |
| `cable_length` | float | `8` | blocks of cable (or sling) paid out |

### 14.2 `towable` — what can pull this vehicle

| field | type | default | notes |
| --- | --- | --- | --- |
| `enabled` | bool | `false` | opt-in, like `mountable` |
| `tags` | string[] | `[]` | names a hitch can match with `#tag` |
| `modes` | enum[] | `["cable"]` | which couplings this vehicle accepts |
| `offset` | Vec3 | `[0,0,0]` | the kingpin, lunette or towing eye, in this vehicle's own frame |
| `axle` | Vec3 | `[0,0,0]` | the wheels it pivots about while towed; only the ground-plane position is used |

**`axle` is the field that matters.** A towed vehicle is not steered: it rolls, and its tyres do not
slip sideways at the axle, which is what makes a trailer track round a corner instead of being dragged
round it square. Put it at the middle of the axle group that carries the load, on the centreline. For
a tandem, that is between the two. The distance from `offset` to `axle` is the drawbar, and it is
what decides how far the trailer cuts the inside of a turn.

A coupling forward of the axle tows nose first, which is every normal trailer. One behind it is towed
tail first, and that is honoured rather than refused, because a recovery tow of a vehicle that has
lost its front end is exactly that.

### 14.3 What a coupling does

A hitch is a ball joint in the physics step: the coupling is held on the hitch and the pair behave as
one articulated vehicle. The trailer pitches about its own wheels, so it noses down into a dip and
stands up over a crest by itself. A fifth wheel has stops at 78 degrees, and past them the cab drags
the trailer round rather than letting it fold any further.

A cable does nothing at all until it comes tight. Past its length it can only pull, and it hangs in a
visible curve between the two vehicles. That curve is drawn as one instanced rope when GemRender is
present and a vertex at a time when it is not.

The load is real mass on the joint: a trailer holds a truck back up to speed and shoves it along under
braking. A towed vehicle rolls (no ground friction) and pays its own `friction` as rolling resistance.
A coupling more than four blocks past its length parts, as does one held more than 1.2 open for five
seconds.

A sling (helicopter cargo hook) hangs the load by the top centre of its main hull box on a rope of the
sling's length, so the aircraft carries the weight. Hanging: heading follows
the aircraft at 2°/tick, hull righted and spin damped. More than 3 blocks past its length in a tick
=> parted. Released (couple key again) => free fall, landing damage ([§17](#17-cargo-holds-and-parachutes)).
Shipped hooks (`max_mass` = real external-load limit, 8-block sling): `mh60l` 4082 kg, `mi24` 2400,
`w3` 2100. Slingable: `humvee*`, `m252a1`.

Trains chain: anything towed can itself tow, and a whole train is solved together. A train cannot be
closed into a loop.

### 14.4 Controls, commands and trials

`P` couples or uncouples from the driver's seat: if anything is on the back it comes off, otherwise
whatever is in reach of a hitch goes on, preferring a rigid hitch, then a sling, then a cable. Which hitch, which
vehicle and which coupling are all decided on the server.

`/combinedassault tow couple <tractor> <towed> [hitch] [mode]` and `tow uncouple <towed>` do it by hand;
`tow status <vehicle>` prints the whole train with the gap, strain and articulation at every
coupling.

`/combinedassault tow trial <course> [radius] [laps] [speed] [mode]` cuts a ring of test ground and
drives a tractor and trailer round it under a fixed driving law, writing a row per tick to
`combinedassault_trials/`. Courses are `lap` (flat, washboard, ruts, whole-block noise, kerbs and a
crest, one sector each), `flat`, `rough` and `washboard`. `tow trial report` hands back the verdict,
which is the worst of the coupling gap, the trailer's clearance and the articulation chatter.

### 14.5 The shipped pair

`combinedassault:m983` is an 8x8 heavy tractor with a fifth wheel at `[0, 1.45, -2.65]` that takes
`#semi_trailer`, a cable-only pintle hook at the rear bumper, and its own towing eye on the front
bumper so it can be recovered on someone else's cable. `combinedassault:mim104` is the Patriot launcher
trailer: a semi-trailer with its kingpin over its origin, its tandem axle 7.22 blocks behind that.
Unmanned launcher: [§18](#18-air-defence).

---

## 15. Pylon missiles

- Rack = `missile_holder`, one `station` socket per station ([§4.6](#46-other-part-types)), fed to a `missile` weapon with
  `missile_holder` + `holder_only` ([§5.4](#54-missile)). Right-click with a tagged round => first free slot of
  the first holder that accepts its size.
- Probe node per station, on foot: fitting round carried => load that station; loaded + empty hand / round carried => unload. Same for weapon `round_bones` stations when `ammo_capacity` == bone count (jet pylons): load fills the next bone, unload empties the lowest loaded.
- WFLib round: its own preset flies to the aim point, or homes on the lock; leaves the station tail on the
  vehicle's velocity. Preset ascent `air_launch` => 8 ticks motor cold, then attack. Ground vehicle, ascent `ascent`,
  no cold launch => + ascent speed along the station: climbs at the tube angle; level tube => attack at once.
- `missile/small` = station-sized (≤ 1.9 long, ≤ 0.36 across): wfcore's `missile_atgm`, `missile_atgm_frag`,
  `missile_guided_rocket`, tagged from wfcore's data. No WFLib airframe outside `atgm`/`guided_rocket` fits.
- Slot fires any WFLib round (§5.3); fit = `wflib:missile/<size>` tag alone.
- Drawn: WFLib airframe at true size; any other round, its item sprite.
- Selected round (`F`, map fire mission): lowest slot of that kind; none loaded => nothing fires.
- `POD` = MLRS cell: `wflib:missile_gmlrs`, `missile_gmlrs_aw`. `POD_LARGE`: `wflib:missile_atacms`.
- `pods` holder: slot i = seated pod's tube i; pose = pod tube offset (x, y) from the `station` sockets' centroid, station 0 rotation; length = pod's. No pod => no slots. Pods: `wflib:pod_gmlrs` (6 x `missile/pod`, tubes = `m142` stations), `wflib:pod_atacms` (1 x `missile/pod_large`, centred).
- Right-click with a pod => seated; previous pod, as loaded, to the player. Pod item use = fill empty tubes from the inventory; sneak-use = unload.
- Seated pod dry + stores hold a loaded pod + crewed => `pod_swap_time` => swap; empty pod to the stores.
- Shipped: `mq9` (UAV, six stations); `m142` (pod on the elevating launcher: holder frame under the launcher's `pitch` frame; `default_pod` `wflib:pod_gmlrs`).

## 16. ERA and slat

`addon_armor[]` on the vehicle file: flat panels players fit, refill, strip and weld in place. Trace-only:
never collide, never deck.

| field | type | default | notes |
| --- | --- | --- | --- |
| `id` | string | *required* | unique per vehicle; save key |
| `kind` | `era` \| `slat` | `era` | |
| `bone` | string | `""` | frame carrying it (turret => turns with it); `""` = hull frame; unknown => dropped, warned |
| `center` | Vec3 | `[0,0,0]` | vehicle-local blocks, rest pose. ERA: on the hull face; slat: bar plane |
| `normal` | Vec3 | `[0,0,1]` | outward |
| `up` | Vec3 | `[0,1,0]` | projected onto the panel; slat bars run across it |
| `width` / `height` | float | `1` / `0.5` | blocks |
| `cells` | `[cols, rows]` | `round(w/0.40)`, `round(h/0.25)` | ERA bricks; ≤ 64 |
| `standoff` | float | `0` | slat bracket length, drawn only; physics measures the real gap to the hull |
| `surface` | object | absent | baked `{center, normal, up, residual}`; replaces the authored frame. Do not hand-edit |

An `era` / `slat` role quad supplies the frame and size ([§6.7](#67-sockets)).

Authored frame = where to look; `tools/bake_addon_armor.py [vehicle...] [--pack dir]` fits it to the glTF
triangles at rest and writes `surface`. ERA: plane through the outermost skin under the panel (hits within
0.12 of the shallowest). Slat: authored orientation, centre = skin + `standoff`. Re-run after moving a point or
changing the model. `residual` > ~0.08 => panel spans a bend: split it. Panels up to 1 block behind a
collider's face still meet rounds first.

Slat bars: `floor(h / 0.09)`, 15 mm across, 50 mm deep.

### 16.1 Items

| item | fits | cost | removal |
| --- | --- | --- | --- |
| `era_brick` | `era`, light | 1 / cell | live bricks back, spent = scrap |
| `heavy_era_brick` | `era`, heavy | 1 / cell | same |
| `slat_panel` | `slat` | `ceil(w*h)` | `floor(cost * condition)` |

Right-click a point with a kit: empty => fit; same ERA type, spent cells => refill. Mount nodes (kit or `#hull_tool`
carried): fit / refill / remove / weld. Weld = carried `#hull_tool`; bent bar free, broken bar 1 of
`#combinedassault:slat_repair_stock` (iron, steel ingots). Holding a kit or a `#hull_tool` outlines valid points.

### 16.2 What they do

| threat | light ERA | heavy ERA | slat |
| --- | --- | --- | --- |
| `heat` | jet `-65%`, fires | `-75%`, fires | nose on bar => fires 0.3–0.6 off hull, jet `/(1+0.6*gap)`; body wedged (60–110 mm) => `80%` dud, else jet `x0.5` |
| `tandem_heat` | precursor spends brick, jet untouched | same | wedged => `10%` dud, else jet `x0.9` |
| `kinetic` | `-5%`, fires ≥ 20 mm | `-35%`, fires ≥ 35 mm | bar struck or body wedged => bar broken, `-15 mm` |
| `small_arms` | stopped, brick survives | same | bar struck => stopped; gap => through |
| `he` | fires ≥ 76 mm; blast unchanged | same | bar or wedge => bursts on cage |

- ERA cut x `clamp(sin(obliquity)/sin 60°, 0.3, 1)`; within 12% of a brick edge x0.5. Spent cell = hole.
- Brick detonation: 1.2-block blast, no blocks, hurts nearby infantry. No sympathetic detonation.
- Blast: 1024 rays over reach `2*size`, fixed energy each => fluence `~size/d^2`. First surface consumes: hull,
  live brick (ERA ignores blast, shields). Bar takes cover `c = (15 mm + 50 mm*tan) / pitch` of a ray, passes
  `1-c`; bend `= sum c * 2*size*(4pi/1024)*(1-d/reach)^2 / (pitch*width)`, < 0.05 ignored. Hull blast damage x
  surviving ray share.
  WFLib shaped charge: rays inside its cone only; hull outside the cone takes nothing.
- Wedge = body diameter > gap between present neighbours; a broken bar doubles the gap.
- Round penetration 0: the same cut applies to direct-hit damage.
- Threat = the WFLib round's (`wflib:heat` 450 mm, `wflib:ap` 550 mm, shells otherwise `he`; cartridges by warhead;
  missiles `heat` for `shaped_charge`, else `he`).
- A burst on a panel moves the warhead's blast there (`ProjectileStrikeEvent.detonation`).
- TACZ rounds (taczwf + WFLib): hit test via `PreciseHitbox` (hull + panels, not the AABB); threat and
  warhead per ammo id, taczwf `taczwf/ordnance`. RPG-7 = 85 mm `heat`.
- Gap: WFLib missiles hurt hulls by blast only, so ERA on them spends a brick and nothing else.
- `/combinedassault ballistics addon fill <era_light|era_heavy|slat> | clear | stats | shoot <mount> <round> [obliquity]`:
  nearest vehicle with `addon_armor` within 32 blocks.
- Shipped: `m1a2` (ERA skirts, slat rear/rear sides/turret bustle), socket frames unbaked.

## 17. Cargo holds and parachutes

`cargo`:

| field | type | default | notes |
| --- | --- | --- | --- |
| `door` | part id | *required* | switchable part closing the hold |
| `max_mass` | float | `0` | kg over stowed vehicles' current mass; `0` = no limit |
| `bay_center`, `bay_size` | Vec3 | from `cargo_bay` socket | box, vehicle frame; both required |

- Door shut => every vehicle with origin in the bay stowed: entity NBT inside the carrier (no tick, no collision; saved, unloaded, dimension-changed with it), drawn rest pose / authored paint, mass added to the carrier. Mounts on a stowed vehicle stow with it.
- Close refused (reason to the player; pilot boarding leaves it open) while a vehicle in the bay is occupied, tow-coupled, outside the box by > 0.3, or over `max_mass`.
- Door open + carrier on ground below 0.3 b/t => all back at their stowed pose. Airborne => fitted-parachute vehicles leave rear-first (bay -Z end, 3 blocks below and behind), one per 20 ticks; unfitted never leave.
- Carrier destroyed or `/kill`ed => cargo released in place. `discard` (figure box, `/combinedassault reload`) keeps it in the NBT.
- Rebuild failure (vehicle id gone, UUID taken) => stays stowed, logged. Def loses `cargo` => saved hold carried unread.

Landing (ground vehicles; aircraft excluded): touchdown faster than 9 m/s vertical hurts, damage = `2 * max_health * ((v - 9) / 16)^2` at the hull bottom (25 m/s = wreck). Sprung hulls land on suspension, which reports no contact impact.

Cargo parachute (`combinedassault:cargo_parachute`): use on a vehicle to fit, sneak-use to take back; not aircraft or UAVs. Opens falling > 0.6 b/t, or 10 ticks after leaving a hold. Quadratic drag to 0.35 b/t (7 m/s), opening shock <= 0.5 b/t per tick, rights the hull; spent on ground/water. Canopies: one per 8 t, up to 6.

## 18. Air defence

- Per station: `air_defense` ([§4.4](#44-weapon-stations--weapon-auto_weapon)). Shipped: `mim104` (`uav`; four `LARGE` canisters on the elevating launcher; `indirect_fire` map); `laser_trailer` (LOCUST module on towed chassis), `laser_emplacement` (LOCUST cabinet; `mountable` `naval_emplacement`): weapon `laser`; turret = part mounts `weapon/locust/locust_yoke` (yaw node) + `locust_head` (pitch node).
- Laser station `AUTO`: same battery; lead speed 1e6, fire = beam tick with clear line, energy-gated. Damage: wflib `LaserDamage` per vehicle mass (kg) + nominal armour of the face toward the source; `DamageSystem` LASER = no threshold, THERMAL wear at nearest hull point. Measured: Mavic burns, MQ-9 > 95 % after 100 beam ticks (`LaserGameTest`). Probe: no salvo / interceptor rows.

| field | default | |
| --- | --- | --- |
| `range` | `384` | engagement + own radar range, blocks |
| `mast` | `3` | own radar antenna height |
| `min_elevation` / `max_elevation` | `15` / `80` | launch window, degrees up; lead point outside => clamped |
| `salvo_spacing` | `10` | ticks between rounds of a salvo (>= weapon `shoot_interval`) |
| `cooldown` | `40` | ticks after a salvo |
| `dwell` | `40` | laser station: beam ticks per engagement (replaces salvo/spacing) |

- Hull node: mode `MANUAL` / `AUTO`, target source (radar network / own radar), salvo 1 / 2, claim / release.
- Owner = claimed side (WarForge faction, else scoreboard team), else crew team. Settings, claim and UAV controller pairing: owner's allies only; anyone while unowned. Missiles carry the owner's side.
- `MANUAL`: operator via UAV controller. `R` locks the engageable missile / drone nearest the sight line (3°) => launcher tracks it; fire => interceptor LOCKed on it, else NEAREST. Map fire missions ([§9.3](#93-fire-missions-from-the-map)) offer non-interceptor rounds only.
- `AUTO`, station empty: WFLib `InterceptBattery`; launcher laid on the lead point's bearing, elevation clamped to the window; interceptors only.
  - radar network: owner's recon net picture; none => "no radar coverage", holds.
  - own radar: surveillance radar registered on the owner's net (feeds it too).
  - Unowned => private net: the shared unaffiliated net reads unaffiliated missiles as friendly.
- Interceptor leaves along its canister (WFLib `intercept` ascent keeps a launch velocity).
- `uav` vehicles = `InterceptTarget` class `DRONE` (side = claim, else operator faction). Gap: manned aircraft/vehicles are not => never engaged.

---

## 19. Autonomy

`uav` + `autonomy` => `VehicleDrone`: wflib `ControllableDrone` (missions, squads, telemetry, pads). Absent => manual only.

| field | default | notes |
| --- | --- | --- |
| `tasks` | `[]` | wflib `DroneTask.Kind` ids flown: `moveto`, `loiter`, `exfil`, ...; others refused at dispatch |
| `cruise_speed` | wflib `0.75` | blocks/tick |
| `cruise_altitude` | wflib `40` | blocks AGL; ground pilot forces `0` |
| `min_speed` | `0.8` | fixed wing: orbit / climb-out floor; rotates at 0.9x on the roll |
| `release_speed` | cruise | attack-run speed, blocks/tick |

Pilot by class: `multirotor` => `Multirotor.step` setpoint; rotary => hover loop with velocity target; `fixed_wing_vehicle` => aim + throttle steps, slow setpoint = orbit; wheeled/tracked => steer + bang-bang speed. `generic` has none (refused).

- Plans fly only while nobody is aboard: remote pilot suspends; dismount => resumes from current position.
- Airborne => engine on, own chunk tickets along the route; landed IDLE => engine off.
- Charge = energy / full-power burn x wflib hover drain => wflib reserves apply unchanged.
- Drone pad: an idle body within 8 blocks that flies the kind takes the pad's dispatch before a wflib drone is spawned; home = pad.
- Gaps: fixed wing lands by descending orbit (no runway approach); ground pilot has no path planning.

---

## 20. Loadouts

```json
"loadouts": [
  {"id": "plain", "flight_mode": "stabilized"},
  {"id": "kamikaze", "part": "hardpoint", "payload": {"type": "combinedassault:warhead", "arming_ticks": 40}, "flight_mode": "fpv"}
]
```

| field | notes |
| --- | --- |
| `id` | lang `loadout.combinedassault.<id>` |
| `part` | `payload` part the hardware goes on; absent = nothing to fit |
| `payload` | type + config, with `part` (both or neither, else load error); strapped items = cost |
| `flight_mode` | multirotor default on selection |

- First loadout applied at first spawn. Modding tool button cycles: free, strapped hardware back to the player.
- Probe node on current loadout's `part`: strap one accepted item per action; take off => refund.
- UAV + use with item: current payload accepts => strapped; else first payload loadout accepting it selected, then strapped. Item fits => hull probe storage row omitted (else it claims use).
- Load errors: unknown part, not a payload part, unknown payload type.

| payload type | holds | trigger | config |
| --- | --- | --- | --- |
| `warhead` | 1 `#wflib:drone_explosive` (wflib `StrappedExplosive`) | impact >= `fuze_speed`, operator (LMB), shot down => consumes drone; release (secondary shoot) => unpowered wflib round, drone velocity, impact fuze | `arming_ticks` 40, `fuze_speed` 0.12, `hit_damage` 30, `slots` 1 |
| `grenade_rack` | `#mineassault:drone_droppable` `ChargeItem`s | operator only; one dropped per trigger, drone velocity, full fuse | `slots` 4 (<= 4 synced) |
| `speaker` | 1 `wflib:loudspeaker` (0.6 kg) | none; carrier radio voice = SPEAKER | - |

- Autopilot STRIKE ([§19](#19-autonomy)): offered only while loaded; rack drops one per release tick (stick); `warhead` => terminal dive on the aim point.
- Shipped: `mavic`, `amazog` (plain / kamikaze / bomber / speaker).
- Radio (wflib `RadioCarrier`): synced `RADIO` station, NBT `Radio`; voice = SPEAKER if `speaker` loaded, else CABIN for non-UAV hulls with seats and not destroyed. Key K (`radio_station`) cycles while riding; PDA Radio app for linked bodies. HUD `hud/radio`.

## 21. Hardware kits

Gun = kit def; vehicle part = typed `hardpoint`. Fitted / taken off in the field with the kit item, no modding tool.

```json
// combinedassault/<ns>/hardware/m2.json
{"model": "combinedassault:models/weapon/m2/m2.gltf", "weapons": ["combinedassault:gun_12.7mm"],
 "fits": ["pintle", "tripod"], "install_ticks": 120, "station": {"optical_sight_type": "operator", "zoom_max": 2}}

// vehicle part (type combinedassault:weapon, no weapons)
"hardpoint": {"node": "weapon", "accepts": ["tripod"], "fitted": null,
  "overrides": {"combinedassault:m2": {"vehicle_name": "entity.combinedassault.spg9_m2", "rot_info": {"x_rot_min": -30}}}}
```

| kit field | notes |
| --- | --- |
| `model` | mount glTF, grafted under the hardpoint `node`; role nodes `bolt`, `hit`, `hit_surface` only |
| `weapons` | [§4.5](#45-the-weapons-list) entries; each gets `kit` + `save_id` `<kit>/<weapon>` |
| `fits` | hardpoint classes (free strings) |
| `install_ticks` | fit / take off duration, default 100 |
| `station` | weapon part keys over the hardpoint part's own |

| hardpoint field | notes |
| --- | --- |
| `node` | vehicle glTF node the kit model grafts under |
| `accepts` | classes; kits = every loaded kit with a `fits` class here, by id; none => load error |
| `fitted` | kit at first spawn; absent / null = empty |
| `overrides` | kit id -> station keys for this vehicle only, + `vehicle_name` (lang key: vehicle display name while fitted) |

- Station keys: `pose`, `seat_offset`, `seat_rot`, `dismount_offset`, `protect_passenger`, `passenger_can_use_item`, `crew_roles`, `rot_info`, `bolts`, `ejects`, `ammo_capacity`, `firing_mode`, `optical_sight_offset`, `operator_view_offset`, `operator_on_weapon_unit`, `fire_control_sensor_type`, `optical_sight_type`, `with_*`, `loading`, `zoom_min`, `zoom_max`, `crosshair_style`, `handling`, `optic`. Other key => load error.
- Merge: part (glTF sockets applied) < kit `station` < hardpoint override; objects (`rot_info`) merge by key, else replace.
- No kit => no gun: `currentWeaponId` null, kit mounts hidden and unhit, never fires. Seat switch cycles within the fitted kit; modding tool swaps refused.
- Item `combinedassault:hardware`, component `combinedassault:hardware_kit` = kit id; name lang `hardware.<ns>.<path>`; drawn from the kit model. Recipes: result `{"id": "combinedassault:hardware", "components": {"combinedassault:hardware_kit": "<kit>"}}`.
- Probe node `hardpoint:<part index>`: empty => one timed `hardpoint_fit` row per kit (arg = part << 8 | kit index), item consumed; fitted => timed `hardpoint_take_off`, item back. Refused: wreck, part detached, kit already on, eye > 4 blocks from pivot, pivot > 1.5 m behind hull surface from the eye, a seat of the part occupied, item missing (creative exempt).
- NBT `Kit` (id, "" = empty) per hardpoint part.
- Shipped kits: `m2`, `mk19` (`pintle`, `tripod`), `spg9` (`tripod`). Hardpoints: `humvee` roof (`pintle`, empty), `riverine` bows / stern (`pintle`, M2 / M2 / Mk 19), `spg9` weapon tripod (`tripod`, empty, `kneel`).
