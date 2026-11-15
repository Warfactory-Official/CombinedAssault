// node tools/blockbench/test_combinedassault.mjs
// fixtures/blockbench-5.1.6-raw.gltf: Codecs.gltf 'compile' event output of a real Blockbench 5.1.6 export through the
// plugin (roles set via its panel): hull body, turret part (yaw 30), gun pitch + clip "gun", colliders, hit, seat locator.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../..');
const ca = createRequire(import.meta.url)('./combinedassault.js');
const fixture = () => JSON.parse(fs.readFileSync(path.join(here, 'fixtures/blockbench-5.1.6-raw.gltf'), 'utf8'));
const PNG = Buffer.from('89504e470d0a1a0a', 'hex').toString('base64');

let failures = 0;
function test(name, body) {
	try {
		body();
		console.log(`ok   ${name}`);
	} catch (err) {
		failures++;
		console.log(`FAIL ${name}\n     ${err.message.split('\n').join('\n     ')}`);
	}
}

function pyList(source, name) {
	const match = source.match(new RegExp(`^${name} = [\\[{]([^\\]}]*)[\\]}]`, 'm'));
	return [...match[1].matchAll(/"([a-z_]+)"/g)].map(m => m[1]);
}

const ROOT = '/packs/demo';
const options = (textures) => ({
	packRoot: ROOT, namespace: 'combinedassault', modelPath: 'ground/test/tank',
	textures: textures || {
		'tank.png': {file: `${ROOT}/assets/combinedassault/textures/test/tank.png`, alpha: 'OPAQUE', doubleSided: false},
		'glass.png': {data: PNG, alpha: 'BLEND', doubleSided: true},
	},
});

function accessorFloats(gltf, bin, index) {
	const accessor = gltf.accessors[index];
	const view = gltf.bufferViews[accessor.bufferView];
	const width = {SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4}[accessor.type];
	const stride = view.byteStride || width * 4;
	const out = [];
	for (let i = 0; i < accessor.count; i++) {
		const row = [];
		for (let c = 0; c < width; c++) row.push(bin.readFloatLE((view.byteOffset || 0) + (accessor.byteOffset || 0) + i * stride + c * 4));
		out.push(row);
	}
	return out;
}

