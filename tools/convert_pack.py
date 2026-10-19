#!/usr/bin/env python3
"""One-shot: old Limitless pack layout (Bedrock structure + hull json + display json) -> CombinedAssault pack.

usage: convert_pack.py <old pack root> <new pack root> [--namespace combinedassault] [--id combinedassault]

Writes node roles into each vehicle glTF (extras.combinedassaultRole / combinedassaultInterface / combinedassault),
colliders as hidden mesh nodes, defs under combinedassault/<ns>/. Report on stdout: every inferred binding and
every structure pivot the display node misses by > 0.02.
"""
import base64
import json
import math
import os
import re
import shutil
import struct
import sys

import numpy as np

ROLE = 'combinedassaultRole'
IFACE = 'combinedassaultInterface'
DATA = 'combinedassault'
HIDDEN = 'gemrenderHidden'
PIVOT_TOLERANCE = 0.02
SOCKET_FIELDS = ['camera_zoomed', 'afterburner', 'dismount', 'exhaust', 'towable', 'camera', 'vortex', 'hitch',
                 'point', 'sight', 'smoke', 'bolt', 'seat', 'slat', 'view', 'axle', 'era']
SOCKET_ROLE = {'point': 'mount_point'}
# Fields whose target is a part id (else an entry id or nothing).
PART_TARGET = {'seat', 'dismount', 'view', 'sight', 'bolt'}
INDEXED = {'bolt', 'exhaust', 'vortex', 'smoke', 'afterburner'}
SYSTEMS = ['hull', 'turret', 'barrel', 'track', 'wheel', 'tail_rotor', 'rotor', 'wing', 'engine', 'fuel', 'ammo', 'optics']
SET_ROTATION = re.compile(r'setRotation\(\s*"([^"]+)"([^;]*)')
PART_ROT = re.compile(r'getPart([XY])Rot\(\s*"([^"]+)"')


# ---------------------------------------------------------------- glTF

def trs(node):
    if 'matrix' in node:
        return np.array(node['matrix'], dtype=float).reshape(4, 4).T
    t = node.get('translation', [0, 0, 0])
    x, y, z, w = node.get('rotation', [0, 0, 0, 1])
    s = node.get('scale', [1, 1, 1])
    r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    m = np.eye(4)
    m[:3, :3] = r * np.array(s)
    m[:3, 3] = t
    return m


def matrix_trs(m):
    t = m[:3, 3]
    sx, sy, sz = (np.linalg.norm(m[:3, i]) for i in range(3))
    r = m[:3, :3] / np.array([sx, sy, sz])
    q = quat(r)
    return [float(v) for v in t], q, [float(sx), float(sy), float(sz)]


def quat(r):
    tr = r[0, 0] + r[1, 1] + r[2, 2]
    if tr > 0:
        s = math.sqrt(tr + 1.0) * 2
        w, x, y, z = 0.25 * s, (r[2, 1] - r[1, 2]) / s, (r[0, 2] - r[2, 0]) / s, (r[1, 0] - r[0, 1]) / s
    elif r[0, 0] > r[1, 1] and r[0, 0] > r[2, 2]:
        s = math.sqrt(1.0 + r[0, 0] - r[1, 1] - r[2, 2]) * 2
        w, x, y, z = (r[2, 1] - r[1, 2]) / s, 0.25 * s, (r[0, 1] + r[1, 0]) / s, (r[0, 2] + r[2, 0]) / s
    elif r[1, 1] > r[2, 2]:
        s = math.sqrt(1.0 + r[1, 1] - r[0, 0] - r[2, 2]) * 2
        w, x, y, z = (r[0, 2] - r[2, 0]) / s, (r[0, 1] + r[1, 0]) / s, 0.25 * s, (r[1, 2] + r[2, 1]) / s
    else:
        s = math.sqrt(1.0 + r[2, 2] - r[0, 0] - r[1, 1]) * 2
        w, x, y, z = (r[1, 0] - r[0, 1]) / s, (r[0, 2] + r[2, 0]) / s, (r[1, 2] + r[2, 1]) / s, 0.25 * s
    return [float(x), float(y), float(z), float(w)]


