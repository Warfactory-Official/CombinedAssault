// node tools/blockbench/compare_roundtrip.mjs <original.gltf> <original.bin> <exported.gltf>
// Exported model vs its source after Import -> Export: node extras, world transforms, images, collider faces,
// drawn triangles, clips.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';

const ca = createRequire(import.meta.url)('./combinedassault.js');
const [originalPath, originalBin, exportedPath] = process.argv.slice(2);
const a = JSON.parse(fs.readFileSync(originalPath, 'utf8'));
const b = JSON.parse(fs.readFileSync(exportedPath, 'utf8'));
const binA = [fs.readFileSync(originalBin)];
const binB = [fs.readFileSync(exportedPath.replace(/\.gltf$/, '.bin'))];

function worlds(gltf) {
	const out = new Map();
	const local = node => {
		const {t, q, s} = node.matrix ? ca.decompose(node.matrix) : {t: node.translation || [0, 0, 0],
			q: node.rotation || [0, 0, 0, 1], s: node.scale || [1, 1, 1]};
		const [x, y, z, w] = q;
		const r = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
			[2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
			[2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]];
		return [0, 1, 2].map(i => [...[0, 1, 2].map(j => r[i][j] * s[j]), t[i]]).concat([[0, 0, 0, 1]]);
	};
	const mul = (p, c) => p.map((row, i) => [0, 1, 2, 3].map(j => row.reduce((sum, v, k) => sum + v * c[k][j], 0)));
	const visit = (i, parent) => {
		const m = mul(parent, local(gltf.nodes[i]));
		out.set(i, m);
		for (const c of gltf.nodes[i].children || []) visit(c, m);
	};
	const id = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]];
	for (const r of gltf.scenes[gltf.scene || 0].nodes) visit(r, id);
	return out;
}

const byName = gltf => new Map(gltf.nodes.map((n, i) => [n.name, i]));
const namesA = byName(a);
const namesB = byName(b);
const wa = worlds(a);
const wb = worlds(b);
let drift = 0;
const mirrored = [];
let worst = '';
const problems = [];
for (const [name, i] of namesA) {
	if (!namesB.has(name)) {
		problems.push(`node ${name} missing`);
		continue;
	}
	const j = namesB.get(name);
	try {
		assert.deepEqual(b.nodes[j].extras || {}, a.nodes[i].extras || {});
	} catch {
		problems.push(`node ${name} extras ${JSON.stringify(a.nodes[i].extras)} -> ${JSON.stringify(b.nodes[j].extras)}`);
	}
	// Groups carry no scale in Blockbench: compare position + normalised axes.
	const det = m => m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
		+ m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
	const unit = m => [0, 1, 2].map(r => [0, 1, 2].map(c => m[r][c] / Math.hypot(m[0][c], m[1][c], m[2][c])).concat(m[r][3]));
	// Mirror is baked into the vertices: such nodes are judged by their triangles only.
	if (det(wa.get(i)) < 0) {
		mirrored.push(name);
		continue;
	}
	const d = Math.max(...unit(wa.get(i)).flat().map((v, k) => Math.abs(v - unit(wb.get(j)).flat()[k])));
	if (d > drift) {
		drift = d;
		worst = name;
	}
}
const added = [...namesB.keys()].filter(n => !namesA.has(n));

const locations = g => (g.images || []).map(i => i.extras && i.extras.resourceLocation).sort();
try {
	assert.deepEqual([...new Set(locations(b))], [...new Set(locations(a))]);
} catch {
	problems.push(`images ${locations(a)} -> ${locations(b)}`);
}

// Collider faces: same welded corner sets per face (order-free), world space.
const colliderFaces = (gltf, bins, world, i) => {
	const mesh = gltf.meshes[gltf.nodes[i].mesh];
	const m = world.get(i);
	const toWorld = q => [0, 1, 2].map(r => m[r][0] * q[0] + m[r][1] * q[1] + m[r][2] * q[2] + m[r][3]);
	const groups = mesh.primitives.map(p => {
		const pos = ca.readAccessor(gltf, bins, p.attributes.POSITION).map(toWorld);
		const idx = p.indices !== undefined ? ca.readAccessor(gltf, bins, p.indices).map(r => r[0]) : [...pos.keys()];
		return idx.map(k => pos[k]);
	});
	// One primitive: the game merges its triangles by plane.
	const faces = groups.length === 1
		? ca.planeGroups(groups[0], [...groups[0].keys()]).map(g => g.map(k => groups[0][k])) : groups;
	return faces.map(f => [...new Set(f.map(c => c.map(v => v.toFixed(3)).join(',')))].sort().join(' ')).sort();
};
let colliders = 0;
for (const [name, i] of namesA) {
	if ((a.nodes[i].extras || {}).combinedassaultRole !== 'collider' || !namesB.has(name)) continue;
	colliders++;
	const fa = colliderFaces(a, binA, wa, i);
	const fb = colliderFaces(b, binB, wb, namesB.get(name));
	if (fa.length !== fb.length) problems.push(`collider ${name}: ${fa.length} faces -> ${fb.length}`);
	else if (fa.some((f, k) => f !== fb[k])) problems.push(`collider ${name}: face corners differ`);
}