test('vocabularies equal tools/blender', () => {
	const py = ['ca_validate.py', 'combinedassault_tools.py'].map(f => path.join(repo, 'tools/blender', f))
		.filter(f => fs.existsSync(f)).map(f => fs.readFileSync(f, 'utf8')).find(text => /^SYSTEMS = \[/m.test(text));
	assert.deepEqual(ca.SYSTEMS, pyList(py, 'SYSTEMS'));
	assert.deepEqual(ca.SOCKET_FIELDS, pyList(py, 'SOCKET_FIELDS'));
	assert.deepEqual([...ca.UNTARGETED].sort(), pyList(py, 'UNTARGETED').sort());
});

test('socket fields equal NodeRole SOCKET constants', () => {
	const java = fs.readFileSync(path.join(repo, 'src/main/java/com/wf/combinedassault/ballistics/hit/NodeRole.java'), 'utf8');
	const sockets = [...java.matchAll(/^\s+([A-Z_]+)\(Kind\.SOCKET\)/gm)].map(m => m[1].toLowerCase());
	assert.deepEqual(ca.SOCKET_FIELDS, sockets);
});

test('nodeExtras: frames', () => {
	assert.deepEqual(ca.nodeExtras(undefined), {});
	assert.deepEqual(ca.nodeExtras({role: 'body'}), {combinedassaultRole: 'body'});
	assert.deepEqual(ca.nodeExtras({role: 'part', interface: 'turret', limit_yaw: true, y_rot_min: -120, y_rot_max: 120,
		limit_pitch: true, x_rot_min: -10, x_rot_max: 5}), {
		combinedassaultRole: 'part', combinedassaultInterface: 'turret',
		combinedassault: {x_rot_min: -10, x_rot_max: 5, y_rot_min: -120, y_rot_max: 120},
	});
	assert.deepEqual(ca.nodeExtras({role: 'pitch', interface: 'gun', limit_yaw: true}),
		{combinedassaultRole: 'pitch', combinedassaultInterface: 'gun'});
});

test('nodeExtras: collider keys only from the collider vocabulary', () => {
	const extras = ca.nodeExtras({role: 'collider', main: true, deck: true, solid: false, world: false, label: 'ramp',
		hit: true, locked: true, system: 'turret', health: 9});
	assert.deepEqual(extras, {
		combinedassaultRole: 'collider', gemrenderHidden: true, genHulls: {locked: true},
		combinedassault: {main: true, deck: true, solid: false, world: false, name: 'ramp', hit: true},
	});
	const allowed = ['main', 'deck', 'solid', 'world', 'name', 'hit'];
	assert.ok(Object.keys(extras.combinedassault).every(k => allowed.includes(k)));
});

test('nodeExtras: hit, overrides, sockets', () => {
	assert.deepEqual(ca.nodeExtras({role: 'hit'}),
		{combinedassaultRole: 'hit', gemrenderHidden: true, combinedassault: {system: 'engine'}});
	assert.deepEqual(ca.nodeExtras({role: 'hit', system: 'fuel', side: 'left', swept: 'off', health: 12.34567}), {
		combinedassaultRole: 'hit', gemrenderHidden: true,
		combinedassault: {system: 'fuel', side: 'left', swept: false, health: 12.34567},
	});
	assert.deepEqual(ca.nodeExtras({meta_system: 'none', skip: true, swept: 'on'}),
		{combinedassault: {system: 'none', skip: true, swept: true}});
	assert.deepEqual(ca.nodeExtras({role: 'hit_surface', meta_system: 'wheel'}),
		{combinedassaultRole: 'hit_surface', gemrenderHidden: true, combinedassault: {system: 'wheel'}});
	assert.deepEqual(ca.nodeExtras({role: 'socket', field: 'bolt', interface: 'gun', index: 1}), {
		combinedassaultRole: 'bolt', combinedassaultInterface: 'gun', gemrenderHidden: true,
		combinedassault: {index: 1},
	});
	assert.deepEqual(ca.nodeExtras({role: 'socket', field: 'exhaust'}),
		{combinedassaultRole: 'exhaust', gemrenderHidden: true});
});

test('quantiseCutoff copies GltfMaterial', () => {
	assert.equal(ca.quantiseCutoff(0.05), 0);
	assert.equal(ca.quantiseCutoff(0.06), 0.1);
	assert.equal(ca.quantiseCutoff(0.3), 0.1);
	assert.equal(ca.quantiseCutoff(0.31), 0.5);
	assert.equal(ca.quantiseCutoff(0.9), 0.5);
});

test('alphaModeOf', () => {
	assert.equal(ca.alphaModeOf(new Uint8Array([1, 2, 3, 255, 4, 5, 6, 255])), 'OPAQUE');
	assert.equal(ca.alphaModeOf(new Uint8Array([1, 2, 3, 255, 4, 5, 6, 0])), 'MASK');
	assert.equal(ca.alphaModeOf(new Uint8Array([1, 2, 3, 0, 4, 5, 6, 128])), 'BLEND');
});

test('paths and resource locations', () => {
	assert.equal(ca.resourceLocationOf('C:\\packs\\demo\\', 'C:\\packs\\demo\\assets\\wf\\textures\\a\\b.png'), 'wf:textures/a/b.png');
	assert.equal(ca.resourceLocationOf('/packs/demo', '/packs/other/assets/wf/textures/b.png'), null);
	assert.equal(ca.resourceLocationOf('/packs/demo', '/packs/demo/assets/b.png'), null);
	const p = ca.packPaths('/packs/demo/', 'wf', '/air/jet/f16.glb');
	assert.equal(p.glb, '/packs/demo/assets/wf/models/air/jet/f16.glb');
	assert.equal(p.textureDir, '/packs/demo/assets/wf/textures/air/jet');
	assert.equal(p.textureLocation, 'wf:textures/air/jet');
	assert.equal(ca.packPaths('/p', 'wf', 'f16').textureLocation, 'wf:textures');
});

test('finishGltf: buffer = bare byteLength, no uri; .glb round trip', () => {
	const raw = fixture();
	const embedded = Buffer.from(raw.buffers[0].uri.split(',')[1], 'base64');
	const out = ca.finishGltf(raw, options());
	assert.equal(out.paths.glb, `${ROOT}/assets/combinedassault/models/ground/test/tank.glb`);
	assert.ok(out.bin.equals(embedded));
	assert.deepEqual(out.gltf.buffers, [{byteLength: embedded.length}]);
	const glb = ca.writeGlb(JSON.parse(JSON.stringify(out.gltf)), out.bin);
	assert.equal(glb.length % 4, 0);
	assert.equal(glb.readUInt32LE(8), glb.length);
	const back = ca.readContainer(glb);
	assert.deepEqual(back.gltf, out.gltf);
	assert.ok(back.bin.subarray(0, out.bin.length).equals(out.bin));
	assert.deepEqual(ca.readContainer(Buffer.from(JSON.stringify(out.gltf))).bin, null);
	assert.ok(!JSON.stringify(out.gltf).includes('"uri"'));
	for (const view of out.gltf.bufferViews) assert.ok(view.byteOffset + view.byteLength <= out.bin.length);
});

test('finishGltf: textures referenced in place or written, never renamed', () => {
	const out = ca.finishGltf(fixture(), options());
	const images = Object.fromEntries(out.gltf.images.map(i => [i.name, i]));
	assert.deepEqual(images['tank.png'].extras, {resourceLocation: 'combinedassault:textures/test/tank.png'});
	assert.deepEqual(images['glass.png'].extras, {resourceLocation: 'combinedassault:textures/ground/test/glass.png'});
	assert.deepEqual(out.writes.map(w => w.path), [`${ROOT}/assets/combinedassault/textures/ground/test/glass.png`]);
	assert.equal(out.writes[0].data.toString('base64'), PNG);
	const outside = ca.finishGltf(fixture(), options({
		'tank.png': {file: '/elsewhere/tank.png', data: PNG, alpha: 'OPAQUE'},
		'glass.png': {data: PNG, alpha: 'BLEND'},
	}));
	assert.deepEqual(outside.writes.map(w => path.basename(w.path)).sort(), ['glass.png', 'tank.png']);
});

test('finishGltf: materials carry GemRender alpha modes and TEXCOORD_0 only', () => {
	const out = ca.finishGltf(fixture(), options());
	const byName = Object.fromEntries(out.gltf.materials.filter(m => m.name).map(m => [m.name, m]));
	assert.equal(byName.tank.alphaMode, 'OPAQUE');
	assert.ok(!('alphaCutoff' in byName.tank));
	assert.ok(!('doubleSided' in byName.tank));
	assert.equal(byName.glass.alphaMode, 'BLEND');
	assert.equal(byName.glass.doubleSided, true);
	for (const m of out.gltf.materials) {
		if (m.alphaMode === 'MASK') assert.ok([0, 0.1, 0.5].includes(m.alphaCutoff));
		const t = m.pbrMetallicRoughness.baseColorTexture;
		if (t) assert.ok(!t.texCoord);
	}
	for (const mesh of out.gltf.meshes) {
		for (const p of mesh.primitives) assert.ok(!('TEXCOORD_1' in p.attributes));
	}
});

test('finishGltf: role extras and frame survive, 1 unit = 1 block', () => {
	const out = ca.finishGltf(fixture(), options());
	const node = name => out.gltf.nodes.find(n => n.name === name);
	assert.deepEqual(node('hull').extras, {combinedassaultRole: 'body'});
	assert.ok(!node('hull').translation && !node('hull').rotation);
	assert.equal(node('turret').extras.combinedassaultInterface, 'turret');
	assert.deepEqual(node('turret').translation, [0, 0.5, 0.25]);
	assert.equal(node('gun').extras.combinedassaultRole, 'pitch');
	assert.deepEqual(node('hull_col').extras.combinedassault, {main: true, name: 'hull'});
	assert.deepEqual(node('socket_seat_turret').extras,
		{combinedassaultRole: 'seat', combinedassaultInterface: 'turret', gemrenderHidden: true});
	assert.deepEqual(node('socket_seat_turret').translation, [0, 0.375, -0.125]);
	assert.ok(!out.gltf.nodes.some(n => n.name === 'player_reference' || n.name === 'head'));
	// hull_box: from [-8,0,-16] to [8,8,16] px.
	const box = out.gltf.meshes[node('hull_box').mesh].primitives[0].attributes.POSITION;
	assert.deepEqual(out.gltf.accessors[box].min, [-0.5, 0, -1]);
	assert.deepEqual(out.gltf.accessors[box].max, [0.5, 0.5, 1]);
	for (const name of ['hull_col', 'turret_col', 'engine']) {
		assert.ok(out.gltf.meshes[node(name).mesh].primitives.every(p => !('material' in p)), name);
	}
	const points = accessorFloats(out.gltf, out.bin, box);
	assert.ok(points.every(p => Math.abs(p[0]) <= 0.5 && p[1] >= 0 && p[1] <= 0.5 && Math.abs(p[2]) <= 1));
	// Clip "gun": translation key = rest [0, 0.125, 0.25] + 2 px up.
	const clip = out.gltf.animations.find(a => a.name === 'gun');
	const channel = clip.channels.find(c => c.target.path === 'translation');
	assert.equal(out.gltf.nodes[channel.target.node].name, 'gun');
	assert.deepEqual(accessorFloats(out.gltf, out.bin, clip.samplers[channel.sampler].output)[0], [0, 0.25, 0.25]);
});

test('finishGltf: hidden-face material pruned, indices remapped', () => {
	const raw = fixture();
	raw.images.push({mimeType: 'image/png', name: 'Unknown', uri: ''});
	raw.textures.push({sampler: 0, source: raw.images.length - 1});
	raw.materials.unshift({pbrMetallicRoughness: {baseColorTexture: {index: raw.textures.length - 1}}, alphaMode: 'MASK',
		alphaCutoff: 0.2});
	for (const mesh of raw.meshes) for (const p of mesh.primitives) p.material += 1;
	const lonely = raw.meshes.push({primitives: [{attributes: {POSITION: 0}, material: 0}]}) - 1;
	raw.nodes.push({name: 'only_hidden', mesh: lonely});
	raw.meshes[0].primitives.push({attributes: {POSITION: 0}, material: 0});
	const out = ca.finishGltf(raw, options());
	assert.ok(!out.gltf.images.some(i => i.name === 'Unknown'));
	assert.equal(out.gltf.images.length, 2);
	assert.ok(!('mesh' in out.gltf.nodes.find(n => n.name === 'only_hidden')));
	for (const mesh of out.gltf.meshes) {
		for (const p of mesh.primitives) assert.ok(p.material === undefined || p.material < out.gltf.materials.length);
	}
	for (const t of out.gltf.textures) assert.ok(t.source < out.gltf.images.length && t.sampler < out.gltf.samplers.length);
	const hullBox = out.gltf.meshes[out.gltf.nodes.find(n => n.name === 'hull_box').mesh];
	assert.equal(out.gltf.materials[hullBox.primitives[0].material].name, 'tank');
});

test('finishGltf: unknown texture name fails fast', () => {
	assert.throws(() => ca.finishGltf(fixture(), options({'tank.png': {data: PNG, alpha: 'OPAQUE'}})), /glass\.png/);
});

const HUMVEE = path.join(repo, 'src/main/resources/combinedassault_packs/combinedassault/assets/combinedassault/models/ground/humvee/humvee');

test('splitExtras -> mergeExtras returns every shipped humvee node extras verbatim', () => {
	const {gltf} = ca.readContainer(fs.readFileSync(HUMVEE + '.glb'));
	for (const node of gltf.nodes) {
		const extras = node.extras || {};
		assert.deepEqual(ca.mergeExtras(extras, ca.splitExtras(extras)), extras, node.name);
	}
});

test('mergeExtras: panel owns role keys, unknown keys pass through', () => {
	const raw = {combinedassaultRole: 'light', gemrenderHidden: true, genHulls: {node: 'body', locked: true},
		combinedassault: {kind: 'search', color: '#ffffff', index: 2}, custom: 1};
	assert.deepEqual(ca.mergeExtras(raw, {role: 'socket', field: 'light', index: 3, light_kind: 'search', light_color: '#ffffff'}), {
		combinedassaultRole: 'light', gemrenderHidden: true, genHulls: {node: 'body'}, custom: 1,
		combinedassault: {kind: 'search', color: '#ffffff', index: 3},
	});
	assert.deepEqual(ca.mergeExtras({combinedassault: {main: true, solid: true, name: 'x'}, combinedassaultRole: 'collider'},
		{role: 'none'}), {});
	assert.deepEqual(ca.mergeExtras({combinedassaultRole: 'collider', combinedassault: {solid: true}}, {role: 'collider', locked: true}),
		{combinedassaultRole: 'collider', gemrenderHidden: true, genHulls: {locked: true}, combinedassault: {solid: true}});
	assert.throws(() => ca.splitExtras({combinedassaultRole: 'wing_tip'}), /wing_tip/);
});

test('panel keys: light socket, unit / spin / road wheel / follows clips / hidden round trip', () => {
	const light = {combinedassaultRole: 'light', gemrenderHidden: true,
		combinedassault: {kind: 'search', color: '#eef4ff', range: 90, intensity: 3000, inner: 2, outer: 5, shadow: false, cookie: 'a:b', index: 1}};
	const props = ca.splitExtras(light);
	assert.deepEqual(props, {role: 'socket', field: 'light', index: 1, light_kind: 'search', light_color: '#eef4ff', light_range: 90,
		light_intensity: 3000, light_inner: 2, light_outer: 5, light_shadow: false, light_cookie: 'a:b'});
	assert.deepEqual(ca.nodeExtras(props), light);
	assert.deepEqual(ca.nodeExtras({role: 'socket', field: 'light'}),
		{combinedassaultRole: 'light', gemrenderHidden: true, combinedassault: {kind: 'head'}});
	const wheel = {combinedassault: {road_wheel: true, unit: 'FL', spin: 'cw'}, gemrenderHidden: true};
	assert.deepEqual(ca.splitExtras(wheel), {role: 'none', hidden: true, road_wheel: true, unit: 'FL', spin: 'cw'});
	assert.deepEqual(ca.nodeExtras(ca.splitExtras(wheel)), wheel);
	assert.deepEqual(ca.mergeExtras(wheel, {role: 'none'}), {});
	const frame = {combinedassaultRole: 'part', combinedassaultInterface: 'door', combinedassault: {follows_clips: true, unit: 'R1'}};
	assert.deepEqual(ca.nodeExtras(ca.splitExtras(frame)), frame);
	assert.ok(!('road_wheel' in (ca.nodeExtras({role: 'part', interface: 'x', road_wheel: true}).combinedassault || {})));
});

test('quatToEuler matches three.js decompositions', () => {
	const q = (axis, deg) => {
		const h = deg * Math.PI / 360;
		const out = [0, 0, 0, Math.cos(h)];
		out[axis] = Math.sin(h);
		return out;
	};
	const close = (a, b) => a.forEach((v, i) => assert.ok(Math.abs(v - b[i]) < 1e-9, `${a} vs ${b}`));
	close(ca.quatToEuler(q(1, 30), 'ZYX'), [0, 30, 0]);
	close(ca.quatToEuler(q(0, -60), 'XYZ'), [-60, 0, 0]);
	// Rz(20) * Ry(10) * Rx(5) for ZYX.
	const mul = (a, b) => [a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1], a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
		a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3], a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]];
	close(ca.quatToEuler(mul(mul(q(2, 20), q(1, 10)), q(0, 5)), 'ZYX'), [5, 10, 20]);
	close(ca.quatToEuler(mul(mul(q(0, 5), q(1, 10)), q(2, 20)), 'XYZ'), [5, 10, 20]);
});