class Gltf:
    def __init__(self, path, read_asset):
        self.path = path
        self.doc = json.load(open(path))
        self.read_asset = read_asset
        self.buffers = []
        for b in self.doc['buffers']:
            if 'uri' in b and b['uri'].startswith('data:'):
                self.buffers.append(bytearray(base64.b64decode(b['uri'].split(',', 1)[1])))
            elif 'uri' in b:
                self.buffers.append(bytearray(open(os.path.join(os.path.dirname(path), b['uri']), 'rb').read()))
            else:
                self.buffers.append(bytearray(read_asset(b['extras']['resourceLocation'])))
        self.parent = {}
        for i, n in enumerate(self.doc['nodes']):
            for c in n.get('children', []):
                self.parent[c] = i

    @property
    def nodes(self):
        return self.doc['nodes']

    def scene_roots(self):
        return self.doc['scenes'][self.doc.get('scene', 0)]['nodes']

    def world(self, i):
        m = trs(self.nodes[i])
        while i in self.parent:
            i = self.parent[i]
            m = trs(self.nodes[i]) @ m
        return m

    def by_name(self, name):
        hits = [i for i, n in enumerate(self.nodes) if n.get('name') == name]
        return hits[0] if hits else None

    def add_node(self, node, parent):
        self.nodes.append(node)
        i = len(self.nodes) - 1
        if parent is None:
            self.doc['scenes'][self.doc.get('scene', 0)]['nodes'].append(i)
        else:
            self.nodes[parent].setdefault('children', []).append(i)
            self.parent[i] = parent
        return i

    def wrap_roots(self, name):
        roots = list(self.scene_roots())
        node = {'name': name, 'children': roots}
        self.nodes.append(node)
        i = len(self.nodes) - 1
        for r in roots:
            self.parent[r] = i
        self.doc['scenes'][self.doc.get('scene', 0)]['nodes'] = [i]
        return i

    def add_mesh(self, name, positions, indices, faces=None, material=None):
        """positions: float list xyz, indices: int list; appended to buffer 0. faces: triangle counts => one primitive
        per face (collider face loops survive exactly). material: index on every primitive."""
        buf = self.buffers[0]
        while len(buf) % 4:
            buf.append(0)
        pos_off = len(buf)
        buf += struct.pack('<%df' % len(positions), *positions)
        idx_off = len(buf)
        buf += struct.pack('<%dI' % len(indices), *indices)
        views = self.doc.setdefault('bufferViews', [])
        acc = self.doc.setdefault('accessors', [])
        views.append({'buffer': 0, 'byteOffset': pos_off, 'byteLength': len(positions) * 4, 'target': 34962})
        p = np.array(positions).reshape(-1, 3)
        acc.append({'bufferView': len(views) - 1, 'componentType': 5126, 'count': len(p), 'type': 'VEC3',
                    'min': [float(v) for v in p.min(0)], 'max': [float(v) for v in p.max(0)]})
        pos_acc = len(acc) - 1
        views.append({'buffer': 0, 'byteOffset': idx_off, 'byteLength': len(indices) * 4, 'target': 34963})
        view = len(views) - 1
        primitives = []
        at = 0
        for count in (faces or [len(indices) // 3]):
            acc.append({'bufferView': view, 'byteOffset': at * 12, 'componentType': 5125, 'count': count * 3,
                        'type': 'SCALAR'})
            primitives.append({'attributes': {'POSITION': pos_acc}, 'indices': len(acc) - 1, 'mode': 4})
            if material is not None:
                primitives[-1]['material'] = material
            at += count
        meshes = self.doc.setdefault('meshes', [])
        meshes.append({'name': name, 'primitives': primitives})
        return len(meshes) - 1

    def remove_nodes(self, drop):
        """Drop leaf nodes (no children) and the meshes only they used; buffer 0 compacted to what is still read."""
        drop = set(drop)
        nodes = self.doc['nodes']
        if any(nodes[i].get('children') for i in drop):
            raise ValueError('remove_nodes: only leaves')
        remap, kept = {}, []
        for i, n in enumerate(nodes):
            if i not in drop:
                remap[i] = len(kept)
                kept.append(n)
        for n in kept:
            if 'children' in n:
                n['children'] = [remap[c] for c in n['children'] if c in remap]
        for scene in self.doc['scenes']:
            scene['nodes'] = [remap[r] for r in scene['nodes'] if r in remap]
        for skin in self.doc.get('skins', []):
            skin['joints'] = [remap[j] for j in skin['joints']]
            if 'skeleton' in skin:
                skin['skeleton'] = remap[skin['skeleton']]
        for anim in self.doc.get('animations', []):
            for ch in anim['channels']:
                ch['target']['node'] = remap[ch['target']['node']]
        self.doc['nodes'] = kept
        self.parent = {remap[c]: remap[p] for c, p in self.parent.items() if c in remap}
        self.compact()

    def compact(self):
        """Unreferenced meshes, accessors, bufferViews dropped; buffer 0 rebuilt from the views still read."""
        used_mesh = sorted({n['mesh'] for n in self.doc['nodes'] if 'mesh' in n})
        mesh_map = {m: i for i, m in enumerate(used_mesh)}
        self.doc['meshes'] = [self.doc['meshes'][m] for m in used_mesh]
        for n in self.doc['nodes']:
            if 'mesh' in n:
                n['mesh'] = mesh_map[n['mesh']]
        used_acc = set()
        for m in self.doc['meshes']:
            for p in m['primitives']:
                used_acc |= set(p['attributes'].values())
                if 'indices' in p:
                    used_acc.add(p['indices'])
                for t in p.get('targets', []):
                    used_acc |= set(t.values())
        for skin in self.doc.get('skins', []):
            if 'inverseBindMatrices' in skin:
                used_acc.add(skin['inverseBindMatrices'])
        for anim in self.doc.get('animations', []):
            for sm in anim['samplers']:
                used_acc |= {sm['input'], sm['output']}
        acc_map = {a: i for i, a in enumerate(sorted(used_acc))}
        accessors = [self.doc['accessors'][a] for a in sorted(used_acc)]
        for m in self.doc['meshes']:
            for p in m['primitives']:
                p['attributes'] = {k: acc_map[v] for k, v in p['attributes'].items()}
                if 'indices' in p:
                    p['indices'] = acc_map[p['indices']]
                if 'targets' in p:
                    p['targets'] = [{k: acc_map[v] for k, v in t.items()} for t in p['targets']]
        for skin in self.doc.get('skins', []):
            if 'inverseBindMatrices' in skin:
                skin['inverseBindMatrices'] = acc_map[skin['inverseBindMatrices']]
        for anim in self.doc.get('animations', []):
            for sm in anim['samplers']:
                sm['input'], sm['output'] = acc_map[sm['input']], acc_map[sm['output']]
        used_view = sorted({a['bufferView'] for a in accessors if 'bufferView' in a}
                           | {img['bufferView'] for img in self.doc.get('images', []) if 'bufferView' in img})
        views = self.doc['bufferViews']
        if any(views[v]['buffer'] != 0 for v in used_view) or len(self.buffers) != 1:
            raise ValueError('compact: one buffer only')
        old = bytes(self.buffers[0])
        buf = bytearray()
        view_map = {}
        for v in used_view:
            while len(buf) % 4:
                buf.append(0)
            view = views[v]
            start = view.get('byteOffset', 0)
            view_map[v] = len(view_map)
            view['byteOffset'] = len(buf)
            buf += old[start:start + view['byteLength']]
        self.doc['bufferViews'] = [views[v] for v in used_view]
        for a in accessors:
            if 'bufferView' in a:
                a['bufferView'] = view_map[a['bufferView']]
        for img in self.doc.get('images', []):
            if 'bufferView' in img:
                img['bufferView'] = view_map[img['bufferView']]
        self.doc['accessors'] = accessors
        self.buffers[0] = buf

    def save(self, out_path, write_asset):
        for i, b in enumerate(self.doc['buffers']):
            b['byteLength'] = len(self.buffers[i])
            if 'uri' in b and not b['uri'].startswith('data:'):
                open(os.path.join(os.path.dirname(out_path), b['uri']), 'wb').write(self.buffers[i])
            elif 'uri' not in b:
                write_asset(b['extras']['resourceLocation'], bytes(self.buffers[i]))
        json.dump(self.doc, open(out_path, 'w'), indent=1)


def set_role(node, role, iface=None, **data):
    extras = node.setdefault('extras', {})
    if ROLE in extras and extras[ROLE] != role:
        raise ValueError('node %s already has role %s, asked %s' % (node.get('name'), extras[ROLE], role))
    extras[ROLE] = role
    if iface is not None:
        extras[IFACE] = iface
    if data:
        extras.setdefault(DATA, {}).update(data)


# ---------------------------------------------------------------- old pack

class OldPack:
    def __init__(self, root, ns):
        self.root = root
        self.ns = ns

    def rl_path(self, rl, kind):
        ns, path = rl.split(':', 1) if ':' in rl else (self.ns, rl)
        return os.path.join(self.root, kind, ns, path)

    def read_asset(self, rl):
        return open(self.rl_path(rl, 'assets'), 'rb').read()

    def json(self, kind, sub, rl):
        p = self.rl_path(rl, kind)
        return json.load(open(p.replace(os.path.join(kind, rl.split(':')[0]), os.path.join(kind, rl.split(':')[0], sub), 1)
                              + '.json'))


def structure(old, rl):
    ns, path = rl.split(':', 1)
    g = json.load(open(os.path.join(old.root, 'data', ns, 'models/bedrock', path + '.structure.json')))
    bones = {}
    for b in g['minecraft:geometry'][0]['bones']:
        px, py, pz = b.get('pivot', [0, 0, 0])
        bones[b['name']] = {'parent': b.get('parent'), 'pivot': np.array([-px / 16, py / 16, pz / 16]),
                            'cubes': len(b.get('cubes', []))}
    return bones


def hull(old, rl):
    ns, path = rl.split(':', 1)
    p = os.path.join(old.root, 'data', ns, 'models/hull', path + '.hull.json')
    return json.load(open(p))['pieces'] if os.path.exists(p) else None


def bindings(old, display):
    """display node -> (part, pitch) from controller part_bindings + script setRotation literals."""
    out = {}
    if 'animation_controller' not in display:
        return out
    cid = display['animation_controller']
    ns, path = cid.split(':', 1)
    p = os.path.join(old.root, 'assets', ns, 'animation_controllers', path + '.json')
    if not os.path.exists(p):
        return out
    scripts = []

    def walk(e):
        if isinstance(e, list):
            for x in e:
                walk(x)
        elif isinstance(e, dict):
            if isinstance(e.get('script'), str):
                scripts.append(e['script'])
            for b in e.get('part_bindings', []):
                out.setdefault(b['bone'], (b['part'], b.get('rotation_type', 'y').lower() == 'x'))
            for v in e.values():
                walk(v)
    walk(json.load(open(p)))
    for s in scripts:
        ns, path = s.split(':', 1)
        sp = os.path.join(old.root, 'assets', ns, 'scripts', path + '.js')
        if not os.path.exists(sp):
            continue
        src = open(sp).read()
        for call in SET_ROTATION.finditer(src):
            part, pitch = None, False
            for m in PART_ROT.finditer(call.group(2)):
                part = m.group(2)
                pitch |= m.group(1) == 'X'
            if part:
                out.setdefault(call.group(1), (part, pitch))
    return out


# ---------------------------------------------------------------- systems (NodeSystems name rule, made explicit)

def name_kind(name):
    n = name.lower()
    if 'turret' in n:
        return 'turret'
    if 'cannon' in n or 'barrel' in n or 'gun' in n or n == 'mg' or n.startswith('mg_'):
        return 'barrel'
    if 'tail' in n and 'rotor' in n:
        return 'tail_rotor'
    if 'rotor' in n or 'blade' in n:
        return 'rotor'
    if 'wing' in n:
        return 'wing'
    return None


def name_match(name):
    if 'steering' in name.lower():
        return None
    k = name_kind(name)
    if k in ('turret', 'barrel'):
        return k
    n = name.lower()
    if 'wheel' in n or 'track' in n:
        return 'running_gear'
    return k


def name_side(name):
    n = name.lower()
    if 'left' in n:
        return 'left'
    if 'right' in n:
        return 'right'
    for t in re.split('[^a-z0-9]+', n):
        if t == 'l':
            return 'left'
        if t == 'r':
            return 'right'
    return None


# ---------------------------------------------------------------- convert one vehicle

def triangulate(points, faces):
    idx = []
    for f in faces:
        for k in range(1, len(f) - 1):
            idx += [f[0], f[k], f[k + 1]]
    return [c for p in points for c in p], idx


def box(fr, to):
    (x0, y0, z0), (x1, y1, z1) = fr, to
    pts = [[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]]
    faces = [[0, 3, 2, 1], [4, 5, 6, 7], [0, 1, 5, 4], [2, 3, 7, 6], [1, 2, 6, 5], [0, 4, 7, 3]]
    return pts, faces


def convert_vehicle(old, vid, vehicle, displays, gltf_cache, report, model_frames):
    """Phase 1: body + part/pitch frames. model_frames[model rl] accumulates bone -> frame node across vehicles."""
    display = displays[vid]
    model_rl = display['model']
    gltf = gltf_cache(model_rl)
    bones = structure(old, vehicle['structure_model'])
    binds = bindings(old, display)
    state = model_frames.setdefault(model_rl, {'structure': vehicle['structure_model'], 'frames': {}, 'vehicles': []})
    if state['structure'] != vehicle['structure_model']:
        raise ValueError('%s: model %s shared with structure %s, this one %s' % (vid, model_rl, state['structure'],
                                                                                  vehicle['structure_model']))
    state['vehicles'].append(vid)

    roots = gltf.scene_roots()
    body = roots[0] if len(roots) == 1 else None
    if body is None or not np.allclose(gltf.world(body), np.eye(4), atol=1e-6) \
            and gltf.nodes[body].get('extras', {}).get(ROLE) != 'body':
        body = gltf.wrap_roots(vid)
        report.append('%s: body = new root wrapper' % vid)
    if ROLE not in gltf.nodes[body].get('extras', {}):
        set_role(gltf.nodes[body], 'body')
    frames = state['frames']
    frames['vehicle_body'] = body
    frames['main_structure'] = body

    def existing(role, part):
        for i, n in enumerate(gltf.nodes):
            e = n.get('extras', {})
            if e.get(ROLE) == role and e.get(IFACE) == part:
                return i
        return None

    def find_node(bone, part, pitch):
        for name, (p, is_pitch) in binds.items():
            if p == part and is_pitch == pitch and gltf.by_name(name) is not None:
                return gltf.by_name(name), 'controller/script binding'
        i = gltf.by_name(bone)
        return (i, 'same name') if i is not None else (None, None)

    def frame(bone, part, pitch, parent_frame):
        role = 'pitch' if pitch else 'part'
        i = existing(role, part)
        if i is not None:
            return i
        i, how = find_node(bone, part, pitch)
        if i is not None and ROLE in gltf.nodes[i].get('extras', {}):
            report.append('%s: node %s already %s %s; new frame for %s' % (
                vid, gltf.nodes[i]['name'], gltf.nodes[i]['extras'][ROLE], gltf.nodes[i]['extras'].get(IFACE), bone))
            i = None
        if i is None:
            pw = gltf.world(parent_frame)
            local = np.linalg.inv(pw) @ np.block([[np.eye(3), bones[bone]['pivot'].reshape(3, 1)], [0, 0, 0, 1]])
            t, q, s = matrix_trs(local)
            name = bone if gltf.by_name(bone) is None else bone + '_frame'
            i = gltf.add_node({'name': name, 'translation': t, 'rotation': q}, parent_frame)
            how = 'created at structure pivot'
        else:
            d = np.linalg.norm(gltf.world(i)[:3, 3] - bones[bone]['pivot'])
            if d > PIVOT_TOLERANCE:
                report.append('%s: %s node %s pivots %.3f from structure bone %s (display pivot wins)'
                              % (vid, role, gltf.nodes[i]['name'], d, bone))
        set_role(gltf.nodes[i], role, part)
        report.append('%s: %s %s -> node %s (%s)' % (vid, role, part, gltf.nodes[i]['name'], how))
        return i

    def parent_frame_of(bone):
        b = bones[bone]['parent']
        while b and b not in frames:
            b = bones[b]['parent']
        return frames.get(b, body) if b else body

    for part in vehicle.get('parts', []):
        bone = part.get('structure_bone')
        if not bone:
            continue
        barrel = bone + '_barrel'
        if bone in bones:
            if bone not in frames:
                frames[bone] = frame(bone, part['id'], False, parent_frame_of(bone))
        elif barrel not in bones:
            report.append('%s: part %s structure_bone %s not in structure: no frame (inert today too)'
                          % (vid, part['id'], bone))
            continue
        if barrel in bones and barrel not in frames:
            frames[barrel] = frame(barrel, part['id'], True, frames[bone] if bone in frames else parent_frame_of(barrel))
    return gltf, model_rl


def convert_colliders(old, model_rl, gltf, state, report):
    """Phase 2, once per model: hull pieces under the frame of their bone (body if no vehicle gave it one)."""
    bones = structure(old, state['structure'])
    pieces = hull(old, state['structure'])
    frames = state['frames']
    body = frames['vehicle_body']
    if pieces is None:
        raise ValueError('%s: no hull model; boxed structure unsupported' % model_rl)
    deck = set()
    for bone in bones:
        b = bone
        while b:
            if b.lower() == 'deck' or b.lower().startswith('deck_'):
                deck.add(bone)
                break
            b = bones[b]['parent']
    for bone in bones:
        if bone not in frames and bone not in ('vehicle_body', 'main_structure'):
            report.append('%s: structure bone %s carries no part frame; its pieces ride the body' % (model_rl, bone))
    for n, piece in enumerate(pieces):
        bone = piece['bone']
        f = frames.get(bone, body)
        if 'points' in piece:
            pts, faces = piece['points'], piece['faces']
        else:
            pts, faces = box(piece['from'], piece['to'])
        positions, indices = triangulate(pts, faces)
        mesh = gltf.add_mesh('collider_%s_%d' % (bone, n), positions, indices, [len(f) - 2 for f in faces])
        inv = np.linalg.inv(gltf.world(f))
        t, q, s = matrix_trs(inv)
        data = {}
        if piece.get('main'):
            data['main'] = True
        if piece.get('deck') or bone in deck:
            data['deck'] = True
        if piece.get('solid', True) is False:
            data['solid'] = False
        if piece.get('name'):
            data['name'] = piece['name']
        node = {'name': 'collider_%s_%d' % (bone, n), 'mesh': mesh, 'translation': t, 'rotation': q, 'scale': s,
                'extras': {HIDDEN: True}}
        if piece.get('generated'):
            node['extras']['genHulls'] = {'node': piece['node'], **({'locked': True} if piece.get('locked') else {})}
        set_role(node, 'collider', None, **data)
        gltf.add_node(node, f)


def hit_override(node, override):
    data = node.setdefault('extras', {}).setdefault(DATA, {})
    for k, v in override.items():
        if k == 'component':
            data['system'] = v
        elif k in ('health', 'swept', 'skip'):
            data[k] = v
        elif k != 'bone':
            raise ValueError('hit override key %s' % k)


def convert_markers(gltf, vid, tracked, report, part_ids):
    """socket_* / hit_* names -> roles; extras.combinedassault {bone, component...} -> explicit; name-rule systems."""
    for i, n in enumerate(gltf.nodes):
        name = n.get('name', '')
        lower = name.lower()
        extras = n.get('extras', {})
        old = extras.get(DATA)
        if old is not None and ROLE not in extras:
            new = {}
            for k, v in old.items():
                if k == 'component':
                    new['system'] = v
                elif k in ('health', 'swept', 'skip'):
                    new[k] = v
                elif k != 'bone':
                    raise ValueError('%s node %s: extras key %s' % (vid, name, k))
            extras[DATA] = new
            if not new:
                del extras[DATA]
        if lower.startswith('socket_'):
            rest = name[len('socket_'):]
            field = next((f for f in SOCKET_FIELDS if rest.lower() == f or rest.lower().startswith(f + '_')), None)
            if field is None:
                raise ValueError('%s: socket %s unknown field' % (vid, name))
            target = rest[len(field) + 1:] if len(rest) > len(field) else ''
            index = None
            if field == 'bolt':
                if part_ids is None:
                    if target and not target.isdigit():
                        raise ValueError('%s: mount bolt %s takes only an index' % (vid, name))
                    target, index = '', (target or None)
                else:
                    best = None
                    for pid in part_ids:
                        if (target == pid or target.startswith(pid + '_') and target[len(pid) + 1:].isdigit()) \
                                and (best is None or len(pid) > len(best)):
                            best = pid
                    if best is None:
                        raise ValueError('%s: %s names no part' % (vid, name))
                    index = target[len(best) + 1:] or None
                    target = best
            elif field in INDEXED:
                if target and not target.isdigit():
                    raise ValueError('%s: %s takes only an index' % (vid, name))
                target, index = '', (target or None)
            data = {'index': int(index)} if index is not None else {}
            set_role(n, SOCKET_ROLE.get(field, field), target if target else None, **data)
            n.setdefault('extras', {})[HIDDEN] = True
        elif lower.startswith('hit_'):
            rest = lower[4:]
            best = None
            for s in SYSTEMS:
                if (rest == s or rest.startswith(s + '_')) and (best is None or len(s) > len(best)):
                    best = s
            if best is None:
                raise ValueError('%s: %s names no system' % (vid, name))
            data = {'system': best}
            side = name_side(name)
            if side:
                data['side'] = side
            set_role(n, 'hit', None, **data)
            n.setdefault('extras', {})[HIDDEN] = True
    # Name-rule systems: explicit on the topmost node of each match, inherited by its subtree.
    for i, n in enumerate(gltf.nodes):
        extras = n.get('extras', {})
        if extras.get(ROLE) in ('collider', 'hit') or n.get('name', '').lower().startswith(('socket_', 'hit_')):
            continue
        if DATA in extras and 'system' in extras[DATA]:
            continue
        m = name_match(n.get('name', ''))
        if m is None:
            continue
        p = gltf.parent.get(i)
        inherited = False
        while p is not None:
            pe = gltf.nodes[p].get('extras', {})
            if (DATA in pe and 'system' in pe[DATA]) or name_match(gltf.nodes[p].get('name', '')) is not None:
                inherited = name_match(gltf.nodes[p].get('name', '')) == m or (DATA in pe and 'system' in pe[DATA])
                break
            p = gltf.parent.get(p)
        if inherited:
            continue
        system = ('track' if tracked else 'wheel') if m == 'running_gear' else m
        data = {'system': system}
        side = name_side(n.get('name', ''))
        if side:
            data['side'] = side
        extras.setdefault(DATA, {}).update(data)
        n['extras'] = extras


def convert_mount_model(gltf, report, name):
    """Part mount models: sockets/hit roles only; interface = carrier part at graft."""
    convert_markers(gltf, name, False, report, None)


# ---------------------------------------------------------------- main

# ---------------------------------------------------------------- Bedrock geometry -> static glTF
# Port of GemRender BedrockSkeleton/BedrockCubes: X flipped, euler (-x, -y, z) deg -> rotationZYX, /16.

BEDROCK_QUAD = [[0, 1, 5, 4], [6, 7, 3, 2], [2, 3, 1, 0], [7, 6, 4, 5], [6, 2, 0, 4], [3, 7, 5, 1]]
BEDROCK_NORMAL = [[0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]]
BEDROCK_FACES = ['down', 'up', 'north', 'south', 'west', 'east']
BEDROCK_OPPOSITE = {'west': 'east', 'east': 'west'}


def bedrock_rotation(degrees):
    x, y, z = (math.radians(-degrees[0]), math.radians(-degrees[1]), math.radians(degrees[2])) if degrees else (0, 0, 0)
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    m = np.eye(4)
    m[:3, :3] = rz @ ry @ rx
    return m


def translation(v):
    m = np.eye(4)
    m[:3, 3] = v
    return m


def bedrock_face_uv(cube, face, mirror, tw, th):
    source = BEDROCK_OPPOSITE.get(face, face) if mirror else face
    rotation = 0
    uv = cube.get('uv', [0, 0])
    if isinstance(uv, list):
        dx, dy, dz = (math.floor(v) for v in cube['size'])
        u, v = uv
        u0 = {'east': u, 'north': u + dz, 'up': u + dz, 'down': u + dz + dx, 'west': u + dz + dx,
              'south': u + dz + dx + dz}[source]
        u_size = dz if source in ('west', 'east') else dx
        v0 = v if source == 'up' else v + dz
        v_size = {'up': dz, 'down': -dz}.get(source, dy)
    else:
        entry = uv.get(source)
        if entry is None:
            return None
        u0, v0 = entry['uv']
        u_size, v_size = entry['uv_size']
        rotation = entry.get('uv_rotation', 0)
    u_min, u_max = u0 / tw, (u0 + u_size) / tw
    v_min, v_max = v0 / th, (v0 + v_size) / th
    first, second = (u_min, u_max) if mirror else (u_max, u_min)
    out = [(first, v_min), (second, v_min), (second, v_max), (first, v_max)]
    if rotation % 90 == 0:
        steps = (rotation // 90) % 4
        out = [out[(c + steps) % 4] for c in range(4)]
    return out


def bedrock_gltf(geo_path, texture_rl, out_path, model_rl, generator):
    """Bedrock geometry -> one static mesh in model space (blocks); MASK material on texture_rl."""
    g = json.load(open(geo_path))['minecraft:geometry'][0]
    tw, th = g['description']['texture_width'], g['description']['texture_height']
    bones = g['bones']
    by_name = {b['name']: i for i, b in enumerate(bones)}
    pivot = [np.array([-b.get('pivot', [0, 0, 0])[0], b.get('pivot', [0, 0, 0])[1], b.get('pivot', [0, 0, 0])[2]],
                      dtype=float) for b in bones]
    world = [None] * len(bones)

    def world_of(i):
        if world[i] is None:
            p = by_name.get(bones[i].get('parent'))
            local = translation((pivot[i] - (pivot[p] if p is not None else 0)) / 16) @ bedrock_rotation(bones[i].get('rotation'))
            world[i] = (world_of(p) if p is not None else np.eye(4)) @ local
        return world[i]

    positions, normals, uvs, indices = [], [], [], []
    for i, bone in enumerate(bones):
        for cube in bone.get('cubes', []):
            mirror = cube.get('mirror', bone.get('mirror', False))
            inflate = cube.get('inflate', bone.get('inflate', 0))
            if cube.get('rotation'):
                cp = cube.get('pivot', [0, 0, 0])
                cube_pivot = np.array([-cp[0], cp[1], cp[2]], dtype=float)
                placement = translation((cube_pivot - pivot[i]) / 16) @ bedrock_rotation(cube['rotation'])
            else:
                cube_pivot, placement = pivot[i], np.eye(4)
            m = world_of(i) @ placement
            ox, oy, oz = cube['origin']
            sx, sy, sz = cube['size']
            lo = np.array([(-(ox + sx) - cube_pivot[0] - inflate) / 16, (oy - cube_pivot[1] - inflate) / 16,
                           (oz - cube_pivot[2] - inflate) / 16])
            size = np.array([max(0, sx + 2 * inflate), max(0, sy + 2 * inflate), max(0, sz + 2 * inflate)]) / 16
            corners = [m @ np.append(lo + size * [(c & 1) > 0, (c & 2) > 0, (c & 4) > 0], 1) for c in range(8)]
            for f, face in enumerate(BEDROCK_FACES):
                span_a = size[2] if face in ('west', 'east') else size[0]
                span_b = size[2] if face in ('down', 'up') else size[1]
                face_uv = bedrock_face_uv(cube, face, mirror, tw, th)
                if span_a == 0 or span_b == 0 or face_uv is None:
                    continue
                n = m[:3, :3] @ np.array(BEDROCK_NORMAL[f], dtype=float)
                base = len(positions)
                for k, corner in enumerate(BEDROCK_QUAD[f]):
                    positions.append(corners[corner][:3])
                    normals.append(n / np.linalg.norm(n))
                    uvs.append(face_uv[k])
                indices += [base, base + 1, base + 2, base, base + 2, base + 3]
    if not indices:
        raise ValueError('%s: no faces' % geo_path)
    pos = np.array(positions, dtype='<f4')
    blob = pos.tobytes() + np.array(normals, dtype='<f4').tobytes() + np.array(uvs, dtype='<f4').tobytes()
    blob += np.array(indices, dtype='<u4').tobytes()
    count = len(positions)
    name = os.path.splitext(os.path.basename(out_path))[0]
    doc = {'asset': {'version': '2.0', 'generator': generator},
           'scene': 0, 'scenes': [{'nodes': [0]}], 'nodes': [{'name': name, 'mesh': 0}],
           'meshes': [{'primitives': [{'attributes': {'POSITION': 0, 'NORMAL': 1, 'TEXCOORD_0': 2}, 'indices': 3,
                                       'material': 0}]}],
           'materials': [{'name': name, 'doubleSided': True, 'alphaMode': 'MASK', 'alphaCutoff': 0.1,
                          'pbrMetallicRoughness': {'baseColorTexture': {'index': 0}, 'metallicFactor': 0.0,
                                                   'roughnessFactor': 1.0}}],
           'textures': [{'sampler': 0, 'source': 0}], 'samplers': [{'magFilter': 9728, 'minFilter': 9984}],
           'images': [{'mimeType': 'image/png', 'extras': {'resourceLocation': texture_rl}}],
           'buffers': [{'byteLength': len(blob), 'extras': {'resourceLocation': model_rl[:-len('.gltf')] + '.bin'}}],
           'bufferViews': [{'buffer': 0, 'byteOffset': 0, 'byteLength': count * 12},
                           {'buffer': 0, 'byteOffset': count * 12, 'byteLength': count * 12},
                           {'buffer': 0, 'byteOffset': count * 24, 'byteLength': count * 8},
                           {'buffer': 0, 'byteOffset': count * 32, 'byteLength': len(indices) * 4}],
           'accessors': [{'bufferView': 0, 'componentType': 5126, 'count': count, 'type': 'VEC3',
                          'min': [float(v) for v in pos.min(0)], 'max': [float(v) for v in pos.max(0)]},
                         {'bufferView': 1, 'componentType': 5126, 'count': count, 'type': 'VEC3'},
                         {'bufferView': 2, 'componentType': 5126, 'count': count, 'type': 'VEC2'},
                         {'bufferView': 3, 'componentType': 5125, 'count': len(indices), 'type': 'SCALAR'}]}
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    open(os.path.join(os.path.dirname(out_path), name + '.bin'), 'wb').write(blob)
    json.dump(doc, open(out_path, 'w'), indent=1)


def main():
    old_root, new_root = sys.argv[1], sys.argv[2]
    ns = 'combinedassault'
    pack_id = 'combinedassault'
    for i, a in enumerate(sys.argv):
        if a == '--namespace':
            ns = sys.argv[i + 1]
        if a == '--id':
            pack_id = sys.argv[i + 1]
    old = OldPack(old_root, ns)
    report = []
    if os.path.exists(new_root):
        raise SystemExit('%s exists' % new_root)
    # assets copied whole minus display/ and Bedrock models; then glTFs rewritten in place
    shutil.copytree(os.path.join(old_root, 'assets'), os.path.join(new_root, 'assets'),
                    ignore=shutil.ignore_patterns('display', 'bedrock'))
    os.makedirs(os.path.join(new_root, 'data', ns), exist_ok=True)
    if os.path.isdir(os.path.join(old_root, 'data', ns, 'recipe')):
        shutil.copytree(os.path.join(old_root, 'data', ns, 'recipe'), os.path.join(new_root, 'data', ns, 'recipe'))
    if os.path.isdir(os.path.join(old_root, 'data', ns, 'tags')):
        shutil.copytree(os.path.join(old_root, 'data', ns, 'tags'), os.path.join(new_root, 'data', ns, 'tags'))
    defs = os.path.join(new_root, DATA, ns)
    os.makedirs(os.path.join(defs, 'vehicles'))
    os.makedirs(os.path.join(defs, 'weapons'))

    def new_asset_path(rl):
        n, p = rl.split(':', 1)
        return os.path.join(new_root, 'assets', n, p)

    def write_asset(rl, data):
        open(new_asset_path(rl), 'wb').write(data)

    models = {}

    def gltf_cache(rl):
        if rl not in models:
            models[rl] = Gltf(new_asset_path(rl), lambda b: open(new_asset_path(b), 'rb').read())
        return models[rl]

    model_frames = {}
    displays = {}
    disp_dir = os.path.join(old_root, 'assets', ns, 'display', 'vehicle')
    for f in sorted(os.listdir(disp_dir)):
        displays[f[:-5]] = json.load(open(os.path.join(disp_dir, f)))

    veh_dir = os.path.join(old_root, 'data', ns, 'vehicles')
    for f in sorted(os.listdir(veh_dir)):
        vid = f[:-5]
        vehicle = json.load(open(os.path.join(veh_dir, f)))
        gltf, model_rl = convert_vehicle(old, vid, vehicle, displays, gltf_cache, report, model_frames)
        if not getattr(gltf, 'markers_done', False):
            convert_markers(gltf, vid, vehicle.get('type', '').endswith('tracked_vehicle'), report,
                            [p['id'] for p in vehicle.get('parts', [])])
            gltf.markers_done = True
        hitbox = vehicle.get('hitbox')
        if isinstance(hitbox, dict):
            for name, override in hitbox.get('nodes', {}).items():
                i = gltf.by_name(name)
                if i is None:
                    raise ValueError('%s: hitbox.nodes %s not in model' % (vid, name))
                hit_override(gltf.nodes[i], override)
        out = {k: v for k, v in vehicle.items() if k not in ('structure_model', 'hitbox')}
        if isinstance(vehicle.get('hitbox'), bool):
            out['hitbox'] = vehicle['hitbox']
        out['model'] = model_rl
        parts = []
        for part in vehicle.get('parts', []):
            p = {k: v for k, v in part.items() if k != 'structure_bone'}
            if 'mounts' in p:
                p['mounts'] = [{'model': m['model'], 'node': m['bone']} for m in p['mounts']]
            for mount in p.get('mounts', []):
                mrl = mount['model']
                mg = gltf_cache(mrl)
                if not getattr(mg, 'markers_done', False):
                    convert_mount_model(mg, report, mrl)
                    mg.markers_done = True
            parts.append(p)
        if parts:
            out['parts'] = parts
        d = displays[vid]
        disp = {}
        for key, new_key in (('slot_texture', 'icon'), ('animation_controller', 'controller'), ('sounds', 'sounds'),
                             ('tab_index', 'tab_index'), ('description', 'description')):
            if key in d:
                disp[new_key] = d[key]
        if 'track_config' in d:
            tc = d['track_config']
            n, path = tc['model'].split(':', 1)
            link = '%s:models/%s.gltf' % (n, path)
            bedrock_gltf(os.path.join(old_root, 'assets', n, 'models', 'bedrock', path + '.json'), tc['texture'],
                         new_asset_path(link), link, 'tools/convert_pack.py (Bedrock %s)' % tc['model'])
            disp['track'] = {'model': link, 'module_length': tc['module_length']}
        variants = {}
        for did, dd in displays.items():
            if did != vid and dd.get('model') == d['model'] and did.startswith(vid + '_') and 'paint' in dd:
                variants[did] = {'paint': dd['paint']}
                if 'tab_index' in dd:
                    variants[did]['tab_index'] = dd['tab_index']
        if 'paint' in d:
            disp['paint'] = d['paint']
        if variants:
            disp['variants'] = variants
        out['display'] = disp
        json.dump(out, open(os.path.join(defs, 'vehicles', f), 'w'), indent=4)

    for rl, state in model_frames.items():
        convert_colliders(old, rl, models[rl], state, report)
    for rl, g in models.items():
        g.save(new_asset_path(rl), write_asset)

    # weapons: json + display sounds
    wdir = os.path.join(old_root, 'data', ns, 'weapons')
    for f in sorted(os.listdir(wdir)):
        w = json.load(open(os.path.join(wdir, f)))
        dp = os.path.join(old_root, 'assets', ns, 'display', 'weapon', f)
        if os.path.exists(dp):
            wd = json.load(open(dp))
            if 'sounds' in wd:
                w['sounds'] = wd['sounds']
        json.dump(w, open(os.path.join(defs, 'weapons', f), 'w'), indent=4)

    # decorations: Bedrock sticker -> glTF + def {model, icon}
    ddir = os.path.join(old_root, 'assets', ns, 'display', 'decoration')
    os.makedirs(os.path.join(defs, 'decorations'))
    for f in sorted(os.listdir(ddir)):
        dd = json.load(open(os.path.join(ddir, f)))
        n, path = dd['model'].split(':', 1)
        model = '%s:models/%s.gltf' % (n, path)
        bedrock_gltf(os.path.join(old_root, 'assets', n, 'models', 'bedrock', path + '.json'), dd['texture'],
                     new_asset_path(model), model, 'tools/convert_pack.py (Bedrock %s)' % dd['model'])
        json.dump({'model': model, 'icon': dd['texture']}, open(os.path.join(defs, 'decorations', f), 'w'), indent=4)

    # animation controllers: part bindings are node roles now; Bedrock-clip nodes gone with Bedrock
    clip_nodes = {'switchable_animation', 'loop_animation', 'state_machine', 'track_animation', 'event_animation'}
    cdir = os.path.join(new_root, 'assets', ns, 'animation_controllers')
    for f in sorted(os.listdir(cdir)):
        c = json.load(open(os.path.join(cdir, f)))

        def strip(e):
            if isinstance(e, list):
                e[:] = [x for x in e if not (isinstance(x, dict) and x.get('type') in clip_nodes)]
                for x in e:
                    strip(x)
            elif isinstance(e, dict):
                e.pop('part_bindings', None)
                for key in ('state_machines', 'switchable_animations', 'loop_animations', 'event_animations'):
                    e.pop(key, None)
                for v in e.values():
                    strip(v)
        strip(c)
        if c.get('graph', {}).get('type') in clip_nodes:
            raise ValueError('%s: graph root is a Bedrock clip node' % f)
        json.dump(c, open(os.path.join(cdir, f), 'w'), indent=4)

    meta = json.load(open(os.path.join(old_root, 'vehicle_pack.meta.json')))
    json.dump({'pack': {'pack_format': 34, 'description': '[CA] CombinedAssault'},
               DATA: {'id': pack_id, 'license': meta.get('license', ''), 'authors': meta.get('authors', [])}},
              open(os.path.join(new_root, 'pack.mcmeta'), 'w'), indent=4)
    print('\n'.join(report))


if __name__ == '__main__':
    main()