// Drawn triangles (degenerate dropped): world corners, UV, winding, material settings; matched within 1e-4.
const TOL = 1e-4;
const drawn = (gltf, bins, world, i) => {
	const m = world.get(i);
	// Negative determinant: the renderer flips the winding.
	const mirrored = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
		+ m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]) < 0;
	const tris = [];
	for (const p of gltf.meshes[gltf.nodes[i].mesh].primitives) {
		const pos = ca.readAccessor(gltf, bins, p.attributes.POSITION);
		const uv = p.attributes.TEXCOORD_0 !== undefined ? ca.readAccessor(gltf, bins, p.attributes.TEXCOORD_0) : null;
		const idx = p.indices !== undefined ? ca.readAccessor(gltf, bins, p.indices).map(r => r[0]) : [...pos.keys()];
		const mat = gltf.materials[p.material] || {};
		const mode = mat.alphaMode || 'OPAQUE';
		const tag = `${mode}/${mode === 'MASK' ? mat.alphaCutoff ?? 0.5 : ''}/${!!mat.doubleSided}/${mat.name}`;
		for (let k = 0; k + 2 < idx.length; k += 3) {
			const tri = idx.slice(k, k + 3);
			if (mirrored) tri.reverse();
			const corners = tri.map(v => [0, 1, 2].map(r => m[r][0] * pos[v][0]
				+ m[r][1] * pos[v][1] + m[r][2] * pos[v][2] + m[r][3]).concat(uv ? uv[v] : []));
			// Coincident corners: float32 rounding of a scaled position can collapse a near-zero sliver.
			const near = (p, q) => Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]) < 1e-6;
			if (near(corners[0], corners[1]) || near(corners[1], corners[2]) || near(corners[0], corners[2])) continue;
			tris.push({tag, corners});
		}
	}
	return tris;
};
const bucket = t => [0, 1, 2].map(j => Math.round((t.corners[0][j] + t.corners[1][j] + t.corners[2][j]) / 3 * 100)).join(',');
const same = (x, y) => x.tag === y.tag && [0, 1, 2].some(r => x.corners.every((c, k) =>
	c.every((v, j) => Math.abs(v - y.corners[(k + r) % 3][j]) < TOL)));
let triangles = 0;
for (const [name, i] of namesA) {
	const role = (a.nodes[i].extras || {}).combinedassaultRole;
	if (a.nodes[i].mesh === undefined || role === 'collider') continue;
	const j = namesB.get(b.nodes[namesB.get(name)].mesh === undefined ? `${name}_mesh` : name);
	const ta = drawn(a, binA, wa, i);
	const tb = drawn(b, binB, wb, j);
	triangles += ta.length;
	if (ta.length !== tb.length) {
		problems.push(`mesh ${name}: ${ta.length} triangles -> ${tb.length}`);
		continue;
	}
	const buckets = new Map();
	for (const t of tb) {
		const key = bucket(t);
		if (!buckets.has(key)) buckets.set(key, []);
		buckets.get(key).push(t);
	}
	const near = t => {
		const [x, y, z] = bucket(t).split(',').map(Number);
		for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) {
			const list = buckets.get(`${x + dx},${y + dy},${z + dz}`) || [];
			const at = list.findIndex(u => same(t, u));
			if (at >= 0) return list.splice(at, 1)[0];
		}
		return null;
	};
	const missing = ta.filter(t => !near(t));
	if (missing.length) problems.push(`mesh ${name}: ${missing.length} triangles unmatched, e.g. ${JSON.stringify(missing[0])}`);
}

const clips = g => (g.animations || []).map(c => `${c.name}:${c.channels.map(ch => g.nodes[ch.target.node].name + '.' + ch.target.path).sort()}`).sort();
try {
	assert.deepEqual(clips(b), clips(a));
} catch {
	problems.push(`clips ${clips(a)} -> ${clips(b)}`);
}
// Clip end poses: world matrix of each target at the last key.
for (const clip of a.animations || []) {
	const other = (b.animations || []).find(c => c.name === clip.name);
	if (!other) continue;
	for (const ch of clip.channels) {
		const name = a.nodes[ch.target.node].name;
		const chB = other.channels.find(c => b.nodes[c.target.node].name === name && c.target.path === ch.target.path);
		if (!chB) continue;
		const last = (g, bins, c, s) => ca.readAccessor(g, bins, s.samplers[c.sampler].output).pop();
		const va = last(a, binA, ch, clip);
		const vb = last(b, binB, chB, other);
		const sign = ch.target.path === 'rotation' && va.reduce((sum, v, k) => sum + v * vb[k], 0) < 0 ? -1 : 1;
		const d = Math.max(...va.map((v, k) => Math.abs(v - sign * vb[k])));
		if (d > 1e-4) problems.push(`clip ${clip.name} ${name}.${ch.target.path} end ${va} -> ${vb}`);
	}
}

console.log(`nodes ${namesA.size} -> ${namesB.size} (added: ${added.join(', ') || 'none'})`);
console.log(`colliders compared ${colliders}; drawn triangles ${triangles} (corner, UV, material, winding); clips ${clips(a).length}`);
console.log(`max world drift (position, axes) ${drift.toExponential(2)} (${worst})${mirrored.length ? `; mirrored, triangles only: ${mirrored.join(', ')}` : ''}`);
console.log(problems.length ? problems.join('\n') : 'extras, images, collider faces, drawn triangles, clips: equal');
process.exit(problems.length ? 1 : 0);