test('decompose: matrix -> TRS, mirror kept in s[0]', () => {
	const c = Math.cos(0.5), s = Math.sin(0.5);
	const d = ca.decompose([2 * c, 2 * s, 0, 0, -3 * s, 3 * c, 0, 0, 0, 0, -4, 0, 1, 2, 3, 1]);
	assert.deepEqual(d.t, [1, 2, 3]);
	assert.ok(Math.abs(Math.abs(d.s[0]) - 2) < 1e-12 && Math.abs(d.s[1] - 3) < 1e-12 && Math.abs(d.s[2] - 4) < 1e-12);
	assert.ok(d.s[0] * d.s[1] * d.s[2] < 0);
});

test('hull: polygon loops, plane groups, game refusals', () => {
	const cube = [[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]];
	const quads = [[0, 3, 2, 1], [4, 5, 6, 7], [0, 1, 5, 4], [2, 3, 7, 6], [1, 2, 6, 5], [0, 4, 7, 3]];
	const fan = q => [q[0], q[1], q[2], q[0], q[2], q[3]];
	const loops = ca.polygonLoops(cube, quads.map(fan));
	assert.equal(loops.length, 6);
	assert.deepEqual(ca.hullProblems(cube, loops), []);
	assert.equal(ca.planeGroups(cube, quads.flatMap(fan)).length, 6);
	const dented = cube.map(p => p.slice());
	dented[6] = [0.5, 0.5, 0.5];
	assert.match(ca.hullProblems(dented, ca.polygonLoops(dented, quads.map(fan))).join(), /bent|not convex/);
	assert.match(ca.hullProblems(cube, loops.slice(0, 3)).join(), /4 faces/);
	const welded = ca.weld([[0, 0, 0], [0.00005, 0, 0], [1, 0, 0]], 1e-4);
	assert.deepEqual(welded.index, [0, 0, 1]);
});

