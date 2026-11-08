// CombinedAssault vehicle authoring for Blockbench 5.1 (desktop). Install: File > Plugins > Load Plugin from File.
(function () {
'use strict';

// ---------------------------------------------------------------- vocabularies (tools/blender/combinedassault_tools.py)

const SYSTEMS = ['hull', 'engine', 'track', 'wheel', 'turret', 'barrel', 'fuel', 'ammo', 'optics', 'rotor',
	'tail_rotor', 'wing'];
const SOCKET_FIELDS = ['seat', 'dismount', 'view', 'sight', 'sight_lens', 'bolt', 'eject', 'era', 'slat', 'mount_point', 'hitch', 'towable',
	'axle', 'exhaust', 'vortex', 'smoke', 'afterburner', 'camera', 'camera_zoomed', 'light', 'cargo_bay',
	'center_of_mass', 'pivot', 'track', 'propeller', 'station'];
const MESH_FIELDS = ['era', 'slat', 'track', 'sight_lens'];
const UNTARGETED = ['towable', 'axle', 'camera', 'camera_zoomed', 'exhaust', 'vortex', 'smoke', 'afterburner', 'light',
	'cargo_bay', 'center_of_mass', 'pivot', 'propeller'];
const ID_TARGETED = ['era', 'slat', 'mount_point', 'hitch'];
// Node scale carries a size the game reads; Blockbench nodes always export scale 1.
const SCALED_FIELDS = {bolt: 'barrel_length', eject: 'ejects speed', station: 'holder round length', propeller: 'radius', cargo_bay: 'cargo.bay_size'};
const ROLE = 'combinedassaultRole';
const INTERFACE = 'combinedassaultInterface';
const META = 'combinedassault';
const HIDDEN = 'gemrenderHidden';
const CLIP_NAME = /^[a-z0-9_]+(\.[A-Za-z0-9_.]+)?$/;
const RESOURCE_PATH = /^[a-z0-9_.\-/]+$/;
const NAMESPACE = /^[a-z0-9_.\-]+$/;
// GltfMaterial.CUTOUTS
const CUTOUTS = [0.0, 0.1, 0.5];
const PX = 16;

const ROLES = {
	none: 'Model (drawn, hit as surface)',
	body: 'Body frame (hull, exactly one)',
	part: 'Part frame (turret, wheel, door)',
	pitch: 'Pitch frame (gun elevation)',
	collider: 'Collider (convex, hidden)',
	hit: 'Hit module (internal system)',
	hit_surface: 'Hit surface (replaces parent)',
	socket: 'Socket (seat, view, bolt...)',
};
const FRAMES = ['body', 'part', 'pitch'];
const SHAPES = ['collider', 'hit', 'hit_surface'];

const DEFAULTS = {
	role: 'none', interface: '', field: 'seat', index: -1,
	label: '', main: false, deck: false, solid: true, world: true, hit: false, locked: false,
	system: 'engine', side: 'none',
	meta_system: 'auto', skip: false, swept: 'auto', health: 0,
	limit_pitch: false, x_rot_min: -90, x_rot_max: 90, limit_yaw: false, y_rot_min: -180, y_rot_max: 180,
	hidden: false, follows_clips: false, unit: '', spin: 'none', road_wheel: false,
	light_kind: 'head', light_color: '', light_range: 0, light_intensity: 0, light_inner: 0, light_outer: 0,
	light_shadow: true, light_cookie: '',
};
const LIGHT_KINDS = {head: 'Headlight (driver toggles)', search: 'Searchlight (other crew toggle)'};
// VehicleLight.Kind defaults: color, range (blocks), inner / outer half angle (deg).
const LIGHT_KIND_DEFAULTS = {head: {color: 0xFFF1D8, range: 40, inner: 12, outer: 32}, search: {color: 0xEEF4FF, range: 96, inner: 2.5, outer: 6}};
const HEX_COLOR = /^#[0-9a-f]{6}$/i;

/** Light socket keys: kind always, the rest only when set (0 / empty / on = the kind's default). */
function lightMeta(s) {
	const meta = {kind: s.light_kind};
	if (HEX_COLOR.test(s.light_color)) meta.color = s.light_color;
	for (const key of ['range', 'intensity', 'inner', 'outer']) if (s['light_' + key] > 0) meta[key] = s['light_' + key];
	if (!s.light_shadow) meta.shadow = false;
	if (s.light_cookie) meta.cookie = s.light_cookie;
	return meta;
}

function settingsOf(props) {
	return Object.assign({}, DEFAULTS, props || {});
}

/** node_extras() of the Blender add-on; {} = untagged. */
function nodeExtras(props) {
	const s = settingsOf(props);
	const role = s.role;
	const extras = {};
	const meta = {};
	if (role !== 'none') extras[ROLE] = role === 'socket' ? s.field : role;
	if (role === 'part' || role === 'pitch' || (role === 'socket' && s.interface)) extras[INTERFACE] = s.interface;
	if (SHAPES.includes(role) || role === 'socket' || s.hidden) extras[HIDDEN] = true;
	if (role === 'collider') {
		if (s.main) meta.main = true;
		if (s.deck) meta.deck = true;
		if (!s.solid) meta.solid = false;
		if (!s.world) meta.world = false;
		if (s.label) meta.name = s.label;
		if (s.hit) meta.hit = true;
		if (s.locked) extras.genHulls = {locked: true};
	} else if (role === 'socket') {
		if (s.index >= 0) meta.index = s.index;
		if (s.field === 'light') Object.assign(meta, lightMeta(s));
	} else {
		if (role === 'hit') meta.system = s.system;
		else if (s.meta_system !== 'auto') meta.system = s.meta_system;
		if (s.side !== 'none') meta.side = s.side;
		if (s.skip) meta.skip = true;
		if (s.swept !== 'auto') meta.swept = s.swept === 'on';
		if (s.unit) meta.unit = s.unit;
		if (s.spin !== 'none') meta.spin = s.spin;
		if (s.health > 0) meta.health = s.health;
		if (role === 'none' && s.road_wheel) meta.road_wheel = true;
		if (FRAMES.includes(role) && s.follows_clips) meta.follows_clips = true;
		if ((role === 'part' || role === 'pitch') && s.limit_pitch) {
			meta.x_rot_min = s.x_rot_min;
			meta.x_rot_max = s.x_rot_max;
		}
		if (role === 'part' && s.limit_yaw) {
			meta.y_rot_min = s.y_rot_min;
			meta.y_rot_max = s.y_rot_max;
		}
	}
	if (Object.keys(meta).length) extras[META] = meta;
	return extras;
}

// Meta keys the panel writes; every other `combinedassault` key (light kind, color, ...) passes through.
const OWNED_META = ['main', 'deck', 'solid', 'world', 'name', 'hit', 'index', 'system', 'side', 'skip', 'swept', 'health',
	'x_rot_min', 'x_rot_max', 'y_rot_min', 'y_rot_max', 'unit', 'spin', 'road_wheel', 'follows_clips',
	'kind', 'color', 'range', 'intensity', 'inner', 'outer', 'shadow', 'cookie'];
// Explicit default in a source file: kept verbatim while the panel leaves it at that default.
const META_DEFAULTS = {
	collider: {main: false, deck: false, solid: true, world: true, hit: false},
	surface: {skip: false, health: 0, road_wheel: false, follows_clips: false},
};

/** Node extras -> panel settings (non-default keys only). Unknown role => throws. */
function splitExtras(extras) {
	const e = extras || {};
	const m = e[META] || {};
	const s = {};
	const role = e[ROLE];
	if (role === undefined) {
		s.role = 'none';
		if (e[HIDDEN]) s.hidden = true;
	}
	else if (FRAMES.includes(role) || SHAPES.includes(role)) s.role = role;
	else if (SOCKET_FIELDS.includes(role)) {
		s.role = 'socket';
		s.field = role;
	} else throw new Error(`unknown combinedassaultRole '${role}'`);
	if (e[INTERFACE]) s.interface = e[INTERFACE];
	if (s.role === 'collider') {
		for (const key of ['main', 'deck', 'solid', 'world', 'hit']) if (key in m) s[key] = !!m[key];
		if (m.name) s.label = m.name;
		if (e.genHulls && e.genHulls.locked) s.locked = true;
	} else if (s.role === 'socket') {
		if (typeof m.index === 'number') s.index = m.index;
		if (s.field === 'light') {
			if (m.kind) s.light_kind = m.kind;
			if (m.color) s.light_color = m.color;
			for (const key of ['range', 'intensity', 'inner', 'outer']) if (m[key]) s['light_' + key] = m[key];
			if (m.shadow === false) s.light_shadow = false;
			if (m.cookie) s.light_cookie = m.cookie;
		}
	} else {
		if (m.system !== undefined) s[s.role === 'hit' ? 'system' : 'meta_system'] = m.system;
		if (m.side) s.side = m.side;
		if (m.skip) s.skip = true;
		if (m.swept !== undefined) s.swept = m.swept ? 'on' : 'off';
		if (m.unit) s.unit = m.unit;
		if (m.spin) s.spin = m.spin;
		if (m.health) s.health = m.health;
		if (m.road_wheel) s.road_wheel = true;
		if (m.follows_clips) s.follows_clips = true;
		if ('x_rot_min' in m || 'x_rot_max' in m) Object.assign(s, {limit_pitch: true, x_rot_min: m.x_rot_min, x_rot_max: m.x_rot_max});
		if ('y_rot_min' in m || 'y_rot_max' in m) Object.assign(s, {limit_yaw: true, y_rot_min: m.y_rot_min, y_rot_max: m.y_rot_max});
	}
	for (const key of Object.keys(s)) if (s[key] === DEFAULTS[key] && key !== 'role') delete s[key];
	return s;
}

/** Source extras with the panel-owned keys replaced by nodeExtras(props); everything else verbatim. */
function mergeExtras(raw, props) {
	const out = raw ? JSON.parse(JSON.stringify(raw)) : {};
	const made = nodeExtras(props);
	const role = settingsOf(props).role;
	const rawMeta = out[META] || {};
	const meta = {};
	const defaults = role === 'collider' ? META_DEFAULTS.collider : role === 'socket' ? {} : META_DEFAULTS.surface;
	for (const key in rawMeta) {
		if (!OWNED_META.includes(key) || (key in defaults && rawMeta[key] === defaults[key])) meta[key] = rawMeta[key];
	}
	Object.assign(meta, made[META]);
	delete out[ROLE];
	delete out[INTERFACE];
	delete out[META];
	delete out[HIDDEN];
	if (out.genHulls) {
		delete out.genHulls.locked;
		if (!Object.keys(out.genHulls).length) delete out.genHulls;
	}
	for (const key in made) {
		if (key === META) continue;
		out[key] = key === 'genHulls' ? Object.assign(out.genHulls || {}, made.genHulls) : made[key];
	}
	if (Object.keys(meta).length) out[META] = meta;
	return out;
}

/** GltfMaterial.quantiseCutoff: nearest, ties to the lower. */
function quantiseCutoff(cutoff) {
	let chosen = CUTOUTS[0];
	for (const candidate of CUTOUTS) {
		if (Math.abs(cutoff - candidate) < Math.abs(cutoff - chosen)) chosen = candidate;
	}
	return chosen;
}

/** OPAQUE: every texel a=255; MASK: a in {0,255}; else BLEND. */
function alphaModeOf(rgba) {
	let cut = false;
	for (let i = 3; i < rgba.length; i += 4) {
		const a = rgba[i];
		if (a === 255) continue;
		if (a !== 0) return 'BLEND';
		cut = true;
	}
	return cut ? 'MASK' : 'OPAQUE';
}

function slashes(path) {
	return path.replace(/\\/g, '/').replace(/\/+$/, '');
}

function cleanModelPath(model) {
	return slashes(model.trim()).replace(/^\/+/, '').replace(/\.(glb|gltf)$/i, '');
}

function packPaths(packRoot, ns, model) {
	const root = slashes(packRoot);
	const modelPath = cleanModelPath(model);
	const assets = `${root}/assets/${ns}`;
	const dir = modelPath.includes('/') ? modelPath.slice(0, modelPath.lastIndexOf('/')) : '';
	return {
		glb: `${assets}/models/${modelPath}.glb`,
		textureDir: `${assets}/textures${dir ? '/' + dir : ''}`,
		textureLocation: `${ns}:textures${dir ? '/' + dir : ''}`,
	};
}

/** `<ns>:<path under assets/<ns>/>` for a file under <packRoot>/assets/, else null. */
function resourceLocationOf(packRoot, file) {
	const prefix = slashes(packRoot) + '/assets/';
	const path = slashes(file);
	if (!path.startsWith(prefix)) return null;
	const rest = path.slice(prefix.length);
	const cut = rest.indexOf('/');
	if (cut <= 0) return null;
	return `${rest.slice(0, cut)}:${rest.slice(cut + 1)}`;
}

function pngName(name) {
	return /\.png$/i.test(name) ? name : name + '.png';
}

function decodeDataUri(uri) {
	return Buffer.from(uri.slice(uri.indexOf(',') + 1), 'base64');
}

const GLB_MAGIC = 0x46546C67;
const CHUNK_JSON = 0x4E4F534A;
const CHUNK_BIN = 0x004E4942;

/** .glb -> {gltf, bin (BIN chunk or null)}; a .gltf (JSON text) -> {gltf, bin: null}. */
function readContainer(data) {
	const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
	if (buf.length < 12 || buf.readUInt32LE(0) !== GLB_MAGIC) return {gltf: JSON.parse(buf.toString('utf8')), bin: null};
	const jsonLength = buf.readUInt32LE(12);
	if (buf.readUInt32LE(16) !== CHUNK_JSON) throw new Error('GLB: first chunk is not JSON');
	const gltf = JSON.parse(buf.toString('utf8', 20, 20 + jsonLength));
	const at = 20 + jsonLength;
	const bin = at + 8 <= buf.length && buf.readUInt32LE(at + 4) === CHUNK_BIN
		? buf.subarray(at + 8, at + 8 + buf.readUInt32LE(at)) : null;
	return {gltf, bin};
}

/** glTF JSON + BIN chunk -> .glb bytes; buffer 0 has neither uri nor extras. */
function writeGlb(gltf, bin) {
	if (gltf.buffers) gltf.buffers[0] = {byteLength: bin.length};
	const text = Buffer.from(JSON.stringify(gltf), 'utf8');
	const json = Buffer.concat([text, Buffer.alloc((4 - text.length % 4) % 4, 0x20)]);
	const binary = Buffer.concat([bin, Buffer.alloc((4 - bin.length % 4) % 4)]);
	const head = Buffer.alloc(20);
	head.writeUInt32LE(GLB_MAGIC, 0);
	head.writeUInt32LE(2, 4);
	head.writeUInt32LE(12 + 8 + json.length + (gltf.buffers ? 8 + binary.length : 0), 8);
	head.writeUInt32LE(json.length, 12);
	head.writeUInt32LE(CHUNK_JSON, 16);
	if (!gltf.buffers) return Buffer.concat([head, json]);
	const chunk = Buffer.alloc(8);
	chunk.writeUInt32LE(binary.length, 0);
	chunk.writeUInt32LE(CHUNK_BIN, 4);
	return Buffer.concat([head, json, chunk, binary]);
}

const UUID = 'combinedassaultUuid';
const COMPONENTS = {5120: ['Int8', 1, 127], 5121: ['Uint8', 1, 255], 5122: ['Int16', 2, 32767], 5123: ['Uint16', 2, 65535],
	5125: ['Uint32', 4, 0], 5126: ['Float32', 4, 0]};
const WIDTH = {SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT2: 4, MAT3: 9, MAT4: 16};

/** Accessor -> rows of numbers (normalized integers scaled to [0,1] / [-1,1]). */
function readAccessor(gltf, buffers, index) {
	const a = gltf.accessors[index];
	const component = COMPONENTS[a.componentType];
	const width = WIDTH[a.type];
	if (!component || !width) throw new Error(`accessor ${index}: component type ${a.componentType} / ${a.type} not supported`);
	const [type, size, max] = component;
	const rows = [];
	const scale = v => a.normalized && max ? Math.max(v / max, -1) : v;
	const rowsOf = (view, base, count, rowWidth) => {
		const buffer = buffers[view.buffer || 0];
		const data = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
		const stride = view.byteStride || rowWidth * size;
		const get = data['get' + type].bind(data);
		const out = [];
		for (let i = 0; i < count; i++) {
			const row = [];
			for (let c = 0; c < rowWidth; c++) row.push(scale(get(base + i * stride + c * size, true)));
			out.push(row);
		}
		return out;
	};
	if (a.bufferView === undefined) {
		for (let i = 0; i < a.count; i++) rows.push(new Array(width).fill(0));
	} else {
		const view = gltf.bufferViews[a.bufferView];
		rows.push(...rowsOf(view, (view.byteOffset || 0) + (a.byteOffset || 0), a.count, width));
	}
	if (a.sparse) {
		const {count, indices, values} = a.sparse;
		const indexView = gltf.bufferViews[indices.bufferView];
		const indexBuffer = buffers[indexView.buffer || 0];
		const indexData = new DataView(indexBuffer.buffer, indexBuffer.byteOffset, indexBuffer.byteLength);
		const indexType = COMPONENTS[indices.componentType];
		if (!indexType) throw new Error(`accessor ${index}: sparse index type ${indices.componentType} not supported`);
		const indexBase = (indexView.byteOffset || 0) + (indices.byteOffset || 0);
		const valueView = gltf.bufferViews[values.bufferView];
		const substitutes = rowsOf(Object.assign({}, valueView, {byteStride: 0}), (valueView.byteOffset || 0) + (values.byteOffset || 0), count, width);
		for (let i = 0; i < count; i++) {
			rows[indexData['get' + indexType[0]](indexBase + i * indexType[1], true)] = substitutes[i];
		}
	}
	return rows;
}

function appendView(state, bytes) {
	const pad = (4 - state.bin.length % 4) % 4;
	state.bin = Buffer.concat([state.bin, Buffer.alloc(pad), bytes]);
	state.gltf.bufferViews.push({buffer: 0, byteOffset: state.bin.length - bytes.length, byteLength: bytes.length});
	return state.gltf.bufferViews.length - 1;
}

function appendAccessor(state, array, type, componentType, bounds) {
	const view = appendView(state, Buffer.from(array.buffer, array.byteOffset, array.byteLength));
	const accessor = {bufferView: view, componentType, count: array.length / WIDTH[type], type};
	if (bounds) Object.assign(accessor, bounds);
	state.gltf.accessors.push(accessor);
	return state.gltf.accessors.length - 1;
}

/** One primitive per face, shared POSITION, no material: the source form of ModelRig colliders. */
function colliderMesh(state, polygon) {
	const flat = new Float32Array(polygon.points.flat());
	const min = [0, 1, 2].map(j => polygon.points.reduce((m, p) => Math.min(m, Math.fround(p[j])), Infinity));
	const max = [0, 1, 2].map(j => polygon.points.reduce((m, p) => Math.max(m, Math.fround(p[j])), -Infinity));
	const position = appendAccessor(state, flat, 'VEC3', 5126, {min, max});
	const primitives = polygon.loops.map(loop => {
		const tris = [];
		for (let k = 1; k + 1 < loop.length; k++) tris.push(loop[0], loop[k], loop[k + 1]);
		return {attributes: {POSITION: position}, indices: appendAccessor(state, new Uint32Array(tris), 'SCALAR', 5125), mode: 4};
	});
	return {primitives};
}

/** Keep only referenced accessors / bufferViews; rebuild the buffer 4-aligned. */
function repack(gltf, bin) {
	const accessorRefs = [];
	for (const mesh of gltf.meshes || []) {
		for (const p of mesh.primitives) {
			for (const key in p.attributes) accessorRefs.push([p.attributes, key]);
			if (p.indices !== undefined) accessorRefs.push([p, 'indices']);
			for (const target of p.targets || []) for (const key in target) accessorRefs.push([target, key]);
		}
	}
	for (const animation of gltf.animations || []) {
		for (const sampler of animation.samplers) accessorRefs.push([sampler, 'input'], [sampler, 'output']);
	}
	for (const skin of gltf.skins || []) if (skin.inverseBindMatrices !== undefined) accessorRefs.push([skin, 'inverseBindMatrices']);
	const accessorMap = new Map();
	const accessors = [];
	for (const [holder, key] of accessorRefs) {
		const old = holder[key];
		if (!accessorMap.has(old)) {
			accessorMap.set(old, accessors.length);
			accessors.push(gltf.accessors[old]);
		}
		holder[key] = accessorMap.get(old);
	}
	const viewRefs = accessors.filter(a => a.bufferView !== undefined).map(a => [a, 'bufferView'])
		.concat((gltf.images || []).filter(i => i.bufferView !== undefined).map(i => [i, 'bufferView']));
	const viewMap = new Map();
	const views = [];
	const chunks = [];
	let length = 0;
	for (const [holder] of viewRefs) {
		const old = holder.bufferView;
		if (!viewMap.has(old)) {
			const view = Object.assign({}, gltf.bufferViews[old]);
			const pad = (4 - length % 4) % 4;
			if (pad) chunks.push(Buffer.alloc(pad));
			chunks.push(bin.subarray(view.byteOffset || 0, (view.byteOffset || 0) + view.byteLength));
			view.byteOffset = length + pad;
			view.buffer = 0;
			length = view.byteOffset + view.byteLength;
			viewMap.set(old, views.length);
			views.push(view);
		}
		holder.bufferView = viewMap.get(old);
	}
	gltf.accessors = accessors;
	gltf.bufferViews = views;
	return Buffer.concat(chunks);
}

/**
 * Blockbench glTF (ascii, embed_textures off, scale 16) -> pack form.
 * opts.textures: {<image name>: {file?, data?, alpha, doubleSided}}: file = saved PNG on disk, data = base64 PNG.
 * opts.polygons: {<node combinedassaultUuid>: {points (blocks, node space), loops}}: collider meshes, face per primitive.
 * opts.meshes: {<node combinedassaultUuid>: [{texture: image name | null, material: kept material | null, positions,
 * normals, uvs, indices}]}: Blockbench Mesh elements, one primitive per (texture, material).
 * Returns {gltf, bin, writes: [{path, data}], locations: {<image name>: resourceLocation}}.
 */
function finishGltf(gltf, opts) {
	const paths = packPaths(opts.packRoot, opts.namespace, opts.modelPath);
	const buffers = gltf.buffers || [];
	if (buffers.length > 1) throw new Error(`expected one glTF buffer, got ${buffers.length}`);
	let bin = Buffer.alloc(0);
	if (buffers.length) {
		const uri = buffers[0].uri || '';
		if (!uri.startsWith('data:')) throw new Error('glTF buffer is not embedded');
		bin = decodeDataUri(uri);
		gltf.buffers = [{byteLength: bin.length}];
	}

	const state = {gltf, bin, materials: new Map(), kept: new Set()};
	for (const node of gltf.nodes || []) {
		const uuid = node.extras && node.extras[UUID];
		if (uuid === undefined) continue;
		delete node.extras[UUID];
		if (!Object.keys(node.extras).length) delete node.extras;
		const polygon = opts.polygons && opts.polygons[uuid];
		const groups = opts.meshes && opts.meshes[uuid];
		if (polygon) {
			if (node.mesh === undefined) node.mesh = gltf.meshes.push({}) - 1;
			gltf.meshes[node.mesh] = colliderMesh(state, polygon);
		} else if (groups) {
			if (!groups.length) delete node.mesh;
			else {
				if (node.mesh === undefined) node.mesh = (gltf.meshes = gltf.meshes || []).push({}) - 1;
				gltf.meshes[node.mesh] = elementMesh(state, groups);
			}
		}
	}

	const images = gltf.images || [];
	const textures = gltf.textures || [];
	const materials = gltf.materials || [];
	const imageOfMaterial = m => {
		const t = m.pbrMetallicRoughness && m.pbrMetallicRoughness.baseColorTexture;
		return t ? images[textures[t.index].source] : null;
	};
	// Blockbench's hidden-face material: no Blockbench texture behind its 1 px image.
	const invisible = new Set();
	materials.forEach((m, i) => {
		const image = imageOfMaterial(m);
		if (image && !(image.name in opts.textures)) {
			if (image.name !== 'Unknown') throw new Error(`no texture named ${image.name}`);
			invisible.add(i);
		}
	});
	for (const mesh of gltf.meshes || []) {
		mesh.primitives = mesh.primitives.filter(p => !invisible.has(p.material));
	}
	(gltf.nodes || []).forEach(node => {
		if (node.mesh === undefined) return;
		const primitives = gltf.meshes[node.mesh].primitives;
		if (!primitives.length) {
			delete node.mesh;
			return;
		}
		// Never drawn, never a plate: an untextured material would only be noise.
		const extras = node.extras || {};
		if (extras[HIDDEN] && extras[ROLE] !== 'hit_surface') {
			for (const p of primitives) {
				if (p.material !== undefined && !imageOfMaterial(materials[p.material])) delete p.material;
			}
		}
	});
	prune(gltf);

	const writes = [];
	const locations = {};
	for (const image of gltf.images || []) {
		if (image.extras && image.extras.resourceLocation) continue;
		const info = opts.textures[image.name];
		let location = info.file ? resourceLocationOf(opts.packRoot, info.file) : null;
		if (!location) {
			const file = pngName(image.name);
			location = `${paths.textureLocation}/${file}`;
			writes.push({path: `${paths.textureDir}/${file}`, data: Buffer.from(info.data, 'base64')});
		}
		locations[image.name] = location;
		delete image.uri;
		image.mimeType = 'image/png';
		image.extras = Object.assign({}, image.extras, {resourceLocation: location});
	}
	for (const material of gltf.materials || []) {
		const image = imageOfMaterial(material);
		const info = image && !state.kept.has(material) ? opts.textures[image.name] : null;
		if (info) {
			material.name = image.name.replace(/\.png$/i, '');
			material.alphaMode = info.alpha;
			material.doubleSided = !!info.doubleSided;
		}
		if (material.alphaMode === 'MASK') {
			material.alphaCutoff = quantiseCutoff(material.alphaCutoff === undefined ? 0.5 : material.alphaCutoff);
		} else {
			delete material.alphaCutoff;
		}
		if (!material.doubleSided) delete material.doubleSided;
	}
	gltf.asset.extras = Object.assign({}, gltf.asset.extras, {staged_by: 'tools/blockbench/combinedassault.js'});
	if (gltf.buffers) {
		bin = repack(gltf, state.bin);
		gltf.buffers[0].byteLength = bin.length;
	}
	return {gltf, bin, writes, locations, paths};
}

function textureOf(gltf, imageName) {
	gltf.images = gltf.images || [];
	gltf.textures = gltf.textures || [];
	let image = gltf.images.findIndex(i => i.name === imageName);
	if (image < 0) image = gltf.images.push({mimeType: 'image/png', name: imageName}) - 1;
	let texture = gltf.textures.findIndex(t => t.source === image);
	if (texture < 0) texture = gltf.textures.push({source: image}) - 1;
	return texture;
}

/** glTF material index for (texture, kept material); kept = imported material fields (materialSource). */
function materialFor(state, texture, kept) {
	const key = `${texture}|${JSON.stringify(kept)}`;
	if (state.materials.has(key)) return state.materials.get(key);
	const gltf = state.gltf;
	const material = {pbrMetallicRoughness: {metallicFactor: 0, roughnessFactor: 1}};
	const base = texture !== null ? textureOf(gltf, texture) : null;
	if (base !== null) material.pbrMetallicRoughness.baseColorTexture = {index: base};
	if (kept) {
		restoreMaterial(gltf, material, kept.source, base);
		Object.assign(material, {name: kept.name, alphaMode: kept.alphaMode, alphaCutoff: kept.alphaCutoff,
			doubleSided: kept.doubleSided});
		if (material.name === undefined) delete material.name;
		state.kept.add(material);
	}
	gltf.materials = gltf.materials || [];
	const index = gltf.materials.push(material) - 1;
	state.materials.set(key, index);
	return index;
}

function elementMesh(state, groups) {
	return {primitives: groups.map(g => {
		const positions = new Float32Array(g.positions);
		const min = [Infinity, Infinity, Infinity];
		const max = [-Infinity, -Infinity, -Infinity];
		positions.forEach((v, i) => {
			min[i % 3] = Math.min(min[i % 3], v);
			max[i % 3] = Math.max(max[i % 3], v);
		});
		const attributes = {
			POSITION: appendAccessor(state, positions, 'VEC3', 5126, {min, max}),
			NORMAL: appendAccessor(state, new Float32Array(g.normals), 'VEC3', 5126),
		};
		if (g.texture !== null) attributes.TEXCOORD_0 = appendAccessor(state, new Float32Array(g.uvs), 'VEC2', 5126);
		return {attributes, indices: appendAccessor(state, new Uint32Array(g.indices), 'SCALAR', 5125), mode: 4,
			material: materialFor(state, g.texture, g.material)};
	})};
}

/** Source material fields over Blockbench's; extra maps re-added as resourceLocation images. */
function restoreMaterial(gltf, material, source, baseIndex) {
	const copy = JSON.parse(JSON.stringify(source || {}));
	for (const [outer, key] of MAP_SLOTS) {
		const holder = outer ? copy[outer] : copy;
		if (!holder || !holder[key]) continue;
		const {location, uri} = holder[key];
		delete holder[key].location;
		delete holder[key].uri;
		if (key === 'baseColorTexture') {
			if (baseIndex === null) delete holder[key];
			else holder[key].index = baseIndex;
			continue;
		}
		gltf.images.push(location ? {mimeType: 'image/png', extras: {resourceLocation: location}} : {uri});
		gltf.textures.push({source: gltf.images.length - 1});
		holder[key].index = gltf.textures.length - 1;
	}
	Object.assign(material, copy);
}

/** Drop materials, textures, images, samplers nothing references; reindex. */
function prune(gltf) {
	const remap = (list, used) => {
		const map = new Map();
		const kept = [];
		(list || []).forEach((item, i) => {
			if (used.has(i)) {
				map.set(i, kept.length);
				kept.push(item);
			}
		});
		return {kept, map};
	};
	const usedMaterials = new Set();
	for (const mesh of gltf.meshes || []) {
		for (const p of mesh.primitives) if (p.material !== undefined) usedMaterials.add(p.material);
	}
	const m = remap(gltf.materials, usedMaterials);
	for (const mesh of gltf.meshes || []) {
		for (const p of mesh.primitives) if (p.material !== undefined) p.material = m.map.get(p.material);
	}
	gltf.materials = m.kept;
	const textureSlots = mat => [mat.pbrMetallicRoughness && mat.pbrMetallicRoughness.baseColorTexture,
		mat.pbrMetallicRoughness && mat.pbrMetallicRoughness.metallicRoughnessTexture, mat.normalTexture,
		mat.occlusionTexture, mat.emissiveTexture].filter(Boolean);
	const usedTextures = new Set();
	gltf.materials.forEach(mat => textureSlots(mat).forEach(slot => usedTextures.add(slot.index)));
	const t = remap(gltf.textures, usedTextures);
	gltf.materials.forEach(mat => textureSlots(mat).forEach(slot => { slot.index = t.map.get(slot.index); }));
	gltf.textures = t.kept;
	const usedImages = new Set(gltf.textures.map(x => x.source));
	const usedSamplers = new Set(gltf.textures.filter(x => x.sampler !== undefined).map(x => x.sampler));
	const im = remap(gltf.images, usedImages);
	const sm = remap(gltf.samplers, usedSamplers);
	gltf.textures.forEach(x => {
		x.source = im.map.get(x.source);
		if (x.sampler !== undefined) x.sampler = sm.map.get(x.sampler);
	});
	gltf.images = im.kept;
	gltf.samplers = sm.kept;
	for (const key of ['materials', 'textures', 'images', 'samplers']) {
		if (!gltf[key].length) delete gltf[key];
	}
}

// ---------------------------------------------------------------- collider faces (ModelRig.hull, ConvexHull)

// ConvexHull.EPS / WELD, blocks.
const HULL_EPS = 1e-4;

function dot(a, b) {
	return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

function sub(a, b) {
	return [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
}

function cross(a, b) {
	return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}

function openEdges(faces) {
	const edges = new Map();
	for (const face of faces) {
		for (let i = 0; i < face.length; i++) {
			const a = face[i];
			const b = face[(i + 1) % face.length];
			const key = a < b ? `${a}|${b}` : `${b}|${a}`;
			edges.set(key, (edges.get(key) || 0) + 1);
		}
	}
	return [...edges].filter(([, count]) => count !== 2).map(([key]) => key);
}

/** Points within HULL_EPS share one index: {points, index: original -> welded}. */
function weld(points, eps) {
	const out = [];
	if (eps === 0) {
		const seen = new Map();
		const index = points.map(p => {
			const key = p.join(',');
			if (!seen.has(key)) seen.set(key, out.push(p) - 1);
			return seen.get(key);
		});
		return {points: out, index};
	}
	const cells = new Map();
	const index = points.map(p => {
		const cell = p.map(v => Math.round(v / eps));
		for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) {
			for (const j of cells.get(`${cell[0] + dx},${cell[1] + dy},${cell[2] + dz}`) || []) {
				const q = out[j];
				if ((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 + (p[2] - q[2]) ** 2 < eps * eps) return j;
			}
		}
		const key = cell.join(',');
		if (!cells.has(key)) cells.set(key, []);
		cells.get(key).push(out.length);
		out.push(p);
		return out.length - 1;
	});
	return {points: out, index};
}

/**
 * ModelRig.hull face loops: per group of triangles (flat [a,b,c,...]), its distinct points sorted by angle,
 * counter-clockwise seen from outside.
 */
function polygonLoops(points, groups) {
	const centre = [0, 1, 2].map(j => points.reduce((sum, p) => sum + p[j], 0) / Math.max(1, points.length));
	return groups.map(group => {
		const loop = [...new Set(group)];
		let n = [0, 0, 0];
		for (let k = 0; k + 2 < group.length; k += 3) {
			const c = cross(sub(points[group[k + 1]], points[group[k]]), sub(points[group[k + 2]], points[group[k]]));
			n = [n[0] + c[0], n[1] + c[1], n[2] + c[2]];
		}
		const mid = [0, 1, 2].map(j => loop.reduce((sum, v) => sum + points[v][j], 0) / loop.length);
		if (dot(n, sub(mid, centre)) < 0) n = n.map(x => -x);
		const u = sub(points[loop[0]], mid);
		const w = cross(n, u);
		const angle = v => Math.atan2(dot(sub(points[v], mid), w), dot(sub(points[v], mid), u));
		return loop.sort((a, b) => angle(a) - angle(b));
	});
}

/** One-primitive collider: ModelRig.hull merges its triangles by plane. */
function planeGroups(points, tris) {
	const planes = [];
	for (let k = 0; k + 2 < tris.length; k += 3) {
		const tri = tris.slice(k, k + 3);
		let n = cross(sub(points[tri[1]], points[tri[0]]), sub(points[tri[2]], points[tri[0]]));
		const length = Math.hypot(...n);
		if (length < 1e-6) continue;
		n = n.map(x => x / length);
		const d = dot(n, points[tri[0]]);
		const same = planes.find(q => dot(q.n, n) > 0.99985 && Math.abs(q.d - d) < HULL_EPS);
		if (same) same.tris.push(...tri);
		else planes.push({n, d, tris: [...tri]});
	}
	return planes.map(q => q.tris);
}

/** ConvexHull.of: the first refusal, or []. */
function hullProblems(points, loops) {
	if (points.length < 4) return [`needs at least 4 corners, has ${points.length}`];
	if (loops.length < 4) return [`needs at least 4 faces, has ${loops.length}`];
	for (let index = 0; index < loops.length; index++) {
		const loop = loops[index];
		let n = [0, 0, 0];
		for (let k = 0; k < loop.length; k++) {
			const a = points[loop[k]];
			const b = points[loop[(k + 1) % loop.length]];
			n = [n[0] + (a[1] - b[1]) * (a[2] + b[2]), n[1] + (a[2] - b[2]) * (a[0] + b[0]), n[2] + (a[0] - b[0]) * (a[1] + b[1])];
		}
		const length = Math.hypot(...n);
		if (length < HULL_EPS) return [`face ${index} has no area`];
		n = n.map(x => x / length);
		const d = dot(n, points[loop[0]]);
		if (Math.max(...loop.map(v => Math.abs(dot(n, points[v]) - d))) > HULL_EPS) {
			return [`face ${index} is bent: its corners are not on one plane`];
		}
		const outside = Math.max(...points.map(p => dot(n, p) - d));
		if (outside > HULL_EPS) {
			return [`is not convex (a corner sits ${outside.toFixed(4)} outside face ${index}): split it into convex pieces`];
		}
	}
	return [];
}

// ---------------------------------------------------------------- import plan

const MAP_SLOTS = [['pbrMetallicRoughness', 'baseColorTexture'], ['pbrMetallicRoughness', 'metallicRoughnessTexture'],
	[null, 'normalTexture'], [null, 'occlusionTexture'], [null, 'emissiveTexture']];

/** Material JSON with texture references as {location | uri, ...}: survives the trip through Blockbench. */
function materialSource(gltf, material) {
	const copy = JSON.parse(JSON.stringify(material));
	for (const [outer, key] of MAP_SLOTS) {
		const holder = outer ? copy[outer] : copy;
		if (!holder || !holder[key]) continue;
		const image = gltf.images[gltf.textures[holder[key].index].source];
		delete holder[key].index;
		holder[key].location = image.extras && image.extras.resourceLocation || null;
		if (!holder[key].location) holder[key].uri = image.uri;
	}
	return copy;
}

/** three.js Euler.setFromRotationMatrix for the orders Blockbench formats use; degrees. */
function quatToEuler(q, order) {
	const [x, y, z, w] = q;
	const m11 = 1 - 2 * (y * y + z * z), m12 = 2 * (x * y - z * w), m13 = 2 * (x * z + y * w);
	const m21 = 2 * (x * y + z * w), m22 = 1 - 2 * (x * x + z * z), m23 = 2 * (y * z - x * w);
	const m31 = 2 * (x * z - y * w), m32 = 2 * (y * z + x * w), m33 = 1 - 2 * (x * x + y * y);
	const clamp = v => Math.max(-1, Math.min(1, v));
	let e;
	// atan2 for the middle angle: asin(m31) loses ~1e-4 rad near +-90 deg from float32 input.
	if (order === 'ZYX') {
		const ey = Math.atan2(-clamp(m31), Math.hypot(m11, m21));
		e = Math.abs(m31) < 0.9999999 ? [Math.atan2(m32, m33), ey, Math.atan2(m21, m11)] : [0, ey, Math.atan2(-m12, m22)];
	} else if (order === 'XYZ') {
		const ey = Math.atan2(clamp(m13), Math.hypot(m11, m12));
		e = Math.abs(m13) < 0.9999999 ? [Math.atan2(-m23, m33), ey, Math.atan2(-m12, m11)] : [Math.atan2(m32, m22), ey, 0];
	} else {
		throw new Error(`euler order ${order} not supported`);
	}
	return e.map(v => v * 180 / Math.PI);
}

/** Column-major glTF matrix -> {t, q, s}; negative determinant folded into s[0]. */
function decompose(m) {
	const col = j => [m[j * 4], m[j * 4 + 1], m[j * 4 + 2]];
	const s = [0, 1, 2].map(j => Math.hypot(...col(j)));
	const det = dot(col(0), cross(col(1), col(2)));
	if (det < 0) s[0] = -s[0];
	const r = [0, 1, 2].map(j => col(j).map(v => v / s[j]));
	const [m11, m21, m31] = r[0];
	const [m12, m22, m32] = r[1];
	const [m13, m23, m33] = r[2];
	const trace = m11 + m22 + m33;
	let q;
	if (trace > 0) {
		const k = 0.5 / Math.sqrt(trace + 1);
		q = [(m32 - m23) * k, (m13 - m31) * k, (m21 - m12) * k, 0.25 / k];
	} else if (m11 > m22 && m11 > m33) {
		const k = 2 * Math.sqrt(1 + m11 - m22 - m33);
		q = [0.25 * k, (m12 + m21) / k, (m13 + m31) / k, (m32 - m23) / k];
	} else if (m22 > m33) {
		const k = 2 * Math.sqrt(1 + m22 - m11 - m33);
		q = [(m12 + m21) / k, 0.25 * k, (m23 + m32) / k, (m13 - m31) / k];
	} else {
		const k = 2 * Math.sqrt(1 + m33 - m11 - m22);
		q = [(m13 + m31) / k, (m23 + m32) / k, 0.25 * k, (m21 - m12) / k];
	}
	return {t: [m[12], m[13], m[14]], q, s};
}

function trs(node) {
	if (node.matrix) return decompose(node.matrix);
	return {t: node.translation || [0, 0, 0], q: node.rotation || [0, 0, 0, 1], s: node.scale || [1, 1, 1]};
}

const isOne = s => s.every(v => Math.abs(v - 1) < 1e-9);

function rotate(q, v) {
	const [x, y, z, w] = q;
	const t = cross([x, y, z], v).map(c => 2 * c);
	const u = cross([x, y, z], t);
	return [v[0] + w * t[0] + u[0], v[1] + w * t[1] + u[1], v[2] + w * t[2] + u[2]];
}
const mul3 = (a, b) => [a[0] * b[0], a[1] * b[1], a[2] * b[2]];

/** Each angle moved by whole turns to sit nearest `near`. */
function unwrap(angles, near) {
	return angles.map((v, j) => v - 360 * Math.round((v - near[j]) / 360));
}

/**
 * Pack glTF -> Blockbench build list. Px = blocks * 16; origins absolute (Blockbench group origin semantics).
 * Groups have no scale: a node scale moves into child translations, mesh vertices and locator scale.
 * orders: Euler order of {frame: groups + locators, mesh: Mesh elements}.
 */
function planImport(gltf, buffers, orders) {
	const warnings = [];
	const nodes = gltf.nodes || [];
	const entries = [];
	const materials = new Map();
	const animated = new Set();
	for (const clip of gltf.animations || []) for (const c of clip.channels) animated.add(c.target.node);
	let roots;
	if (gltf.scenes && gltf.scenes.length) {
		roots = gltf.scenes[gltf.scene || 0].nodes || [];
	} else {
		const child = new Set(nodes.flatMap(n => n.children || []));
		roots = nodes.map((_, i) => i).filter(i => !child.has(i));
	}
	const entryOfNode = new Map();
	const restOfNode = new Map();

	const material = index => {
		if (index === undefined) return null;
		if (!materials.has(index)) {
			const m = gltf.materials[index];
			const pbr = m.pbrMetallicRoughness || {};
			const ref = pbr.baseColorTexture;
			const image = ref ? gltf.images[gltf.textures[ref.index].source] : null;
			const location = image && image.extras && image.extras.resourceLocation;
			const name = m.name || `material ${index}`;
			if (ref && ref.texCoord) warnings.push(`material ${name}: uses UV map ${ref.texCoord}; read as the first`);
			if (ref && !location && !(image && image.uri)) warnings.push(`material ${name}: image has no file`);
			materials.set(index, {
				index, key: `m${index}`, location: location || null, uri: image && !location ? image.uri : null,
				kept: {name: m.name, alphaMode: m.alphaMode || 'OPAQUE', alphaCutoff: m.alphaCutoff === undefined ? 0.5 : m.alphaCutoff,
					doubleSided: !!m.doubleSided, source: materialSource(gltf, m)},
			});
		}
		return index;
	};

	/** bake: {pending, q, s}: vertices -> pending * R(q) * s (node rotation dropped); else vertices * scale. */
	const geometry = (meshIndex, scale, collider, label, bake) => {
		const place = bake ? v => mul3(bake.pending, rotate(bake.q, mul3(bake.s, v))) : v => mul3(scale, v);
		const placeNormal = bake
			? n => mul3(bake.pending.map(x => 1 / x), rotate(bake.q, mul3(bake.s.map(x => 1 / x), n)))
			: n => n.map((x, j) => x / scale[j]);
		const det = bake ? [...bake.pending, ...bake.s].reduce((a, b) => a * b, 1) : scale[0] * scale[1] * scale[2];
		const prims = gltf.meshes[meshIndex].primitives;
		const points = [];
		const triangles = [];
		for (const p of prims) {
			if (p.mode !== undefined && p.mode !== 4) throw new Error(`${label}: primitive mode ${p.mode} (only triangles)`);
			if (p.attributes.POSITION === undefined) continue;
			const base = points.length;
			for (const v of readAccessor(gltf, buffers, p.attributes.POSITION)) points.push(place(v));
			const uv = p.attributes.TEXCOORD_0 !== undefined ? readAccessor(gltf, buffers, p.attributes.TEXCOORD_0) : null;
			const normal = p.attributes.NORMAL !== undefined && !collider
				? readAccessor(gltf, buffers, p.attributes.NORMAL).map(n => {
					const v = placeNormal(n);
					const length = Math.hypot(...v) || 1;
					return v.map(x => x / length);
				}) : null;
			const count = points.length - base;
			const indices = p.indices !== undefined ? readAccessor(gltf, buffers, p.indices).map(r => r[0])
				: [...Array(count).keys()];
			const tris = [];
			for (let k = 0; k + 2 < indices.length; k += 3) tris.push([indices[k], indices[k + 1], indices[k + 2]]);
			// Baked mirror flips the winding back.
			if (det < 0) tris.forEach(t => t.reverse());
			triangles.push({tris: tris.map(t => t.map(i => base + i)), uv: uv && tris.map(t => t.map(i => uv[i])),
				normals: normal && tris.map(t => t.map(i => normal[i])),
				material: collider ? null : material(p.material)});
		}
		const welded = weld(points, collider ? HULL_EPS : 0);
		const px = welded.points.map(p => p.map(v => v * PX));
		const faces = [];
		if (collider) {
			let groups = triangles.map(t => t.tris.flat().map(i => welded.index[i]));
			if (groups.length === 1) groups = planeGroups(welded.points, groups[0]);
			polygonLoops(welded.points, groups).forEach((loop, tag) => {
				if (loop.length <= 4) faces.push({vertices: loop, tag});
				else for (let k = 1; k + 1 < loop.length; k++) faces.push({vertices: [loop[0], loop[k], loop[k + 1]], tag});
			});
		} else {
			for (const t of triangles) {
				t.tris.forEach((tri, k) => {
					const vertices = tri.map(i => welded.index[i]);
					if (new Set(vertices).size < 3) return;
					faces.push({vertices, uv: t.uv ? t.uv[k] : null, normals: t.normals ? t.normals[k] : null, material: t.material});
				});
			}
		}
		return {points: px, faces};
	};

	const visit = (i, parent, parentOrigin, pending) => {
		const node = nodes[i];
		const name = node.name || `node_${i}`;
		const {t, q, s} = trs(node);
		const local = mul3(pending, t).map(v => v * PX);
		const origin = [0, 1, 2].map(j => parentOrigin[j] + local[j]);
		const scale = mul3(pending, s);
		const uneven = v => Math.abs(v[0] - v[1]) > 1e-9 || Math.abs(v[1] - v[2]) > 1e-9;
		const rotated = q.some((v, j) => Math.abs(v - [0, 0, 0, 1][j]) > 1e-9);
		for (const key of ['camera', 'skin', 'weights', 'extensions']) if (node[key]) warnings.push(`${name}: ${key} not kept`);
		const props = splitExtras(node.extras);
		const children = node.children || [];
		const hasMesh = node.mesh !== undefined;
		const frame = FRAMES.includes(props.role);
		const kind = children.length || frame || animated.has(i) ? 'group' : hasMesh ? 'mesh' : 'locator';
		// Uneven parent scale over a rotation is a shear: a mesh leaf takes the whole transform into its vertices.
		const bake = kind === 'mesh' && uneven(pending) && rotated ? {pending, q, s} : null;
		if (kind !== 'mesh' && uneven(pending) && rotated) warnings.push(`${name}: uneven parent scale over a rotation; approximated`);
		const euler = bake ? [0, 0, 0] : quatToEuler(q, kind === 'mesh' ? orders.mesh : orders.frame);
		const entry = {kind, name, parent, node: i, origin, local, rotation: euler, extras: node.extras || null, props};
		const index = entries.push(entry) - 1;
		entryOfNode.set(i, index);
		restOfNode.set(i, {local, rotation: euler, pending});
		if (kind === 'mesh') entry.mesh = geometry(node.mesh, scale, props.role === 'collider', name, bake);
		if (kind === 'locator' && !isOne(scale)) entry.scale = scale;
		if (kind === 'group' && hasMesh) {
			const own = {kind: 'mesh', name: `${name}_mesh`, parent: index, node: i, origin, local: [0, 0, 0],
				rotation: [0, 0, 0], extras: null, props: {role: 'none'}};
			if (!frame && props.role !== 'none') {
				warnings.push(`${name}: ${props.role} with child nodes; the role moves to its mesh ${name}_mesh`);
				Object.assign(own, {extras: entry.extras, props});
				Object.assign(entry, {extras: null, props: {role: 'none'}});
			}
			own.mesh = geometry(node.mesh, scale, own.props.role === 'collider', name);
			entries.push(own);
		}
		for (const c of children) visit(c, index, origin, kind === 'group' ? scale : [1, 1, 1]);
	};
	for (const r of roots) visit(r, -1, [0, 0, 0], [1, 1, 1]);

	const clips = (gltf.animations || []).map((clip, a) => {
		let length = 0;
		const channels = [];
		for (const c of clip.channels) {
			const path = c.target.path;
			if (!['translation', 'rotation', 'scale'].includes(path) || !entryOfNode.has(c.target.node)) {
				warnings.push(`clip ${clip.name}: ${path} channel dropped`);
				continue;
			}
			const sampler = clip.samplers[c.sampler];
			const times = readAccessor(gltf, buffers, sampler.input).map(r => r[0]);
			let values = readAccessor(gltf, buffers, sampler.output);
			if (sampler.interpolation === 'CUBICSPLINE') {
				values = times.map((_, k) => values[k * 3 + 1]);
				warnings.push(`clip ${clip.name}: cubic spline keys imported as linear`);
			}
			const rest = restOfNode.get(c.target.node);
			let previous = [0, 0, 0];
			const keys = times.map((time, k) => {
				let value;
				if (path === 'translation') {
					value = mul3(rest.pending, values[k]).map((v, j) => v * PX - rest.local[j]);
				} else if (path === 'rotation') {
					value = unwrap(quatToEuler(values[k], orders.frame).map((v, j) => v - rest.rotation[j]), previous);
					previous = value;
				} else {
					value = values[k];
				}
				return {time, value};
			});
			length = Math.max(length, ...times);
			channels.push({entry: entryOfNode.get(c.target.node), channel: path === 'translation' ? 'position' : path, keys,
				interpolation: sampler.interpolation === 'STEP' ? 'step' : 'linear'});
		}
		return {name: clip.name || `animation_${a}`, length, channels};
	});
	return {entries, materials: [...materials.values()], clips, warnings};
}

// ---------------------------------------------------------------- authoring helpers (operators of the Blender add-on)

/** Mesh socket starters, px, Blockbench frame (nose +Z): era / slat 1 block quad facing the nose; lens 0.1 block quad
 * facing the eye; track = side-view loop, path = outline. */
const SOCKET_SHAPES = {
	era: {points: [[-8, -8, 0], [8, -8, 0], [8, 8, 0], [-8, 8, 0]], loops: [[0, 1, 2, 3]]},
	slat: {points: [[-8, -8, 0], [8, -8, 0], [8, 8, 0], [-8, 8, 0]], loops: [[0, 1, 2, 3]]},
	sight_lens: {points: [[0.8, -0.8, 0], [-0.8, -0.8, 0], [-0.8, 0.8, 0], [0.8, 0.8, 0]], loops: [[0, 1, 2, 3]]},
	track: {points: [[0, 3.2, 32], [0, 9.6, 36.8], [0, 16, 32], [0, 16, -32], [0, 9.6, -36.8], [0, 3.2, -32]], loops: [[0, 1, 2, 3, 4, 5]]},
};

/** Node scale a scale-valued socket stores for `value` (glTF axes): length on Z, radius / size on all. */
function scaleForValue(field, value) {
	if (['bolt', 'eject', 'station'].includes(field)) return [1, 1, value];
	return [value, value, value];
}

/** Inverse of scaleForValue; 1 = unset. */
function valueOfScale(field, scale) {
	if (!scale) return 1;
	return ['bolt', 'eject', 'station'].includes(field) ? scale[2] : (scale[0] + scale[1] + scale[2]) / 3;
}

/** hull_into: convex hull of points (welded), coplanar triangles merged. {points, loops}; loops CCW from outside. */
function convexHull(input) {
	const welded = weld(input, HULL_EPS).points;
	const n = welded.length;
	const length = v => Math.hypot(v[0], v[1], v[2]);
	const normal = (a, b, c) => cross(sub(welded[b], welded[a]), sub(welded[c], welded[a]));
	if (n < 4) throw new Error('hull needs at least 4 distinct points');
	const i0 = welded.reduce((best, p, i) => p[0] < welded[best][0] ? i : best, 0);
	const far = (score, skip) => welded.reduce((best, p, i) => skip.includes(i) ? best : (score(p) > score(welded[best]) || skip.includes(best) ? i : best), skip.includes(0) ? 1 : 0);
	const i1 = far(p => length(sub(p, welded[i0])), [i0]);
	const i2 = far(p => length(cross(sub(welded[i1], welded[i0]), sub(p, welded[i0]))), [i0, i1]);
	const i3 = far(p => Math.abs(dot(normal(i0, i1, i2), sub(p, welded[i0]))), [i0, i1, i2]);
	if (Math.abs(dot(normal(i0, i1, i2), sub(welded[i3], welded[i0]))) < HULL_EPS * 1e-2) throw new Error('hull points are flat');
	const centre = [0, 1, 2].map(j => (welded[i0][j] + welded[i1][j] + welded[i2][j] + welded[i3][j]) / 4);
	const outward = (a, b, c) => dot(normal(a, b, c), sub(centre, welded[a])) > 0 ? [a, c, b] : [a, b, c];
	let faces = [outward(i0, i1, i2), outward(i0, i1, i3), outward(i0, i2, i3), outward(i1, i2, i3)];
	for (let p = 0; p < n; p++) {
		if ([i0, i1, i2, i3].includes(p)) continue;
		const visible = faces.filter(f => {
			const m = normal(f[0], f[1], f[2]);
			return dot(m, sub(welded[p], welded[f[0]])) / (length(m) || 1) > HULL_EPS;
		});
		if (!visible.length) continue;
		const edges = new Set(visible.flatMap(f => [[f[0], f[1]], [f[1], f[2]], [f[2], f[0]]].map(e => e.join('>'))));
		const kept = faces.filter(f => !visible.includes(f));
		for (const key of edges) {
			const [a, b] = key.split('>').map(Number);
			if (!edges.has(`${b}>${a}`)) kept.push([a, b, p]);
		}
		faces = kept;
	}
	const used = [...new Set(faces.flat())];
	const index = new Map(used.map((v, i) => [v, i]));
	const points = used.map(v => welded[v]);
	const tris = faces.flat().map(v => index.get(v));
	return {points, loops: polygonLoops(points, planeGroups(points, tris))};
}

/** gen_hulls `cylinder`: circumscribed prism round points, axis = thinnest extent. {points, loops}. */
function prismAround(input, sides) {
	const lo = [0, 1, 2].map(a => Math.min(...input.map(p => p[a])));
	const hi = [0, 1, 2].map(a => Math.max(...input.map(p => p[a])));
	const axis = [0, 1, 2].reduce((best, a) => hi[a] - lo[a] < hi[best] - lo[best] ? a : best, 0);
	const u = (axis + 1) % 3;
	const w = (axis + 2) % 3;
	const mid = [0, 1, 2].map(a => (lo[a] + hi[a]) / 2);
	const radius = Math.max(...input.map(p => Math.hypot(p[u] - mid[u], p[w] - mid[w]))) / Math.cos(Math.PI / sides);
	const points = [];
	for (const end of [lo[axis], hi[axis]]) {
		for (let k = 0; k < sides; k++) {
			const v = [0, 0, 0];
			v[axis] = end;
			v[u] = mid[u] + radius * Math.cos(2 * Math.PI * k / sides);
			v[w] = mid[w] + radius * Math.sin(2 * Math.PI * k / sides);
			points.push(v);
		}
	}
	const ring = [...Array(sides).keys()];
	const loops = [ring.slice().reverse(), ring.map(k => sides + k)]
		.concat(ring.map(k => [k, (k + 1) % sides, sides + (k + 1) % sides, sides + k]));
	return {points, loops};
}

/** Group id per polygon (arrays of 3D points): polygons on one plane share an id. */
function coplanarGroups(polygons) {
	const planes = [];
	return polygons.map(poly => {
		let n = [0, 0, 0];
		for (let k = 0; k < poly.length; k++) {
			const a = poly[k];
			const b = poly[(k + 1) % poly.length];
			n = [n[0] + (a[1] - b[1]) * (a[2] + b[2]), n[1] + (a[2] - b[2]) * (a[0] + b[0]), n[2] + (a[0] - b[0]) * (a[1] + b[1])];
		}
		const length = Math.hypot(...n);
		if (length < 1e-9) return -1;
		n = n.map(x => x / length);
		const d = dot(n, poly[0]);
		let id = planes.findIndex(q => dot(q.n, n) > 0.99985 && Math.abs(q.d - d) < HULL_EPS);
		if (id < 0) id = planes.push({n, d}) - 1;
		return id;
	});
}

const ARMOR = /armou?r[_ ]?(\d+(?:\.\d+)?)/i;
const DECOR = /(?:^|[_ .])decor(?:$|[_ .])/i;
const SURFACE_WORDS = {rubber: 'rubber', glass: 'glass', soft: 'soft|fabric|cloth|canvas'};
const SURFACE = Object.fromEntries(Object.entries(SURFACE_WORDS)
	.map(([key, words]) => [key, new RegExp(`(?:^|[_ .])(?:${words})(?:$|[_ .])`, 'gi')]));

function stripTags(name) {
	let out = name.replace(new RegExp(ARMOR.source, 'gi'), '');
	for (const pattern of [new RegExp(DECOR.source, 'gi'), ...Object.values(SURFACE)]) {
		out = out.replace(pattern, '_').replace(pattern, '_');
	}
	return out.replace(/_+/g, '_').replace(/^[_ .]+|[_ .]+$/g, '') || 'material';
}

/** SurfaceMaterial.of: first matching token in declaration order; steel when none. */
function surfaceOf(name) {
	return Object.keys(SURFACE).find(key => new RegExp(SURFACE[key].source, 'i').test(name)) || 'steel';
}

/** `<stem>_decor` or `<stem>[_<surface>][_armor_<mm>]`: the tokens the game reads from a material name. */
function taggedName(name, armor, surface, decor) {
	let stem = stripTags(name);
	if (decor) return stem + '_decor';
	if (surface !== 'steel') stem += '_' + surface;
	return stem + (armor > 0 ? `_armor_${armor}` : '');
}

/** {armor mm, surface, decor} as the game reads the name. */
function materialTags(name) {
	const armor = name.match(ARMOR);
	return {armor: armor ? parseFloat(armor[1]) : 0, surface: surfaceOf(name), decor: DECOR.test(name)};
}

const VIEW_FIELDS = ['seat', 'view', 'sight', 'camera', 'camera_zoomed'];

/** Preview camera at a socket: eye = origin, looks along `forward` (unit); px. */
function viewAngle(eye, forward) {
	return {position: eye.slice(), target: eye.map((v, i) => v + forward[i] * PX), projection: 'perspective'};
}

const api = {
	VIEW_FIELDS, viewAngle,
	SYSTEMS, SOCKET_FIELDS, MESH_FIELDS, UNTARGETED, ROLES, DEFAULTS, CLIP_NAME,
	SOCKET_SHAPES, scaleForValue, valueOfScale, convexHull, prismAround, coplanarGroups, stripTags, surfaceOf, taggedName, materialTags,
	lightMeta, readContainer, writeGlb, nodeExtras, splitExtras, mergeExtras, quantiseCutoff, alphaModeOf, packPaths, resourceLocationOf, finishGltf, prune,
	weld, polygonLoops, planeGroups, hullProblems, materialSource, openEdges, readAccessor, repack, quatToEuler, decompose, planImport,
};
if (typeof Plugin === 'undefined') {
	module.exports = api;
	return;
}

// ---------------------------------------------------------------- Blockbench

const PROP = 'combinedassault';
const EXTRAS_PROP = 'combinedassault_extras';
const SCALE_PROP = 'combinedassault_scale';
const FACE_TAG = 'combinedassault_face';
const FACE_MATERIAL = 'combinedassault_material';
const FACE_NORMALS = 'combinedassault_normals';
const NODE_CLASSES = [Group, Cube, Mesh, Locator];
const fsModule = requireNativeModule;
let properties = [];
let actions = [];
let menu;
let panel;
let selectionListener;

function caOf(node) {
	return settingsOf(node[PROP]);
}

function roleOf(node) {
	return node && node[PROP] ? node[PROP].role || 'none' : 'none';
}

function kindOf(node) {
	if (node instanceof Group) return 'group';
	if (node instanceof Cube) return 'cube';
	if (node instanceof Mesh) return 'mesh';
	if (node instanceof Locator) return 'locator';
	return node.type;
}

function exported(node) {
	for (let n = node; n instanceof OutlinerNode; n = n.parent) {
		if (n.export === false) return false;
	}
	return true;
}

function allNodes() {
	return [...Group.all, ...Outliner.elements].filter(exported);
}

function frameOf(node) {
	let n = node;
	while (n instanceof OutlinerNode && !FRAMES.includes(roleOf(n))) n = n.parent;
	return n instanceof OutlinerNode ? n : null;
}

function underBody(node) {
	for (let n = node; n instanceof OutlinerNode; n = n.parent) {
		if (roleOf(n) === 'body') return true;
	}
	return false;
}

/** Mesh corners in blocks, faces as corner indices in winding order. */
function shapeOf(mesh) {
	const keys = Object.keys(mesh.vertices);
	const index = new Map(keys.map((k, i) => [k, i]));
	const points = keys.map(k => mesh.vertices[k].map(v => v / PX));
	const faces = Object.values(mesh.faces)
		.filter(f => f.vertices.length >= 3)
		.map(f => f.getSortedVertices().map(k => index.get(k)));
	return {points, faces};
}

/** Collider faces as the exporter writes them: faces sharing a face tag form one polygon. */
function colliderPolygon(mesh) {
	const used = [...new Set(Object.values(mesh.faces).flatMap(f => f.vertices))];
	const index = new Map(used.map((k, i) => [k, i]));
	const points = used.map(k => mesh.vertices[k].map(v => v / PX));
	const groups = new Map();
	let solo = 0;
	for (const face of Object.values(mesh.faces)) {
		if (face.vertices.length < 3) continue;
		const order = face.vertices.length === 4 ? face.getSortedVertices() : face.vertices;
		const tris = [];
		for (let k = 1; k + 1 < order.length; k++) tris.push(order[0], order[k], order[k + 1]);
		const key = face[FACE_TAG] >= 0 ? `t${face[FACE_TAG]}` : `s${solo++}`;
		if (!groups.has(key)) groups.set(key, []);
		groups.get(key).push(...tris.map(k => index.get(k)));
	}
	return {points, loops: polygonLoops(points, [...groups.values()])};
}

function faceTextures(element) {
	return Object.values(element.faces).map(f => f.getTexture());
}

function colliderProblems(element) {
	if (!(element instanceof Cube || element instanceof Mesh)) return ['must be a cube or a mesh'];
	const problems = [];
	if (element instanceof Cube) {
		if (element.from.some((v, i) => element.to[i] - v + 2 * element.inflate <= 0)) {
			problems.push('is flat: a box needs size in all three directions');
		}
		if (Object.values(element.faces).some(f => f.texture === null)) {
			problems.push('has a hidden (transparent) face: every face must be present');
		}
	} else {
		const {points, loops} = colliderPolygon(element);
		problems.push(...hullProblems(points, loops));
		return problems;
	}
	const textures = new Set(faceTextures(element).filter(t => t !== null).map(t => (t && t.uuid) || String(t)));
	if (textures.size > 1) problems.push('faces use more than one texture: give every face the same one (or none)');
	return problems;
}

/** check_object(): [errors, warnings] for one exported node. rig: the model has a body frame. */
function checkNode(node, rig) {
	const errors = [];
	const warnings = [];
	const s = caOf(node);
	const role = s.role;
	const kind = kindOf(node);
	if (rig && role !== 'none' && role !== 'body' && !underBody(node)) {
		errors.push(`is a ${role} but not inside the body group`);
	}
	if (FRAMES.includes(role) && kind !== 'group') errors.push(`${role} frame must be a group (folder), not a ${kind}`);
	if (role === 'body' && kind === 'group'
		&& (node.parent instanceof Group || node.origin.some(v => v !== 0) || node.rotation.some(v => v !== 0))) {
		errors.push('body group must be top level, pivot at 0 0 0, no rotation');
	}
	if ((role === 'part' || role === 'pitch') && !s.interface) errors.push(`${role} frame names no part id (interface)`);
	if (role === 'collider') {
		errors.push(...colliderProblems(node));
		const frame = frameOf(node.parent);
		if (s.main && roleOf(frame) !== 'body') errors.push('main collider rides a part frame: put it straight in the body');
		return [errors, warnings];
	}
	if (SHAPES.includes(role) && !(kind === 'cube' || kind === 'mesh')) errors.push(`${role} must be a cube or a mesh`);
	if (role === 'hit_surface' && (!(node.parent instanceof Group) || roleOf(node.parent) === 'body')) {
		warnings.push('hit surface replaces its parent\'s whole subtree; directly in the body that is the whole vehicle');
	}
	if (role === 'hit' && kind === 'mesh' && openEdges(shapeOf(node).faces).length) {
		warnings.push('hit module is open: a shot can enter without crossing it');
	}
	if (role === 'socket') {
		if (UNTARGETED.includes(s.field) && s.interface) errors.push(`socket ${s.field} takes no interface`);
		// Part mount model (no body): a part socket without interface targets the carrying part.
		if (!UNTARGETED.includes(s.field) && !s.interface && (rig || ID_TARGETED.includes(s.field))) {
			errors.push(`socket ${s.field} names no ${ID_TARGETED.includes(s.field) ? 'id' : 'part'} (interface)`);
		}
		if (kind === 'mesh' && !MESH_FIELDS.includes(s.field)) warnings.push('socket is a mesh; only era/slat/track/sight_lens use the geometry');
		if (kind !== 'mesh' && MESH_FIELDS.includes(s.field)) {
			errors.push(`${s.field} socket must be a mesh (${{track: 'outline = path', sight_lens: 'lens mesh'}[s.field] || 'one quad'})`);
		}
		if (kind === 'cube') errors.push('socket must be a locator, a group or (era/slat/track/sight_lens) a mesh');
		if (SCALED_FIELDS[s.field] && !(node[SCALE_PROP] && node[SCALE_PROP].some(v => v !== 1))) {
			warnings.push(`${s.field} reads its size (${SCALED_FIELDS[s.field]}) from node scale: set Value in the panel (now 1)`);
		}
	}
	if (role === 'none' && kind === 'locator') warnings.push('locator has no role: it exports as an empty node');
	const drawn = (kind === 'cube' || kind === 'mesh') && !SHAPES.includes(role) && role !== 'socket';
	if (drawn) {
		const missing = Object.values(node.faces).filter(f => f.texture !== null && !f.getTexture()).length;
		if (missing) warnings.push(`has ${missing} face(s) with no texture: drawn in a flat colour`);
	}
	return [errors, warnings];
}

function usedTextures() {
	const used = new Set();
	for (const node of allNodes()) {
		if (node.faces) faceTextures(node).forEach(t => t instanceof Texture && used.add(t));
	}
	return [...used];
}

/** check_scene(): [errors, warnings], each line prefixed with the node name. */
function checkProject() {
	const errors = [];
	const warnings = [];
	const nodes = allNodes();
	const mains = nodes.filter(n => roleOf(n) === 'collider' && caOf(n).main);
	if (mains.length > 1) errors.push(`more than one collider is Main: ${mains.map(n => n.name).join(', ')}`);
	const bodies = nodes.filter(n => roleOf(n) === 'body');
	if (bodies.length > 1) errors.push(`needs at most one body group, has ${bodies.length}`);
	if (!bodies.length) warnings.push('no body group: exports as a part / display model (gun, turret mount, decoration)');
	const seen = new Map();
	for (const node of nodes) {
		const role = roleOf(node);
		if (role === 'part' || role === 'pitch') {
			const key = `${role} ${caOf(node).interface}`;
			if (seen.has(key)) errors.push(`${key} on both ${seen.get(key)} and ${node.name}`);
			seen.set(key, node.name);
		}
	}
	const frameNames = new Map();
	for (const node of nodes) {
		if (!(node instanceof Group)) continue;
		if (frameNames.has(node.name)) warnings.push(`two groups are named ${node.name}: scripts and controllers find bones by name`);
		frameNames.set(node.name, true);
	}
	for (const node of nodes) {
		const [e, w] = checkNode(node, bodies.length > 0);
		errors.push(...e.map(m => `${node.name}: ${m}`));
		warnings.push(...w.map(m => `${node.name}: ${m}`));
	}
	const names = new Map();
	for (const texture of usedTextures()) {
		const label = `texture ${texture.name}`;
		if (names.has(texture.name)) errors.push(`${label}: two textures share this name; rename one`);
		names.set(texture.name, true);
		if ((texture.file_format && texture.file_format !== 'png') || (texture.path && !/\.png$/i.test(texture.path))) {
			errors.push(`${label}: not a PNG; GemRender reads PNG only`);
		}
		const fileName = texture.path && /\/assets\/[^/]+\/textures\//.test(slashes(texture.path))
			? slashes(texture.path).split('/').pop() : texture.name;
		if (!RESOURCE_PATH.test(fileName)) {
			errors.push(`${label}: file name must be lowercase a-z 0-9 _ - . (Minecraft resource name)`);
		}
		if (texture.path && !texture.internal && !texture.saved) {
			errors.push(`${label}: has unsaved changes; save it first (right-click the texture > Save)`);
		}
		if (texture.frameCount > 1) warnings.push(`${label}: animated textures are not supported; frame 0 only`);
	}
	for (const animation of Animator.animations) {
		if (!CLIP_NAME.test(animation.name)) {
			warnings.push(`animation ${animation.name}: not <part id>[.<suffix>]; the game never plays it`);
		}
	}
	return [errors, warnings];
}

function escapeHtml(text) {
	return String(text).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
}

function showReport(title, errors, warnings, footer) {
	const lines = [];
	if (!errors.length && !warnings.length) lines.push('<p>No problems found.</p>');
	if (errors.length) {
		lines.push(`<h3>Must fix (${errors.length})</h3><ul>${errors.map(m => `<li>${escapeHtml(m)}</li>`).join('')}</ul>`);
	}
	if (warnings.length) {
		lines.push(`<h3>Check (${warnings.length})</h3><ul>${warnings.map(m => `<li>${escapeHtml(m)}</li>`).join('')}</ul>`);
	}
	if (footer) lines.push(footer);
	new Dialog({id: 'ca_report', title, width: 640, lines, singleButton: true}).show();
}

// ---------------------------------------------------------------- export

function textureInfo(texture, packRoot, fs) {
	const ctx = texture.canvas.getContext('2d');
	const rgba = ctx.getImageData(0, 0, texture.canvas.width, texture.canvas.height).data;
	const file = texture.path && !texture.internal && texture.saved
		&& resourceLocationOf(packRoot, texture.path) && fs.existsSync(texture.path) ? texture.path : null;
	return {
		file,
		data: file ? null : texture.canvas.toDataURL('image/png').replace(/^data:image\/png;base64,/, ''),
		alpha: alphaModeOf(rgba),
		doubleSided: Canvas.getRenderSide(texture) === THREE.DoubleSide,
	};
}

function flatNormal(points) {
	let n = [0, 0, 0];
	for (let k = 0; k < points.length; k++) {
		const a = points[k];
		const b = points[(k + 1) % points.length];
		n = [n[0] + (a[1] - b[1]) * (a[2] + b[2]), n[1] + (a[2] - b[2]) * (a[0] + b[0]), n[2] + (a[0] - b[0]) * (a[1] + b[1])];
	}
	const length = Math.hypot(...n) || 1;
	return n.map(v => v / length);
}

/** Mesh element -> primitives per (texture, material); imported corner normals kept while the face is unmoved. */
function meshGroups(mesh) {
	const groups = new Map();
	const table = Project.ca_materials || {};
	for (const face of Object.values(mesh.faces)) {
		if (face.texture === null || face.vertices.length < 3) continue;
		const texture = face.getTexture();
		const name = texture instanceof Texture ? texture.name : null;
		const kept = face[FACE_MATERIAL] && table[face[FACE_MATERIAL]] || null;
		const slotted = !!(kept && kept.source && kept.source.extras && kept.source.extras.textureSlot);
		const key = `${name}|${face[FACE_MATERIAL] || ''}`;
		if (!groups.has(key)) groups.set(key, {texture: name, material: kept, positions: [], normals: [], uvs: [], indices: []});
		const g = groups.get(key);
		const order = face.vertices.length === 4 ? face.getSortedVertices() : face.vertices;
		const flat = flatNormal(order.map(k => mesh.vertices[k]));
		const stored = face[FACE_NORMALS];
		const keep = stored && stored.corners && stored.flat && dot(stored.flat, flat) > 0.99999;
		const first = g.positions.length / 3;
		for (const k of order) {
			g.positions.push(...mesh.vertices[k].map(v => v / PX));
			g.normals.push(...(keep && stored.corners[k] || flat));
			const uv = face.uv[k] || [0, 0];
			if (texture instanceof Texture) g.uvs.push(uv[0] / texture.getUVWidth(), uv[1] / texture.getUVHeight());
			else if (slotted) g.uvs.push(uv[0], uv[1]);
		}
		for (let k = 1; k + 1 < order.length; k++) g.indices.push(first, first + k, first + k + 1);
	}
	return [...groups.values()];
}

/** {gltf, polygons, meshes}: node extras via userData (GLTFExporter writes it as extras). */
async function compileTagged() {
	const restore = [];
	const polygons = {};
	const meshes = {};
	try {
		for (const node of [...Group.all, ...Outliner.elements]) {
			const object = node.scene_object || node.mesh;
			if (!object) continue;
			const extras = mergeExtras(node[EXTRAS_PROP], node[PROP]);
			if (node instanceof Mesh && exported(node)) {
				extras[UUID] = node.uuid;
				if (roleOf(node) === 'collider') polygons[node.uuid] = colliderPolygon(node);
				else meshes[node.uuid] = meshGroups(node);
			}
			if (Object.keys(extras).length) {
				restore.push(() => { object.userData = {}; });
				object.userData = extras;
			}
			const scale = node[SCALE_PROP];
			if (node instanceof Locator && scale && scale.some(v => v !== 1)) {
				restore.push(() => object.scale.set(1, 1, 1));
				object.scale.fromArray(scale);
			}
			if (object.sprite) {
				const sprite = object.sprite;
				restore.push(() => { sprite.no_export = false; });
				sprite.no_export = true;
			}
		}
		const text = await Codecs.gltf.compile({encoding: 'ascii', scale: PX, embed_textures: false, armature: false,
			animations: true});
		return {gltf: JSON.parse(text), polygons, meshes};
	} finally {
		restore.forEach(f => f());
	}
}

function exportDialog() {
	const [errors, warnings] = checkProject();
	if (errors.length) {
		showReport('CombinedAssault: fix before export', errors, warnings, '<p>Nothing was written.</p>');
		return;
	}
	new Dialog({
		id: 'ca_export',
		title: 'Export to CombinedAssault pack',
		width: 600,
		form: {
			pack_root: {label: 'Pack folder', type: 'folder', value: Project.ca_pack_root,
				description: 'The folder holding pack.mcmeta and assets/'},
			namespace: {label: 'Namespace', type: 'text', value: Project.ca_namespace || 'combinedassault'},
			model_path: {label: 'Model path', type: 'text', value: Project.ca_model_path
				|| 'ground/' + (Project.name || 'untitled').toLowerCase().replace(/[^a-z0-9_.-]+/g, '_'),
				description: 'Under assets/<namespace>/models/, without .glb'},
			info: {type: 'info', text: '1 block = 16 px. Nose toward south (+Z), left side toward east (+X).'},
		},
		onConfirm(result) {
			const packRoot = slashes(result.pack_root || '');
			const ns = (result.namespace || '').trim();
			const modelPath = cleanModelPath(result.model_path || '');
			const problems = [];
			if (!packRoot) problems.push('choose the pack folder');
			if (!NAMESPACE.test(ns)) problems.push('namespace: lowercase a-z 0-9 _ - . only');
			if (!modelPath || !RESOURCE_PATH.test(modelPath)) problems.push('model path: lowercase a-z 0-9 _ - . / only');
			if (problems.length) {
				Blockbench.showMessageBox({title: 'Export', message: problems.join('\n')});
				return false;
			}
			Project.ca_pack_root = packRoot;
			Project.ca_namespace = ns;
			Project.ca_model_path = modelPath;
			Project.saved = false;
			writePack(packRoot, ns, modelPath, warnings).catch(err => {
				console.error(err);
				Blockbench.showMessageBox({title: 'Export failed', message: String(err && err.message || err)});
			});
		},
	}).show();
}

async function writePack(packRoot, ns, modelPath, warnings) {
	const fs = fsModule('fs', {scope: packRoot, message: 'Write the model and textures into the pack folder'});
	if (!fs) throw new Error('No permission to write into the pack folder');
	if (!fs.existsSync(packRoot + '/pack.mcmeta')) warnings.push(`${packRoot} has no pack.mcmeta: is it the pack folder?`);
	const textures = {};
	for (const texture of usedTextures()) textures[texture.name] = textureInfo(texture, packRoot, fs);
	const {gltf, polygons, meshes} = await compileTagged();
	const out = finishGltf(gltf, {packRoot, namespace: ns, modelPath, textures, polygons, meshes});
	const mkdir = path => fs.mkdirSync(path.slice(0, path.lastIndexOf('/')), {recursive: true});
	mkdir(out.paths.glb);
	for (const w of out.writes) {
		mkdir(w.path);
		fs.writeFileSync(w.path, w.data);
	}
	fs.writeFileSync(out.paths.glb, writeGlb(out.gltf, out.bin));
	const rows = Object.keys(out.locations).map(name => {
		const written = out.writes.some(w => w.path.endsWith('/' + pngName(name)));
		return `<li>${escapeHtml(name)} -> ${escapeHtml(out.locations[name])} (${written ? 'written' : 'in place'})</li>`;
	}).join('');
	showReport('CombinedAssault: exported', [], warnings,
		`<p>Wrote ${escapeHtml(out.paths.glb)}</p><p>Textures:</p><ul>${rows}</ul>`
		+ `<p>Vehicle json: <code>"model": "${escapeHtml(ns)}:${escapeHtml(modelPath)}"</code>. In game: /reload.</p>`);
}

// ---------------------------------------------------------------- role panel

const show = (...roles) => r => roles.includes(r.role);
const isLight = r => r.role === 'socket' && r.field === 'light';

function roleForm() {
	const systemOptions = Object.fromEntries(SYSTEMS.map(s => [s, s]));
	return {
		role: {label: 'Role', type: 'select', options: ROLES, value: 'none'},
		info_none: {type: 'info', condition: show('none'), text: 'Drawn and hit as the vehicle surface. Rides the nearest frame above it.'},
		info_body: {type: 'info', condition: show('body'), text: 'Top-level group at 0 0 0, unrotated. Everything else goes inside it.'},
		info_part: {type: 'info', condition: show('part'), text: 'Group the part turns or moves (turret yaw, wheel, door). Pivot = hinge.'},
		info_pitch: {type: 'info', condition: show('pitch'), text: 'Group inside the part frame that elevates the gun. Pivot = trunnion.'},
		info_collider: {type: 'info', condition: show('collider'), text: 'Box or closed convex mesh. Never drawn. Put it in the frame it moves with.'},
		info_hit: {type: 'info', condition: show('hit'), text: 'Never drawn. Shots and blasts that reach it damage the system.'},
		info_hit_surface: {type: 'info', condition: show('hit_surface'), text: 'Simple stand-in shape shots hit instead of the parent group\'s drawn parts.'},
		info_socket: {type: 'info', condition: show('socket'), text: 'Use a locator. Its position (and rotation) is the mount point.'},
		field: {label: 'Socket', type: 'select', condition: show('socket'), options: Object.fromEntries(SOCKET_FIELDS.map(f => [f, f]))},
		interface: {label: 'Interface', type: 'text', condition: r => r.role === 'part' || r.role === 'pitch'
				|| (r.role === 'socket' && !UNTARGETED.includes(r.field)),
			description: 'Part id from the vehicle json (frames, seat/view/sight/bolt...) or entry id (era, slat, mount_point, hitch)'},
		index: {label: 'Index', type: 'number', min: -1, step: 1, condition: show('socket'),
			description: '-1 = none; n = the n-th of a list (bolts, exhausts)'},
		label: {label: 'Label', type: 'text', condition: show('collider'), description: 'Name in the in-game readout'},
		main: {label: 'Main', type: 'checkbox', condition: show('collider'),
			description: 'The physics body; at most one, straight in the body. None marked => largest solid body piece'},
		solid: {label: 'Solid', type: 'checkbox', condition: show('collider'),
			description: 'Off: a marker (wheel probe, gear leg, pylon station), not a surface'},
		deck: {label: 'Deck', type: 'checkbox', condition: show('collider'), description: 'Other vehicles may park on it'},
		world: {label: 'Hits world', type: 'checkbox', condition: show('collider'),
			description: 'Off: only vehicles collide with it (ramps that open below ground)'},
		hit: {label: 'Hit surface', type: 'checkbox', condition: show('collider'),
			description: 'Also replaces its parent group\'s drawn parts as hit surface'},
		locked: {label: 'Locked', type: 'checkbox', condition: show('collider'),
			description: 'tools/gen_hulls.py keeps this piece when it regenerates'},
		system: {label: 'System', type: 'select', condition: show('hit'), options: systemOptions},
		side: {label: 'Side', type: 'select', condition: show('hit', 'none', 'body', 'part', 'pitch', 'hit_surface'),
			options: {none: 'None', left: 'Left', right: 'Right'}},
		limit_pitch: {label: 'Limit pitch', type: 'checkbox', condition: show('part', 'pitch'),
			description: 'x_rot_min/max (degrees, negative = up); on the pitch frame, else the part frame'},
		x_rot_min: {label: 'x_rot_min', type: 'number', condition: r => show('part', 'pitch')(r) && r.limit_pitch},
		x_rot_max: {label: 'x_rot_max', type: 'number', condition: r => show('part', 'pitch')(r) && r.limit_pitch},
		limit_yaw: {label: 'Limit yaw', type: 'checkbox', condition: show('part'), description: 'y_rot_min/max (degrees)'},
		y_rot_min: {label: 'y_rot_min', type: 'number', condition: r => r.role === 'part' && r.limit_yaw},
		y_rot_max: {label: 'y_rot_max', type: 'number', condition: r => r.role === 'part' && r.limit_yaw},
		hidden: {label: 'Hidden in game', type: 'checkbox', condition: show('none'),
			description: 'Not drawn (gemrenderHidden); shapes and sockets always'},
		unit: {label: 'Unit', type: 'text', condition: show('none', 'body', 'part', 'pitch', 'hit', 'hit_surface'),
			description: 'Pool split by name (FL, R1); inherited; HUD ROTR FL'},
		spin: {label: 'Spin', type: 'select', condition: show('none', 'body', 'part', 'pitch', 'hit', 'hit_surface'),
			description: 'Rotor unit turn direction seen from above; inherited; required on rotor units',
			options: {none: 'Inherit', cw: 'Clockwise', ccw: 'Counter-clockwise'}},
		road_wheel: {label: 'Road wheel', type: 'checkbox', condition: show('none'),
			description: 'Rides the nearest track: own spring probe under it, lower run follows'},
		follows_clips: {label: 'Colliders follow clips', type: 'checkbox', condition: show('body', 'part', 'pitch'),
			description: 'Colliders and hit nodes under this frame move with the part clip each tick (part must not be rotatable, e.g. generic); else colliders stay at rest pose'},
		scale_value: {label: 'Value', type: 'number', min: 0, step: 0.01, value: 1,
			condition: r => r.role === 'socket' && !!SCALED_FIELDS[r.field],
			description: 'Node scale the game reads as a size (bolt barrel length, eject speed, station round length, propeller radius, cargo bay size)'},
		light_kind: {label: 'Light kind', type: 'select', condition: isLight, options: LIGHT_KINDS},
		light_color: {label: 'Colour', type: 'text', condition: isLight, description: '#rrggbb; empty = the kind\'s colour'},
		light_range: {label: 'Range', type: 'number', min: 0, condition: isLight, description: 'Blocks; 0 = kind default'},
		light_intensity: {label: 'Intensity', type: 'number', min: 0, condition: isLight,
			description: 'Irradiance ~1 at sqrt(intensity) blocks; 0 = kind default'},
		light_inner: {label: 'Inner', type: 'number', min: 0, max: 88.9, condition: isLight,
			description: 'Full-bright half angle, deg; 0 = kind default'},
		light_outer: {label: 'Outer', type: 'number', min: 0, max: 88.9, condition: isLight,
			description: 'Cone half angle, deg (< 89); 0 = kind default'},
		light_shadow: {label: 'Shadow', type: 'checkbox', condition: isLight},
		light_cookie: {label: 'Cookie', type: 'text', condition: isLight, description: 'Beam pattern texture, ns:path; empty = none'},
		meta_system: {label: 'Hit system', type: 'select', condition: show('none', 'body', 'part', 'pitch', 'hit_surface'),
			description: 'Which system a hit here damages; inherited by everything inside',
			options: Object.assign({auto: 'Auto (inherited)', none: 'None (surface only)'}, systemOptions)},
		skip: {label: 'Not hit', type: 'checkbox', condition: show('none', 'body', 'part', 'pitch', 'hit', 'hit_surface'),
			description: 'No hit geometry at all'},
		swept: {label: 'Swept disc', type: 'select', condition: show('none', 'body', 'part', 'pitch', 'hit', 'hit_surface'),
			description: 'Hit as the disc it spins through (rotors)', options: {auto: 'Auto', on: 'On', off: 'Off'}},
		health: {label: 'Health', type: 'number', min: 0, condition: show('none', 'body', 'part', 'pitch', 'hit', 'hit_surface'),
			description: '0 = system default'},
	};
}

/** Selecting a group also marks its children selected: groups win. */
function selectedNodes() {
	if (Group.multi_selected.length) return Group.multi_selected.slice();
	return Outliner.selected.filter(n => NODE_CLASSES.some(c => n instanceof c));
}

function writeSettings(nodes, result) {
	const value = {};
	for (const key in DEFAULTS) {
		if (result[key] !== undefined && result[key] !== null && result[key] !== DEFAULTS[key]) value[key] = result[key];
	}
	if (value.role === 'socket' && UNTARGETED.includes(value.field || DEFAULTS.field)) delete value.interface;
	const groups = nodes.filter(n => n instanceof Group);
	const elements = nodes.filter(n => !(n instanceof Group));
	Undo.initEdit({groups, elements});
	nodes.forEach(n => {
		n[PROP] = Object.assign({}, value);
		if (n instanceof Locator) {
			const field = value.role === 'socket' ? value.field || DEFAULTS.field : null;
			n[SCALE_PROP] = field && SCALED_FIELDS[field] && result.scale_value > 0 ? scaleForValue(field, result.scale_value) : [1, 1, 1];
		}
	});
	Undo.finishEdit('Change CombinedAssault role');
}

function buildPanel() {
	const form = new InputForm(roleForm());
	panel = new Panel('combinedassault', {
		name: 'CombinedAssault',
		icon: 'precision_manufacturing',
		condition: {modes: ['edit']},
		display_condition: () => selectedNodes().length > 0,
		default_position: {slot: 'right_bar', float_position: [0, 0], float_size: [300, 400], height: 300,
			sidebar_index: 7},
		form,
	});
	form.update(form.getResult());
	form.on('input', ({result}) => {
		const nodes = selectedNodes();
		if (nodes.length) writeSettings(nodes, result);
	});
	selectionListener = () => {
		const node = selectedNodes()[0];
		if (!node) return;
		const s = caOf(node);
		form.setValues(Object.assign({scale_value: node instanceof Locator && SCALED_FIELDS[s.field] ? valueOfScale(s.field, node[SCALE_PROP]) : 1}, s));
	};
	Blockbench.on('update_selection', selectionListener);
}

// ---------------------------------------------------------------- helpers: sockets, player reference

function insertionGroup() {
	const node = selectedNodes()[0];
	if (!node) return Group.all.find(g => roleOf(g) === 'body') || null;
	return node instanceof Group ? node : node.parent instanceof Group ? node.parent : null;
}

/** Model-space px position of a node's pivot. */
function pivotOf(node) {
	const object = node.scene_object || node.mesh;
	const v = object.getWorldPosition(new THREE.Vector3());
	Project.model_3d.worldToLocal(v);
	return [v.x, v.y, v.z];
}

/** Socket locators a rider sees from: seat eye, weapon view, sight lens, third-person camera. */
function viewLocators() {
	return Locator.all.filter(l => roleOf(l) === 'socket' && VIEW_FIELDS.includes(l[PROP].field) && l.export !== false);
}

function locatorView(locator) {
	const object = locator.scene_object || locator.mesh;
	const position = object.getWorldPosition(new THREE.Vector3());
	const forward = new THREE.Vector3(0, 0, 1).applyQuaternion(object.getWorldQuaternion(new THREE.Quaternion()));
	return viewAngle([position.x, position.y, position.z], [forward.x, forward.y, forward.z]);
}

let viewCursor = -1;

function lookThrough(locator) {
	const preview = Preview.selected;
	preview.loadAnglePreset(locatorView(locator));
	if (typeof preview.setFOV === 'function') preview.setFOV(70);
	Blockbench.showQuickMessage(`${locator[PROP].field} ${locator[PROP].interface || ''} ${locator.name}`.replace(/\s+/g, ' '), 1200);
}

function passengerViewDialog() {
	const views = viewLocators();
	if (!views.length) return Blockbench.showQuickMessage('No seat / view / sight / camera sockets', 1800);
	const label = l => `${l[PROP].field} ${l[PROP].interface || ''} (${l.name})`.replace(/\s+/g, ' ');
	new Dialog({
		id: 'ca_passenger_view',
		title: 'View as passenger',
		form: {
			socket: {label: 'Socket', type: 'select', options: Object.fromEntries(views.map((l, i) => [i, label(l)]))},
			info: {type: 'info', text: 'Moves the preview camera to the socket eye, looking along its local +Z (nose), FOV 70. Orbit to leave.'},
		},
		onConfirm(result) {
			viewCursor = Number(result.socket);
			lookThrough(views[viewCursor]);
		},
	}).show();
}

function nextPassengerView(step) {
	const views = viewLocators();
	if (!views.length) return Blockbench.showQuickMessage('No seat / view / sight / camera sockets', 1800);
	viewCursor = (viewCursor + step + views.length) % views.length;
	lookThrough(views[viewCursor]);
}

const EYE = 1.62 * PX;
const HITBOX = [0.6 * PX, 1.8 * PX];

// PlayerRenderer scales the model 15/16: 1.875 blocks tall.
const PLAYER_SCALE = 15 / 16;

/** Vanilla player model, feet at `feet` (px), facing +Z. Seated = riding pose. */
function playerCubes(feet, seated) {
	const [x, y, z] = feet;
	const at = (p, k) => [x, y, z][k] + p[k] * PLAYER_SCALE;
	const box = (name, from, to, origin, rotation, color) => ({
		name, color, from: [0, 1, 2].map(k => at(from, k)), to: [0, 1, 2].map(k => at(to, k)),
		origin: [0, 1, 2].map(k => at(origin, k)), rotation: rotation || [0, 0, 0],
	});
	const legs = seated ? [-90, 0, 0] : null;
	const arms = seated ? [-36, 0, 0] : null;
	return [
		box('head', [-4, 24, -4], [4, 32, 4], [0, 24, 0], null, 1),
		box('body', [-4, 12, -2], [4, 24, 2], [0, 12, 0], null, 2),
		box('right_arm', [-8, 12, -2], [-4, 24, 2], [-5, 22, 0], arms, 3),
		box('left_arm', [4, 12, -2], [8, 24, 2], [5, 22, 0], arms, 3),
		box('right_leg', [-4, 0, -2], [0, 12, 2], [-2, 12, 0], legs, 4),
		box('left_leg', [0, 0, -2], [4, 12, 2], [2, 12, 0], legs, 4),
		box('eye_1.62', [-4.5, EYE / PLAYER_SCALE - 0.25, 3.75], [4.5, EYE / PLAYER_SCALE + 0.25, 4.5], [0, EYE / PLAYER_SCALE, 0], null, 0),
	];
}

/** Twelve 0.25 px bars outlining the 0.6 x 1.8 x 0.6 hitbox. */
function hitboxEdges(feet) {
	const [x, y, z] = feet;
	const h = HITBOX[0] / 2;
	const t = 0.125;
	const top = HITBOX[1];
	const edges = [];
	for (const sx of [-h, h]) {
		for (const sz of [-h, h]) edges.push([[sx - t, 0, sz - t], [sx + t, top, sz + t]]);
	}
	for (const sy of [0, top]) {
		for (const sz of [-h, h]) edges.push([[-h, sy - t, sz - t], [h, sy + t, sz + t]]);
		for (const sx of [-h, h]) edges.push([[sx - t, sy - t, -h], [sx + t, sy + t, h]]);
	}
	return edges.map(([a, b], i) => ({name: `hitbox_${i}`, color: 6,
		from: [x + a[0], y + a[1], z + a[2]], to: [x + b[0], y + b[1], z + b[2]], origin: [x, y, z]}));
}

function addPlayerDialog() {
	const node = selectedNodes()[0];
	const seat = node && roleOf(node) === 'socket' && caOf(node).field === 'seat';
	new Dialog({
		id: 'ca_add_player',
		title: 'Add player reference',
		form: {
			seated: {label: 'Seated (riding pose)', type: 'checkbox', value: !!seat},
			at_seat: {label: 'Eye at selected seat', type: 'checkbox', value: !!seat, condition: () => !!seat},
			info: {type: 'info', text: 'Not exported. Player model as drawn in game (1.875 tall), 0.6 x 1.8 x 0.6 hitbox outline, eye bar at 1.62.'},
		},
		onConfirm(result) {
			let feet = [0, 0, 0];
			if (node && seat && result.at_seat) {
				const eye = pivotOf(node);
				feet = [eye[0], eye[1] - EYE, eye[2]];
			} else if (node) {
				feet = pivotOf(node);
			}
			Undo.initEdit({elements: [], outliner: true});
			const group = new Group({name: 'player_reference', origin: feet, export: false}).init();
			const hitbox = new Group({name: 'hitbox', origin: feet, export: false}).addTo(group).init();
			const cubes = [];
			for (const data of playerCubes(feet, result.seated)) {
				cubes.push(new Cube(Object.assign({export: false}, data)).addTo(group).init());
			}
			for (const data of hitboxEdges(feet)) {
				cubes.push(new Cube(Object.assign({export: false}, data)).addTo(hitbox).init());
			}
			for (const cube of cubes) {
				for (const face of Object.values(cube.faces)) face.texture = false;
			}
			Canvas.updateView({elements: cubes, element_aspects: {geometry: true, faces: true, uv: true}});
			Undo.finishEdit('Add player reference', {elements: cubes, outliner: true});
		},
	}).show();
}

// ---------------------------------------------------------------- authoring tools (Blender add-on operators)

/** Model-space px corners of a cube / vertices of a mesh element. */
function elementPoints(element) {
	const object = element.mesh;
	object.updateWorldMatrix(true, false);
	const local = element instanceof Cube
		? [0, 1].flatMap(x => [0, 1].flatMap(y => [0, 1].map(z => [
			(x ? element.to[0] + element.inflate : element.from[0] - element.inflate) - element.origin[0],
			(y ? element.to[1] + element.inflate : element.from[1] - element.inflate) - element.origin[1],
			(z ? element.to[2] + element.inflate : element.from[2] - element.inflate) - element.origin[2]])))
		: Object.values(element.vertices);
	return local.map(v => {
		const w = object.localToWorld(new THREE.Vector3(v[0], v[1], v[2]));
		Project.model_3d.worldToLocal(w);
		return [w.x, w.y, w.z];
	});
}

/** Drawn (untagged) cube / mesh descendants of a group, with the group itself when it is one: model-space px. */
function drawnPoints(node) {
	if (!(node instanceof Group)) return exported(node) && roleOf(node) === 'none' ? elementPoints(node) : [];
	return node.children.flatMap(child => child instanceof Group ? drawnPoints(child)
		: (child instanceof Cube || child instanceof Mesh) && exported(child) && roleOf(child) === 'none' ? elementPoints(child) : []);
}

/** Mesh element with `points` (model-space px) as faces `loops`, pivot at their centre. */
function addMesh(name, points, loops, parent, props, extras) {
	const origin = [0, 1, 2].map(a => (Math.min(...points.map(p => p[a])) + Math.max(...points.map(p => p[a]))) / 2);
	const vertices = {};
	points.forEach((p, i) => { vertices[`v${i}`] = p.map((v, a) => v - origin[a]); });
	const faces = {};
	loops.forEach((loop, i) => { faces[`f${i}`] = {vertices: loop.map(v => `v${v}`), texture: false}; });
	const mesh = new Mesh({name, origin, vertices, faces});
	mesh[PROP] = props;
	if (extras) mesh[EXTRAS_PROP] = extras;
	if (SHAPES.includes(props.role) || props.role === 'socket') mesh.visibility = false;
	mesh.addTo(parent || undefined).init();
	return mesh;
}

function withUndo(label, elements, groups, body) {
	Undo.initEdit({elements, groups, outliner: true});
	const made = body();
	Undo.finishEdit(label, {elements: made.filter(n => !(n instanceof Group)), groups: made.filter(n => n instanceof Group), outliner: true});
	return made;
}

function addSocketDialog() {
	new Dialog({
		id: 'ca_add_socket',
		title: 'Add socket',
		form: {
			field: {label: 'Socket', type: 'select', options: Object.fromEntries(SOCKET_FIELDS.map(f => [f, f]))},
			interface: {label: 'Interface', type: 'text', condition: r => !UNTARGETED.includes(r.field),
				description: 'Part id (seat, view, sight, bolt...) or entry id (mount_point, hitch)'},
			index: {label: 'Index', type: 'number', value: -1, min: -1, step: 1, description: '-1 = none; n = the n-th of a list (bolts, exhausts)'},
			info: {type: 'info', text: 'Placed at the pivot of the selected group. Move it into place afterwards. era / slat / track / sight_lens are meshes: a starter quad / loop.'},
		},
		onConfirm(result) {
			const parent = insertionGroup();
			const origin = parent ? parent.origin.slice() : [0, 0, 0];
			const targeted = !UNTARGETED.includes(result.field);
			const name = ['socket', result.field, targeted ? result.interface : '', result.index >= 0 ? String(result.index) : ''].filter(Boolean).join('_');
			const props = {role: 'socket', field: result.field, interface: targeted ? result.interface : ''};
			if (result.index >= 0) props.index = result.index;
			const made = withUndo('Add CombinedAssault socket', [], [], () => {
				let node;
				if (MESH_FIELDS.includes(result.field)) {
					const shape = SOCKET_SHAPES[result.field];
					node = addMesh(name, shape.points.map(p => p.map((v, a) => v + origin[a])), shape.loops, parent, props);
				} else {
					node = new Locator({name, from: origin});
					node[PROP] = props;
					node.addTo(parent || undefined).init();
				}
				node.select();
				return [node];
			});
			return made;
		},
	}).show();
}

function selectedElements() {
	return selectedNodes().filter(n => n instanceof Cube || n instanceof Mesh);
}

/** make_hull: convex hull of the selected cubes / meshes as a collider, in the first one's frame. */
function makeHull() {
	const sources = selectedElements();
	if (!sources.length) return Blockbench.showQuickMessage('Select cubes or meshes first', 2000);
	const {points, loops} = convexHull(sources.flatMap(elementPoints));
	withUndo('CombinedAssault convex hull', [], [], () => {
		const parent = frameOf(sources[0].parent) || sources[0].parent;
		const mesh = addMesh(sources[0].name + '_hull', points, loops, parent instanceof Group ? parent : undefined, {role: 'collider'});
		mesh.select();
		return [mesh];
	});
}

/** make_hinge: <name>_mount (the hinge frame, at the pivot of the selection) -> <name> (identity), selection moved into it. */
function makeHingeDialog() {
	new Dialog({
		id: 'ca_make_hinge',
		title: 'Make hinge',
		form: {
			name: {label: 'Node name', type: 'text', value: 'hinge', description: 'The node a part clip or script turns'},
			part: {label: 'Part id', type: 'text', value: '', description: 'Non-empty: the pivot is that part\'s frame'},
			info: {type: 'info', text: 'Pivot at the first selected node\'s group pivot. Rotate <name>_mount to aim the hinge axis, then key the clip on <name>.'},
		},
		onConfirm(result) {
			const movers = selectedNodes();
			if (!movers.length) return Blockbench.showQuickMessage('Select the nodes the hinge turns first', 2000);
			const parent = movers[0].parent instanceof Group ? movers[0].parent : undefined;
			const origin = pivotOf(movers[0]);
			withUndo('CombinedAssault hinge', movers.filter(n => !(n instanceof Group)), movers.filter(n => n instanceof Group), () => {
				const mount = new Group({name: result.name + '_mount', origin}).addTo(parent).init();
				const pivot = new Group({name: result.name, origin}).addTo(mount).init();
				if (result.part) pivot[PROP] = {role: 'part', interface: result.part};
				movers.forEach(n => n.addTo(pivot));
				pivot.select();
				return [mount, pivot, ...movers];
			});
		},
	}).show();
}

/** merge_coplanar: faces of the selected collider meshes on one plane get one face tag (= one hull face on export). */
function mergeCoplanar() {
	const colliders = selectedNodes().filter(n => n instanceof Mesh && roleOf(n) === 'collider');
	if (!colliders.length) return Blockbench.showQuickMessage('Select collider meshes first', 2000);
	let merged = 0;
	Undo.initEdit({elements: colliders});
	for (const mesh of colliders) {
		const faces = Object.values(mesh.faces).filter(f => f.vertices.length >= 3);
		const ids = coplanarGroups(faces.map(f => f.vertices.map(k => mesh.vertices[k])));
		const base = 1 + Math.max(-1, ...faces.map(f => f[FACE_TAG]));
		const sizes = new Map();
		ids.forEach(id => sizes.set(id, (sizes.get(id) || 0) + 1));
		faces.forEach((face, i) => {
			const shared = ids[i] >= 0 && sizes.get(ids[i]) > 1;
			face[FACE_TAG] = shared ? base + ids[i] : -1;
			if (shared) merged++;
		});
	}
	Undo.finishEdit('CombinedAssault merge coplanar faces', {elements: colliders});
	Blockbench.showQuickMessage(`${merged} face(s) joined into shared planes`, 2500);
}

/** key_clip: a start / end key of part clip <clip> on the selected nodes (offsets from rest). */
function keyClipDialog() {
	new Dialog({
		id: 'ca_key_clip',
		title: 'Key part clip',
		form: {
			clip: {label: 'Clip', type: 'text', value: 'door', description: 'Part id, or <part id>.<suffix> for more clips on one part'},
			end: {label: 'Key', type: 'select', options: {start: 'Start (progress 0: default state)', end: 'End (progress 1: other state)'}, value: 'start'},
			seconds: {label: 'Length s', type: 'number', value: 0.6, min: 0.01, description: 'End key time; a switchable\'s open / close time'},
			position: {label: 'Position offset', type: 'vector', dimensions: 3, value: [0, 0, 0], description: 'Ignored at Start (rest)'},
			rotation: {label: 'Rotation offset', type: 'vector', dimensions: 3, value: [0, 0, 0], description: 'Degrees; ignored at Start (rest)'},
		},
		onConfirm(result) {
			if (!CLIP_NAME.test(result.clip)) return Blockbench.showMessageBox({title: 'Key part clip', message: 'clip name: <part id>[.<suffix>], part id lowercase a-z 0-9 _'});
			const nodes = selectedNodes().filter(n => n instanceof Group || n instanceof Locator);
			if (!nodes.length) return Blockbench.showQuickMessage('Select groups or locators first', 2000);
			const time = result.end === 'end' ? result.seconds : 0;
			Undo.initEdit({animations: Animator.animations.slice()});
			let animation = Animator.animations.find(a => a.name === result.clip);
			if (!animation) animation = new Animation({name: result.clip, length: Math.max(result.seconds, 0.1), loop: 'once'}).add(false);
			if (time > animation.length) animation.length = time;
			for (const node of nodes) {
				const animator = animation.getBoneAnimator(node);
				for (const channel of ['position', 'rotation']) {
					const value = result.end === 'end' ? result[channel] : [0, 0, 0];
					for (const old of (animator.keyframes || []).filter(k => k.channel === channel && Math.abs(k.time - time) < 1e-3)) old.remove();
					animator.addKeyframe({channel, time, interpolation: 'linear', data_points: [{x: value[0], y: value[1], z: value[2]}]});
				}
			}
			animation.select();
			Undo.finishEdit('CombinedAssault key part clip', {animations: [animation]});
		},
	}).show();
}

/** assign_armor: the selected mesh faces get a copy of their material named `<stem>[_surface]_armor_<mm>`. */
function assignArmorDialog() {
	new Dialog({
		id: 'ca_assign_armor',
		title: 'Assign armour to faces',
		form: {mm: {label: 'mm RHA', type: 'number', value: 100, min: 0}},
		onConfirm(result) {
			const mesh = Mesh.selected[0];
			const faces = mesh ? mesh.getSelectedFaces().map(k => mesh.faces[k]) : [];
			if (!faces.length) return Blockbench.showQuickMessage('Select mesh faces first (face select mode)', 2500);
			Undo.initEdit({elements: [mesh]});
			Project.ca_materials = Project.ca_materials || {};
			for (const face of faces) {
				const texture = face.getTexture();
				const baseKey = face[FACE_MATERIAL];
				const base = baseKey && Project.ca_materials[baseKey];
				const baseName = base && base.name !== undefined ? base.name : texture instanceof Texture ? texture.name.replace(/\.png$/i, '') : 'material';
				const name = taggedName(baseName, result.mm, surfaceOf(baseName), false);
				const key = `armor|${name}`;
				if (!Project.ca_materials[key]) {
					const rgba = texture instanceof Texture
						? texture.canvas.getContext('2d').getImageData(0, 0, texture.canvas.width, texture.canvas.height).data : null;
					Project.ca_materials[key] = base ? Object.assign(JSON.parse(JSON.stringify(base)), {name})
						: {name, alphaMode: rgba ? alphaModeOf(rgba) : 'OPAQUE', alphaCutoff: 0.5, doubleSided: false, source: {}};
				}
				face[FACE_MATERIAL] = key;
			}
			Undo.finishEdit('CombinedAssault assign armour', {elements: [mesh]});
			Blockbench.showQuickMessage(`${faces.length} face(s) at ${result.mm} mm`, 2500);
		},
	}).show();
}

/** Armour mm / surface / decor of the selected texture, as its name (the game reads them from the material name). */
function tagTextureDialog() {
	const texture = Texture.selected || Texture.all[0];
	if (!texture) return Blockbench.showQuickMessage('Load a texture first', 2000);
	const tags = materialTags(texture.name);
	new Dialog({
		id: 'ca_tag_texture',
		title: `Material tags: ${texture.name}`,
		form: {
			armor: {label: 'Armour mm', type: 'number', value: tags.armor, min: 0},
			surface: {label: 'Hit effect', type: 'select', value: tags.surface,
				options: {steel: 'Steel (sparks, holes)', rubber: 'Rubber (black bits, dust, no hole)', glass: 'Glass (shards, glint, no hole)', soft: 'Soft (dust, fibres, no hole)'}},
			decor: {label: 'Decor', type: 'checkbox', value: tags.decor, description: 'Never hit; shots pass'},
			info: {type: 'info', text: 'Renames the texture; the material is named after it. Whole texture: for single faces use Assign armour to faces.'},
		},
		onConfirm(result) {
			const stem = taggedName(texture.name.replace(/\.png$/i, ''), result.armor, result.surface, result.decor);
			Undo.initEdit({textures: [texture]});
			texture.name = /\.png$/i.test(texture.name) ? stem + '.png' : stem;
			texture.updateMaterial && texture.updateMaterial();
			Undo.finishEdit('CombinedAssault material tags', {textures: [texture]});
		},
	}).show();
}

/** hit_cylinder: a hit_surface prism round each selected group's drawn subtree, child of it. */
function hitCylinderDialog() {
	new Dialog({
		id: 'ca_hit_cylinder',
		title: 'Make hit cylinder',
		form: {
			sides: {label: 'Sides', type: 'number', value: 12, min: 3, max: 64, step: 1},
			surface: {label: 'Material', type: 'select', value: 'rubber', options: {rubber: 'rubber', glass: 'glass', soft: 'soft', '': 'None (steel)'}},
			info: {type: 'info', text: 'Tyres: tread detail is noise. Regenerable by tools/gen_hulls.py --hit-cylinder. Built in model axes: keep the group\'s ancestors unrotated.'},
		},
		onConfirm(result) {
			const groups = selectedNodes().filter(n => n instanceof Group && ['none', 'part', 'pitch'].includes(roleOf(n)));
			if (!groups.length) return Blockbench.showQuickMessage('Select model / part / pitch groups first', 2000);
			withUndo('CombinedAssault hit cylinder', [], groups, () => {
				const made = [];
				for (const group of groups) {
					const points = drawnPoints(group);
					if (points.length < 3) { Blockbench.showQuickMessage(`${group.name}: nothing drawn`, 2000); continue; }
					const {points: ring, loops} = prismAround(points, result.sides);
					const mesh = addMesh('hit_' + group.name, ring, loops, group, {role: 'hit_surface'}, {genHulls: {node: group.name, hit: true}});
					if (result.surface) {
						Project.ca_materials = Project.ca_materials || {};
						Project.ca_materials[result.surface] = Project.ca_materials[result.surface]
							|| {name: result.surface, alphaMode: 'OPAQUE', alphaCutoff: 0.5, doubleSided: false, source: {}};
						Object.values(mesh.faces).forEach(f => { f[FACE_MATERIAL] = result.surface; });
					}
					made.push(mesh);
				}
				return made;
			});
		},
	}).show();
}

/** add_screen: quad facing the rider (-Z), UVs 0..1, material with textureSlot screen_<id>. */
function addScreenDialog() {
	new Dialog({
		id: 'ca_add_screen',
		title: 'Add screen',
		form: {
			screen: {label: 'Screen id', type: 'text', value: 'dash', description: '`screens` key; slot = screen_<id>'},
			width: {label: 'Width (blocks)', type: 'number', value: 0.2, min: 0.01, step: 0.01},
			height: {label: 'Height (blocks)', type: 'number', value: 0.15, min: 0.01, step: 0.01},
		},
		onConfirm(result) {
			const slot = 'screen_' + result.screen;
			const parent = insertionGroup();
			const origin = parent ? parent.origin.slice() : [0, 0, 0];
			const w = result.width * PX / 2;
			const h = result.height * PX / 2;
			Project.ca_materials = Project.ca_materials || {};
			Project.ca_materials[slot] = {name: slot, alphaMode: 'OPAQUE', alphaCutoff: 0.5, doubleSided: false,
				source: {extras: {textureSlot: slot}}};
			withUndo('CombinedAssault screen', [], [], () => {
				const mesh = addMesh(slot, [[w, -h, 0], [-w, -h, 0], [-w, h, 0], [w, h, 0]].map(p => p.map((v, a) => v + origin[a])),
					[[0, 1, 2, 3]], parent, {role: 'none'});
				const face = Object.values(mesh.faces)[0];
				face[FACE_MATERIAL] = slot;
				face.uv = {v0: [0, 0], v1: [1, 0], v2: [1, 1], v3: [0, 1]};
				mesh.select();
				return [mesh];
			});
		},
	}).show();
}

// Light preview: a cone outline per light socket, beam along the socket's +Z; never exported.
const PREVIEW = 'ca_light_preview';

function lightCone(meta) {
	const kind = LIGHT_KIND_DEFAULTS[meta.kind || 'head'] || LIGHT_KIND_DEFAULTS.head;
	const reach = (meta.range || kind.range) * PX;
	const outer = meta.outer || kind.outer;
	const inner = meta.inner || Math.min(kind.inner, outer);
	const colour = HEX_COLOR.test(meta.color || '') ? parseInt(meta.color.slice(1), 16) : kind.color;
	return {reach, outer, inner, colour};
}

function clearLightPreviews() {
	for (const locator of Locator.all) {
		const object = locator.scene_object;
		if (!object) continue;
		for (const child of object.children.filter(c => c.name === PREVIEW)) {
			object.remove(child);
			child.geometry.dispose();
			child.material.dispose();
		}
	}
}

function previewLights() {
	clearLightPreviews();
	let made = 0;
	for (const locator of Locator.all) {
		const s = caOf(locator);
		if (roleOf(locator) !== 'socket' || s.field !== 'light' || locator.export === false || !locator.scene_object) continue;
		const cone = lightCone(lightMeta(s));
		const vertices = [];
		for (const angle of [cone.outer, cone.inner]) {
			const radius = cone.reach * Math.tan(Math.min(angle, 88.9) * Math.PI / 180);
			for (let k = 0; k < 24; k++) {
				const a = 2 * Math.PI * k / 24;
				const b = 2 * Math.PI * (k + 1) / 24;
				vertices.push(radius * Math.cos(a), radius * Math.sin(a), cone.reach, radius * Math.cos(b), radius * Math.sin(b), cone.reach);
			}
			if (angle === cone.outer) for (let k = 0; k < 4; k++) {
				const a = Math.PI / 2 * k;
				vertices.push(0, 0, 0, radius * Math.cos(a), radius * Math.sin(a), cone.reach);
			}
		}
		const geometry = new THREE.BufferGeometry();
		geometry.setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3));
		const lines = new THREE.LineSegments(geometry, new THREE.LineBasicMaterial({color: cone.colour}));
		lines.name = PREVIEW;
		locator.scene_object.add(lines);
		made++;
	}
	Canvas.updateView && Canvas.updateView({elements: [], element_aspects: {transform: true}});
	Blockbench.showQuickMessage(made ? `${made} light(s)` : 'no light sockets in the model', 2000);
}

// ---------------------------------------------------------------- import

const PACK_MODEL = /^(.*)\/assets\/([^/]+)\/models\/(.+)\.(glb|gltf)$/i;

function importDialog() {
	Blockbench.import({resource_id: 'combinedassault_model', extensions: ['glb', 'gltf'], type: 'CombinedAssault pack model',
		readtype: 'text'}, files => {
		importPackModel(slashes(files[0].path)).catch(err => {
			console.error(err);
			Blockbench.showMessageBox({title: 'Import failed', message: String(err && err.message || err)});
		});
	});
}

function waitForTexture(texture) {
	return new Promise(resolve => {
		const started = Date.now();
		const poll = () => {
			if (texture.width > 0 || texture.error || Date.now() - started > 10000) resolve();
			else setTimeout(poll, 20);
		};
		poll();
	});
}

async function importPackModel(path) {
	const match = path.match(PACK_MODEL);
	if (!match) throw new Error('Pick a .glb or .gltf inside <pack>/assets/<namespace>/models/');
	const [, packRoot, ns, modelPath] = match;
	const fs = fsModule('fs', {scope: packRoot, message: 'Read the model and textures from the pack folder'});
	if (!fs) throw new Error('No permission to read the pack folder');
	const {gltf, bin: chunk} = readContainer(fs.readFileSync(path));
	const dir = path.slice(0, path.lastIndexOf('/'));
	const fileOf = (location, uri) => {
		if (location) {
			const cut = location.indexOf(':');
			return `${packRoot}/assets/${location.slice(0, cut)}/${location.slice(cut + 1)}`;
		}
		return uri ? `${dir}/${decodeURIComponent(uri)}` : null;
	};
	const buffers = (gltf.buffers || []).map(b => {
		if (b.uri && b.uri.startsWith('data:')) return decodeDataUri(b.uri);
		if (!b.uri && !(b.extras && b.extras.resourceLocation) && chunk) return chunk;
		const file = fileOf(b.extras && b.extras.resourceLocation, b.uri);
		if (!file) throw new Error('a buffer has neither resourceLocation nor uri');
		return fs.readFileSync(file);
	});
	// Mesh elements build a plain THREE.Mesh: default 'XYZ', not Format.euler_order.
	const plan = planImport(gltf, buffers, {frame: Formats.free.euler_order, mesh: 'XYZ'});

	newProject(Formats.free);
	Project.name = modelPath.split('/').pop();
	Project.ca_pack_root = packRoot;
	Project.ca_namespace = ns;
	Project.ca_model_path = modelPath;

	// Blockbench holds one texture per file (Texture.add and its load both merge same-path textures): the glTF
	// material rides on each face, the image on the face texture.
	const byFile = new Map();
	const textures = new Map();
	const names = new Set();
	Project.ca_materials = {};
	for (const m of plan.materials) {
		Project.ca_materials[m.key] = m.kept;
		const file = fileOf(m.location, m.uri);
		if (!file) continue;
		if (!byFile.has(file)) {
			const texture = new Texture().fromPath(file);
			let name = texture.name;
			for (let k = 2; names.has(name); k++) name = texture.name.replace(/(\.png)?$/i, `_${k}$1`);
			names.add(name);
			texture.name = name;
			texture.render_sides = 'front';
			texture.add(false);
			byFile.set(file, texture);
		}
		const texture = byFile.get(file);
		if (m.kept.doubleSided) texture.render_sides = 'double';
		textures.set(m.index, texture);
	}
	await Promise.all([...textures.values()].map(waitForTexture));
	for (const texture of byFile.values()) {
		if (texture.error) plan.warnings.push(`texture ${texture.name}: ${texture.path} did not load`);
		texture.uv_width = texture.width;
		texture.uv_height = texture.height;
	}

	const built = [];
	for (const entry of plan.entries) {
		const parent = entry.parent >= 0 ? built[entry.parent] : undefined;
		const base = {name: entry.name, origin: entry.origin, rotation: entry.rotation};
		let node;
		if (entry.kind === 'group') {
			node = new Group(base);
		} else if (entry.kind === 'locator') {
			node = new Locator({name: entry.name, from: entry.origin, rotation: entry.rotation});
			if (entry.scale) node[SCALE_PROP] = entry.scale;
		} else {
			const vertices = {};
			entry.mesh.points.forEach((p, i) => { vertices[`v${i}`] = p; });
			const faces = {};
			entry.mesh.faces.forEach((f, i) => {
				const material = plan.materials.find(m => m.index === f.material);
				const texture = material ? textures.get(material.index) : null;
				const face = {vertices: f.vertices.map(v => `v${v}`), texture: texture ? texture.uuid : false};
				if (material) face[FACE_MATERIAL] = material.key;
				if (f.normals) {
					face[FACE_NORMALS] = {flat: flatNormal(f.vertices.map(v => entry.mesh.points[v])), corners: {}};
					f.vertices.forEach((v, k) => { face[FACE_NORMALS].corners[`v${v}`] = f.normals[k]; });
				}
				if (f.uv && texture) {
					face.uv = {};
					f.vertices.forEach((v, k) => { face.uv[`v${v}`] = [f.uv[k][0] * texture.uv_width, f.uv[k][1] * texture.uv_height]; });
				} else if (f.uv && material && material.kept.source && material.kept.source.extras && material.kept.source.extras.textureSlot) {
					face.uv = {};
					f.vertices.forEach((v, k) => { face.uv[`v${v}`] = f.uv[k].slice(); });
				}
				if (f.tag !== undefined) face[FACE_TAG] = f.tag;
				faces[`f${i}`] = face;
			});
			node = new Mesh(Object.assign(base, {vertices, faces}));
		}
		node[PROP] = entry.props;
		if (entry.extras) node[EXTRAS_PROP] = JSON.parse(JSON.stringify(entry.extras));
		if (entry.kind === 'mesh' && (SHAPES.includes(entry.props.role) || entry.props.role === 'socket')) node.visibility = false;
		node.addTo(parent).init();
		built.push(node);
	}

	for (const clip of plan.clips) {
		const animation = new Animation({name: clip.name, length: clip.length, loop: 'once'}).add(false);
		for (const channel of clip.channels) {
			const animator = animation.getBoneAnimator(built[channel.entry]);
			for (const key of channel.keys) {
				animator.addKeyframe({channel: channel.channel, time: key.time, interpolation: channel.interpolation,
					data_points: [{x: key.value[0], y: key.value[1], z: key.value[2]}]});
			}
		}
	}
	Canvas.updateAll();
	BarItems.focus_on_selection.trigger();
	const counts = ['group', 'mesh', 'locator'].map(k => `${plan.entries.filter(e => e.kind === k).length} ${k}s`).join(', ');
	showReport('CombinedAssault: imported', [], plan.warnings,
		`<p>${escapeHtml(path)}</p><p>${counts}, ${byFile.size} textures, ${plan.clips.length} animations.</p>`
		+ '<p>Export to pack writes back to the same file; textures stay where they are.</p>');
	return plan;
}

// ---------------------------------------------------------------- registration

Plugin.register('combinedassault', {
	title: 'CombinedAssault',
	author: 'combinedassault',
	description: 'Author CombinedAssault vehicle models: roles, checks, pack export with textures.',
	icon: 'precision_manufacturing',
	version: '1.0.0',
	min_version: '5.1.0',
	variant: 'desktop',
	onload() {
		for (const cls of NODE_CLASSES) {
			properties.push(new Property(cls, 'object', PROP, {exposed: false}));
			properties.push(new Property(cls, 'object', EXTRAS_PROP, {exposed: false}));
		}
		properties.push(new Property(Locator, 'vector', SCALE_PROP, {default: [1, 1, 1], exposed: false}));
		properties.push(new Property(MeshFace, 'number', FACE_TAG, {default: -1, exposed: false}));
		properties.push(new Property(MeshFace, 'string', FACE_MATERIAL, {exposed: false}));
		properties.push(new Property(MeshFace, 'object', FACE_NORMALS, {exposed: false}));
		properties.push(new Property(ModelProject, 'object', 'ca_materials', {exposed: false}));
		properties.push(new Property(ModelProject, 'string', 'ca_pack_root', {exposed: false}));
		properties.push(new Property(ModelProject, 'string', 'ca_namespace', {exposed: false}));
		properties.push(new Property(ModelProject, 'string', 'ca_model_path', {exposed: false}));
		const validate = new Action('ca_validate', {
			name: 'Validate vehicle', icon: 'fact_check', category: 'file', condition: () => !!Project,
			click() {
				const [errors, warnings] = checkProject();
				showReport('CombinedAssault check', errors, warnings);
			},
		});
		const importAction = new Action('ca_import', {
			name: 'Import pack model...', icon: 'file_open', category: 'file', click: importDialog,
		});
		const exportAction = new Action('ca_export', {
			name: 'Export to pack...', icon: 'icon-gltf', category: 'file', condition: () => !!Project,
			click: exportDialog,
		});
		const socket = new Action('ca_add_socket', {
			name: 'Add socket...', icon: 'my_location', category: 'edit', condition: () => !!Project,
			click: addSocketDialog,
		});
		const player = new Action('ca_add_player', {
			name: 'Add player reference...', icon: 'accessibility', category: 'edit', condition: () => !!Project,
			click: addPlayerDialog,
		});
		const passenger = new Action('ca_passenger_view', {
			name: 'View as passenger...', icon: 'videocam', category: 'view', condition: () => !!Project,
			click: passengerViewDialog,
		});
		const nextView = new Action('ca_next_passenger_view', {
			name: 'Next passenger view', icon: 'skip_next', category: 'view', condition: () => !!Project,
			keybind: new Keybind({key: 'pagedown', ctrl: true}),
			click: () => nextPassengerView(1),
		});
		const previousView = new Action('ca_previous_passenger_view', {
			name: 'Previous passenger view', icon: 'skip_previous', category: 'view', condition: () => !!Project,
			keybind: new Keybind({key: 'pageup', ctrl: true}),
			click: () => nextPassengerView(-1),
		});
		const tool = (id, name, icon, click) => new Action(id, {name, icon, category: 'edit', condition: () => !!Project, click});
		const hull = tool('ca_make_hull', 'Make convex hull', 'change_history', makeHull);
		const hinge = tool('ca_make_hinge', 'Make hinge...', 'swap_horiz', makeHingeDialog);
		const merge = tool('ca_merge_coplanar', 'Merge coplanar faces', 'join_full', mergeCoplanar);
		const keyClip = tool('ca_key_clip', 'Key part clip...', 'key', keyClipDialog);
		const armor = tool('ca_assign_armor', 'Assign armour to faces...', 'shield', assignArmorDialog);
		const tagTexture = tool('ca_tag_texture', 'Material tags (armour, hit effect, decor)...', 'sell', tagTextureDialog);
		const hitCylinder = tool('ca_hit_cylinder', 'Make hit cylinder...', 'trip_origin', hitCylinderDialog);
		const screen = tool('ca_add_screen', 'Add screen...', 'tv', addScreenDialog);
		const lights = new Action('ca_preview_lights', {name: 'Preview lights', icon: 'flashlight_on', category: 'view',
			condition: () => !!Project, click: previewLights});
		actions = [importAction, validate, exportAction, socket, player, passenger, nextView, previousView,
			hull, hinge, merge, keyClip, armor, tagTexture, hitCylinder, screen, lights];
		menu = new BarMenu('combinedassault', [importAction, exportAction, validate, '_', socket, hinge, screen, '_', hull, merge, hitCylinder, '_',
			keyClip, '_', tagTexture, armor, '_', player, passenger, nextView, previousView, lights], {name: 'CombinedAssault'});
		MenuBar.update();
		buildPanel();
	},
	onunload() {
		Blockbench.removeListener('update_selection', selectionListener);
		clearLightPreviews();
		if (panel) panel.delete();
		if (menu) {
			menu.delete();
			MenuBar.update();
		}
		actions.forEach(a => a.delete());
		properties.forEach(p => p.delete());
		actions = [];
		properties = [];
	},
});
})();