test('planImport: shipped humvee', () => {
	const {gltf, bin} = ca.readContainer(fs.readFileSync(HUMVEE + '.glb'));
	const plan = ca.planImport(gltf, [bin], {frame: 'ZYX', mesh: 'XYZ'});
	assert.deepEqual(plan.warnings, []);
	const entry = name => plan.entries.find(e => e.name === name);
	assert.equal(entry('humvee').kind, 'group');
	assert.equal(entry('door_fl').kind, 'group');
	assert.equal(entry('door_fl_mesh').kind, 'mesh');
	assert.equal(entry('door_fl_mesh').parent, plan.entries.indexOf(entry('door_fl')));
	assert.deepEqual(entry('door_fl').origin.map(v => +v.toFixed(4)), [17.136, 18.288, 14.8032]);
	assert.equal(entry('roof_mount_pitch').kind, 'group');
	assert.equal(entry('socket_seat_driver').kind, 'locator');
	assert.deepEqual(ca.nodeExtras(entry('socket_seat_driver').props), gltf.nodes.find(n => n.name === 'socket_seat_driver').extras);
	// Collider: one Blockbench face tag per glTF primitive.
	const node = gltf.nodes.find(n => n.name === 'collider_door_fl_9');
	const collider = entry('collider_door_fl_9');
	assert.equal(new Set(collider.mesh.faces.map(f => f.tag)).size, gltf.meshes[node.mesh].primitives.length);
	assert.ok(collider.mesh.faces.every(f => f.vertices.length <= 4 && !f.uv));
	const material = plan.materials[0];
	assert.equal(material.location, 'combinedassault:textures/ground/humvee/humvee.png');
	assert.deepEqual([material.kept.name, material.kept.alphaMode, material.kept.alphaCutoff, material.kept.doubleSided],
		['paint', 'MASK', 0.5, false]);
	const door = plan.clips.find(c => c.name === 'door_fl');
	assert.equal(door.channels[0].channel, 'rotation');
	const clip = gltf.animations.find(a => a.name === 'door_fl');
	const sampler = clip.samplers[clip.channels[0].sampler];
	const quats = ca.readAccessor(gltf, [bin], sampler.output);
	const [, qy, , qw] = quats[quats.length - 1];
	const keys = door.channels[0].keys;
	assert.equal(keys.length, quats.length);
	assert.ok(Math.abs(keys[keys.length - 1].value[1] - 2 * Math.atan2(qy, qw) * 180 / Math.PI) < 1e-3, JSON.stringify(keys.at(-1)));
	const faces = plan.entries.filter(e => e.mesh && e.props.role !== 'collider').reduce((n, e) => n + e.mesh.faces.length, 0);
	const triangles = gltf.nodes.filter(n => n.mesh !== undefined && (n.extras || {}).combinedassaultRole !== 'collider')
		.reduce((n, x) => n + gltf.meshes[x.mesh].primitives.reduce((m, p) => m + gltf.accessors[p.indices].count / 3, 0), 0);
	assert.equal(faces, triangles);
});

test('finishGltf: collider rewritten one primitive per face, shared POSITION, buffer repacked', () => {
	const raw = fixture();
	const node = raw.nodes.find(n => n.name === 'turret_col');
	node.extras.combinedassaultUuid = 'u1';
	const points = [[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]];
	const loops = [[0, 3, 2, 1], [4, 5, 6, 7], [0, 1, 5, 4], [2, 3, 7, 6], [1, 2, 6, 5], [0, 4, 7, 3]];
	const out = ca.finishGltf(raw, Object.assign(options(), {polygons: {u1: {points, loops}}}));
	const outNode = out.gltf.nodes.find(n => n.name === 'turret_col');
	assert.ok(!('combinedassaultUuid' in outNode.extras));
	const prims = out.gltf.meshes[outNode.mesh].primitives;
	assert.equal(prims.length, 6);
	assert.ok(prims.every(p => p.attributes.POSITION === prims[0].attributes.POSITION && !('material' in p)
		&& Object.keys(p.attributes).length === 1));
	assert.deepEqual(accessorFloats(out.gltf, out.bin, prims[0].attributes.POSITION), points);
	const used = new Set(out.gltf.meshes.flatMap(m => m.primitives.flatMap(p => [...Object.values(p.attributes), p.indices])));
	for (const animation of out.gltf.animations) for (const sampler of animation.samplers) used.add(sampler.input).add(sampler.output);
	used.delete(undefined);
	assert.equal(out.gltf.accessors.length, used.size);
	for (const view of out.gltf.bufferViews) assert.equal(view.byteOffset % 4, 0);
	assert.equal(out.gltf.buffers[0].byteLength, out.bin.length);
});

test('finishGltf: Mesh element primitives per (texture, kept material), extra maps restored', () => {
	const source = {name: 'paint', alphaMode: 'MASK', alphaCutoff: 0.5, emissiveFactor: [1, 1, 1],
		pbrMetallicRoughness: {baseColorTexture: {location: 'x:textures/a.png'}, metallicFactor: 0.2, roughnessFactor: 0.7,
			metallicRoughnessTexture: {location: 'x:textures/a_mr.png'}},
		normalTexture: {location: 'x:textures/a_n.png', scale: 0.5}};
	const kept = {name: 'paint', alphaMode: 'MASK', alphaCutoff: 0.5, doubleSided: false, source};
	const raw = fixture();
	raw.nodes.find(n => n.name === 'hull_box').extras = {combinedassaultUuid: 'm1'};
	const tri = {positions: [0, 0, 0, 1, 0, 0, 0, 1, 0], normals: [0, 0, 1, 0, 0, 1, 0, 0, 1], uvs: [0, 0, 1, 0, 0, 1], indices: [0, 1, 2]};
	const opts = Object.assign(options(), {meshes: {m1: [
		Object.assign({texture: 'tank.png', material: kept}, tri),
		Object.assign({texture: 'tank.png', material: Object.assign({}, kept, {name: 'paint_armor_40'})}, tri),
		Object.assign({texture: 'tank.png', material: null}, tri),
	]}});
	const out = ca.finishGltf(raw, opts);
	const node = out.gltf.nodes.find(n => n.name === 'hull_box');
	assert.ok(!node.extras);
	const prims = out.gltf.meshes[node.mesh].primitives;
	assert.equal(prims.length, 3);
	const mats = prims.map(p => out.gltf.materials[p.material]);
	assert.deepEqual(mats.map(m => m.name), ['paint', 'paint_armor_40', 'tank']);
	const m = mats[0];
	assert.deepEqual([m.alphaMode, m.alphaCutoff, m.doubleSided], ['MASK', 0.5, undefined]);
	assert.equal(m.pbrMetallicRoughness.metallicFactor, 0.2);
	assert.deepEqual(m.emissiveFactor, [1, 1, 1]);
	const image = slot => out.gltf.images[out.gltf.textures[slot.index].source].extras.resourceLocation;
	assert.equal(image(m.pbrMetallicRoughness.baseColorTexture), 'combinedassault:textures/test/tank.png');
	assert.equal(image(m.pbrMetallicRoughness.metallicRoughnessTexture), 'x:textures/a_mr.png');
	assert.equal(image(m.normalTexture), 'x:textures/a_n.png');
	assert.equal(m.normalTexture.scale, 0.5);
	assert.equal(mats[2].alphaMode, 'OPAQUE');
	assert.deepEqual(accessorFloats(out.gltf, out.bin, prims[0].attributes.TEXCOORD_0), [[0, 0], [1, 0], [0, 1]]);
	assert.ok(!JSON.stringify(out.gltf).includes('"location"'));
});

test('viewAngle looks along forward from the eye', () => {
	const v = ca.viewAngle([8, 24, -4], [0, 0, 1]);
	assert.deepEqual(v.position, [8, 24, -4]);
	assert.deepEqual(v.target, [8, 24, 12]);
	assert.deepEqual(ca.VIEW_FIELDS, ['seat', 'view', 'sight', 'camera', 'camera_zoomed']);
});

test('sparse accessor overrides base rows', () => {
	const bin = Buffer.alloc(64);
	[1, 2, 3, 4, 5, 6].forEach((v, i) => bin.writeFloatLE(v, i * 4));
	bin.writeUInt16LE(1, 24);
	[9, 9, 9].forEach((v, i) => bin.writeFloatLE(v, 28 + i * 4));
	const gltf = {
		bufferViews: [{buffer: 0, byteOffset: 0, byteLength: 24}, {buffer: 0, byteOffset: 24, byteLength: 2}, {buffer: 0, byteOffset: 28, byteLength: 12}],
		accessors: [{bufferView: 0, componentType: 5126, count: 2, type: 'VEC3',
			sparse: {count: 1, indices: {bufferView: 1, componentType: 5123}, values: {bufferView: 2}}}],
	};
	assert.deepEqual(ca.readAccessor(gltf, [bin], 0), [[1, 2, 3], [9, 9, 9]]);
});

const PACK = path.join(repo, 'src/main/resources/combinedassault_packs/combinedassault');
const KITS = path.join(PACK, 'combinedassault/combinedassault/hardware');
const javaSet = (file, name) => {
	const body = fs.readFileSync(path.join(repo, 'src/main/java/com/wf/combinedassault', file), 'utf8')
		.match(new RegExp(`${name} = (?:Set|EnumSet)\\.of\\(([^;]*)\\);`))[1];
	return [...body.matchAll(/"([a-z_]+)"|NodeRole\.([A-Z_]+)/g)].map(m => m[1] || m[2].toLowerCase()).sort();
};

test('kit vocabularies equal the Java sources and tools/blender', () => {
	assert.deepEqual([...ca.KIT_ROLES].sort(), javaSet('ballistics/hit/ModelDataManager.java', 'KIT_ROLES'));
	assert.deepEqual([...ca.STATION_KEYS].sort(), javaSet('weapon/kit/HardwareKits.java', 'STATION_KEYS'));
	const py = fs.readFileSync(path.join(repo, 'tools/blender/ca_validate.py'), 'utf8');
	assert.deepEqual([...ca.KIT_ROLES].sort(), pyList(py, 'KIT_ROLES').sort());
	assert.deepEqual([...ca.STATION_KEYS].sort(), pyList(py, 'STATION_KEYS').sort());
	assert.deepEqual(ca.HANDS, py.match(/^HANDS = \(([^)]*)\)/m)[1].match(/[a-z]+/g));
});

test('hand socket: extras round trip, validation', () => {
	const props = {role: 'socket', field: 'hand', hand: 'left', action: 'fire'};
	const extras = {combinedassaultRole: 'hand', gemrenderHidden: true, combinedassault: {hand: 'left', action: 'fire'}};
	assert.deepEqual(ca.nodeExtras(props), extras);
	assert.deepEqual(ca.splitExtras(extras), props);
	assert.deepEqual(ca.mergeExtras(extras, ca.splitExtras(extras)), extras);
	assert.deepEqual(ca.nodeExtras({role: 'socket', field: 'hand', interface: 'driver'}),
		{combinedassaultRole: 'hand', combinedassaultInterface: 'driver', gemrenderHidden: true, combinedassault: {hand: 'right'}});
	assert.deepEqual(ca.limbProblems({hand: 'right', action: 'shift'}, 'hand'), []);
	assert.match(ca.limbProblems({}, 'hand').join(), /right \/ left/);
	assert.match(ca.limbProblems({hand: 'middle'}, 'hand').join(), /middle/);
	assert.match(ca.limbProblems({hand: 'left', action: 3}, 'hand').join(), /action/);
	assert.match(ca.limbProblems({hand: 'left', action: 'brake'}, 'hand').join(), /brake/);
});

test('foot socket: extras round trip, validation', () => {
	const props = {role: 'socket', field: 'foot', foot: 'left', action: 'brake'};
	const extras = {combinedassaultRole: 'foot', gemrenderHidden: true, combinedassault: {foot: 'left', action: 'brake'}};
	assert.deepEqual(ca.nodeExtras(props), extras);
	assert.deepEqual(ca.splitExtras(extras), props);
	assert.deepEqual(ca.mergeExtras(extras, ca.splitExtras(extras)), extras);
	assert.deepEqual(ca.nodeExtras({role: 'socket', field: 'foot', interface: 'driver'}),
		{combinedassaultRole: 'foot', combinedassaultInterface: 'driver', gemrenderHidden: true, combinedassault: {foot: 'right'}});
	assert.deepEqual(ca.limbProblems({foot: 'right', action: 'brake'}, 'foot'), []);
	assert.match(ca.limbProblems({}, 'foot').join(), /right \/ left/);
	assert.match(ca.limbProblems({foot: 'middle'}, 'foot').join(), /middle/);
	assert.match(ca.limbProblems({foot: 'left', action: 3}, 'foot').join(), /action/);
	assert.match(ca.limbProblems({foot: 'left', action: 'shift'}, 'foot').join(), /shift/);
	assert.equal(ca.kitRoleProblem('foot') === null, false);
});

test('kit role restriction', () => {
	for (const role of ca.KIT_ROLES) assert.equal(ca.kitRoleProblem(role), null);
	assert.equal(ca.kitRoleProblem(''), null);
	assert.match(ca.kitRoleProblem('collider'), /kit model/);
	assert.match(ca.kitRoleProblem('light'), /kit model/);
	const {gltf} = ca.readContainer(fs.readFileSync(path.join(PACK, 'assets/combinedassault/models/weapon/m2/m2.glb')));
	assert.deepEqual(gltf.nodes.map(n => ca.kitRoleProblem((n.extras || {}).combinedassaultRole || '')).filter(Boolean), []);
});

test('belt sockets: indices 0 + 1, kit model only, kit belt agrees with the model', () => {
	assert.deepEqual(ca.beltProblems([], true), {errors: [], warnings: []});
	assert.deepEqual(ca.beltProblems([1, 0], true), {errors: [], warnings: []});
	for (const bad of [[0], [0, 0], [0, 2], [undefined, 1]]) assert.match(ca.beltProblems(bad, true).errors.join(), /belt sockets/, JSON.stringify(bad));
	assert.match(ca.beltProblems([0, 1], false).warnings.join(), /outside a kit/);
	const belt = {link: 'ma_lmgs:models/link.glb', pitch: 0.012};
	assert.deepEqual(ca.kitBeltProblems({belt}, true), []);
	assert.match(ca.kitBeltProblems({}, true).join(), /kit belt unset/);
	assert.match(ca.kitBeltProblems({belt}, false).join(), /no belt sockets/);
	assert.match(ca.kitProblems({model: 'a:b', weapons: ['a:b'], fits: ['door'], belt: {...belt, pitch: 0}}).join(), /belt/);
	const text = '{\n  "model": "a:b",\n  "belt": {"link": "a:l", "pitch": 0.01}\n}\n';
	assert.ok(!('belt' in JSON.parse(ca.editKitText(text, {belt: null}))));
	assert.equal(ca.editKitText(text, {belt: {link: 'a:l', pitch: 0.01}}), text);
	for (const name of ['ma_pkm', 'ma_m240b', 'ma_m249']) {
		const kit = JSON.parse(fs.readFileSync(path.join(KITS, `${name}.json`), 'utf8'));
		const {gltf} = ca.readContainer(fs.readFileSync(path.join(PACK, `assets/combinedassault/models/weapon/ma/${name}.glb`)));
		const indices = gltf.nodes.filter(n => (n.extras || {}).combinedassaultRole === 'belt').map(n => n.extras.combinedassault.index);
		assert.deepEqual(ca.beltProblems(indices, true).errors, [], name);
		assert.deepEqual(ca.kitBeltProblems(kit, indices.length > 0), [], name);
		assert.deepEqual(gltf.nodes.map(n => ca.kitRoleProblem((n.extras || {}).combinedassaultRole || '')).filter(Boolean), [], name);
	}
});

test('kit json: path, problems, in-place edit round trip on a build/ copy', () => {
	assert.deepEqual(ca.kitOf('/p/pack/combinedassault/wf/hardware/guns/m2.json'), {packRoot: '/p/pack', id: 'wf:guns/m2'});
	assert.equal(ca.kitOf('/p/pack/assets/wf/models/m2.json'), null);
	for (const name of fs.readdirSync(KITS)) {
		const text = fs.readFileSync(path.join(KITS, name), 'utf8');
		const kit = JSON.parse(text);
		assert.deepEqual(ca.kitProblems(kit), [], name);
		const same = {weapons: kit.weapons, fits: kit.fits, install_ticks: kit.install_ticks, item: kit.item || '',
			replaces: kit.replaces || '', station: kit.station || {}};
		assert.equal(ca.editKitText(text, same), text, name);
	}
	assert.match(ca.kitProblems({model: 'a:b', weapons: [], fits: ['x'], station: {laser: 1}}).join(), /weapons.*laser/);
	const scratch = path.join(repo, 'build/test_combinedassault_kit_bb');
	fs.mkdirSync(scratch, {recursive: true});
	const copy = path.join(scratch, 'm2.json');
	fs.copyFileSync(path.join(KITS, 'm2.json'), copy);
	const original = fs.readFileSync(copy, 'utf8');
	fs.writeFileSync(copy, ca.editKitText(original, {install_ticks: 90, item: '', station: JSON.parse(original).station}));
	assert.equal(fs.readFileSync(copy, 'utf8'), original.replace('"install_ticks": 120', '"install_ticks": 90'));
	fs.rmSync(scratch, {recursive: true});
	const spg9 = fs.readFileSync(path.join(KITS, 'spg9.json'), 'utf8');
	const at = spg9.lastIndexOf('}', spg9.lastIndexOf('}') - 1) + 1;
	assert.equal(ca.editKitText(spg9, {replaces: 'combinedassault:old'}),
		spg9.slice(0, at) + ',\n  "replaces": "combinedassault:old"' + spg9.slice(at));
	const pkm = fs.readFileSync(path.join(KITS, 'ma_pkm.json'), 'utf8');
	const stripped = ca.editKitText(pkm, {item: '', replaces: ''});
	assert.deepEqual(Object.keys(JSON.parse(stripped)), ['model', 'weapons', 'fits', 'install_ticks', 'belt', 'station']);
	assert.equal(ca.editKitText(stripped, {item: 'ma_lmgs:pkm'}), stripped.replace(/\n}\s*$/, ',\n  "item": "ma_lmgs:pkm"\n}\n'));
});

test('kit hardpoints: humvee roof, node at the kit origin in the preview frame', () => {
	const load = location => {
		const file = path.join(PACK, 'assets', ...location.split(':'));
		if (!fs.existsSync(file)) return null;
		const {gltf, bin} = ca.readContainer(fs.readFileSync(file));
		return {gltf, buffers: [bin]};
	};
	const dir = path.join(PACK, 'combinedassault/combinedassault/vehicles');
	const vehicles = fs.readdirSync(dir).map(n => ({id: `combinedassault:${n.slice(0, -5)}`, def: JSON.parse(fs.readFileSync(path.join(dir, n), 'utf8'))}));
	const points = ca.kitHardpoints(vehicles, 'combinedassault:m2', ['pintle', 'tripod', 'door']);
	const roof = points.find(p => p.vehicle === 'combinedassault:humvee');
	assert.equal(roof.node, 'roof_mount_pitch');
	assert.ok(!points.some(p => p.vehicle === 'combinedassault:mavic'));
	const world = ca.hardpointWorld(roof.def, roof.node, l => (load(l) || {}).gltf || null);
	assert.ok(Math.abs(world[1][3] - 2.3517) < 1e-3 && Math.abs(world[2][3] - 0.7821) < 1e-3, JSON.stringify(world));
	const at = ca.mat4Mul(ca.rigidInverse(world), world);
	assert.ok(at.every((row, r) => row.every((v, c) => Math.abs(v - (r === c ? 1 : 0)) < 1e-9)));
	const {positions, seats} = ca.previewTriangles(roof.def, ca.rigidInverse(world), load);
	assert.ok(positions.length > 0 && positions.length % 9 === 0);
	assert.equal(seats.length, 5);
	const override = ca.kitHardpoints([{id: 'v', def: {parts: [{id: 'p', hardpoint: {node: 'a', accepts: ['door'],
		overrides: {'combinedassault:m2': {node: 'b'}}}}]}}], 'combinedassault:m2', ['door']);
	assert.equal(override[0].node, 'b');
});

if (failures) {
	console.log(`${failures} failed`);
	process.exit(1);
}
console.log('all passed');

test('convexHull: cube corners + interior point -> 6 quads; sphere-ish keeps all', () => {
	const corners = [];
	for (const x of [0, 2]) for (const y of [0, 3]) for (const z of [0, 4]) corners.push([x, y, z]);
	const hull = ca.convexHull([...corners, [1, 1, 1], [1, 1.5, 2]]);
	assert.equal(hull.points.length, 8);
	assert.equal(hull.loops.length, 6);
	assert.ok(hull.loops.every(l => l.length === 4));
	assert.deepEqual(ca.hullProblems(hull.points, hull.loops), []);
	const octa = ca.convexHull([[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]);
	assert.equal(octa.loops.length, 8);
	assert.deepEqual(ca.hullProblems(octa.points, octa.loops), []);
	assert.throws(() => ca.convexHull([[0, 0, 0], [1, 0, 0], [0, 1, 0], [1, 1, 0]]), /flat/);
});

test('prismAround: thinnest axis, circumscribed, valid hull', () => {
	const tyre = [];
	for (const x of [-1, 1]) for (const y of [-3, 3]) for (const z of [-3, 3]) tyre.push([x, y, z]);
	const prism = ca.prismAround(tyre, 12);
	assert.equal(prism.points.length, 24);
	assert.equal(prism.loops.length, 14);
	assert.ok(prism.points.slice(0, 12).every(p => p[0] === -1));
	const radius = Math.hypot(...[prism.points[0][1], prism.points[0][2]]);
	assert.ok(Math.abs(radius - Math.hypot(3, 3) / Math.cos(Math.PI / 12)) < 1e-9 || radius >= Math.hypot(3, 3));
	assert.deepEqual(ca.hullProblems(prism.points, prism.loops), []);
});

test('coplanarGroups: triangulated box bottom -> one plane', () => {
	const bottom = [[[0, 0, 0], [1, 0, 0], [1, 0, 1]], [[0, 0, 0], [1, 0, 1], [0, 0, 1]], [[0, 1, 0], [1, 1, 0], [1, 1, 1]]];
	const ids = ca.coplanarGroups(bottom);
	assert.equal(ids[0], ids[1]);
	assert.notEqual(ids[0], ids[2]);
});

test('material tags mirror the Blender add-on', () => {
	assert.equal(ca.taggedName('hull_armor_50', 'ufp', 'steel', false), 'hull_armor:ufp');
	assert.equal(ca.taggedName('hull_armor:ufp', 'lfp', 'steel', false), 'hull_armor:lfp');
	assert.equal(ca.taggedName('tyre', '', 'rubber', false), 'tyre_rubber');
	assert.equal(ca.taggedName('hull_armor:ufp_rubber', '', 'steel', true), 'hull_decor');
	assert.equal(ca.stripTags('a_rubber_glass_armor_5'), 'a');
	assert.deepEqual(ca.materialTags('seat_fabric_armor:side_1'), {plate: 'side_1', surface: 'soft', decor: false});
	assert.deepEqual(ca.materialTags('paint_armor:ufp.001'), {plate: 'ufp', surface: 'steel', decor: false});
	assert.deepEqual(ca.materialTags('hull_armor_80'), {plate: '', surface: 'steel', decor: false});
	assert.deepEqual(ca.materialTags('plain'), {plate: '', surface: 'steel', decor: false});
	assert.equal(ca.taggedName('', '', 'steel', false), 'material');
	assert.deepEqual(ca.plateProblems('paint_armor:ufp'), []);
	assert.match(ca.plateProblems('hull_armor_80').join(), /no longer read/);
	assert.match(ca.plateProblems('hull_armor:u-p').join(), /unbound/);
	const java = fs.readFileSync(path.join(repo, 'src/main/java/com/wf/combinedassault/ballistics/hit/ModelDataManager.java'), 'utf8');
	assert.equal(java.match(/PLATE = Pattern\.compile\("(.*?)"\)/)[1].replace(/\\\\/g, '\\'), ca.PLATE.source);
});

test('scale-valued sockets', () => {
	assert.deepEqual(ca.scaleForValue('bolt', 1.5), [1, 1, 1.5]);
	assert.deepEqual(ca.scaleForValue('propeller', 2), [2, 2, 2]);
	assert.equal(ca.valueOfScale('station', [1, 1, 0.4]), 0.4);
	assert.equal(ca.valueOfScale('cargo_bay', [2, 2, 2]), 2);
	assert.equal(ca.valueOfScale('bolt', undefined), 1);
});
