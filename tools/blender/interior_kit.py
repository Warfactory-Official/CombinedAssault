"""Vehicle interiors in Blender: decor liner + fittings built in pack frame (x port, y up, z forward; Blender (x, -z, y)),
holes cut through exterior sheets, colour x AO bake into one atlas.

    exec'd or imported inside Blender (scripts: tools/internal/interior_<vehicle>.py)
"""
import bpy, bmesh, math
import numpy as np
from mathutils import Vector, Matrix

def B(p):
    return Vector((p[0], -p[2], p[1]))

COLS = {
    'wall': (0.80, 0.79, 0.72), 'floor': (0.23, 0.25, 0.22), 'seat': (0.07, 0.075, 0.065), 'steel': (0.20, 0.21, 0.22),
    'brass': (0.72, 0.54, 0.24), 'olive': (0.29, 0.32, 0.21), 'black': (0.03, 0.03, 0.03), 'red': (0.62, 0.07, 0.05),
    'yellow': (0.80, 0.62, 0.10), 'green_dark': (0.15, 0.18, 0.13), 'glass': (0.10, 0.16, 0.18), 'grey': (0.45, 0.46, 0.45),
}

def mat(key):
    name = 'int_' + key
    m = bpy.data.materials.get(name)
    if m is None:
        m = bpy.data.materials.new(name)
        m.use_nodes = True
        bsdf = m.node_tree.nodes.get('Principled BSDF')
        bsdf.inputs['Base Color'].default_value = COLS[key] + (1.0,)
        bsdf.inputs['Roughness'].default_value = 0.8
    m.diffuse_color = COLS[key] + (1.0,)
    return m

class Builder:
    """Faces + material keys; winding fixed in to_object from each face's toward / away reference."""
    def __init__(self):
        self.bm = bmesh.new(); self.keys = []; self.orient = []
    def face(self, pts, key, toward=None, away=None):
        """toward/away: pack point (or callable(face centre pack) -> point) the visible side faces / turns from."""
        vs = [self.bm.verts.new(B(p)) for p in pts]
        f = self.bm.faces.new(vs); self.keys.append(key); self.orient.append((toward, away)); return f
    def quad_strip(self, a, b, key, close=False, toward=None, away=None):
        n = len(a)
        for i in range(n - (0 if close else 1)):
            j = (i + 1) % n
            self.face([a[i], a[j], b[j], b[i]], key, toward, away)
    def box(self, lo, hi, key, keys=None, skip=()):
        """Outward box; keys per side dict(x-,x+,y-,y+,z-,z+)."""
        x0, y0, z0 = lo; x1, y1, z1 = hi
        k = lambda s: (keys or {}).get(s, key)
        sides = {
            'y-': [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)],
            'y+': [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
            'x-': [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],
            'x+': [(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)],
            'z-': [(x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)],
            'z+': [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],
        }
        c = ((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        for s, pts in sides.items():
            if s not in skip: self.face(pts, k(s), away=c)
    def prism(self, c, axis, r, length, n, key, cap0=True, cap1=True, keycap=None, rot=0.0, r1=None):
        """Closed n-gon prism (cylinder) centre c (pack), along axis 'x'|'y'|'z', from c to c+length*axis."""
        r1 = r if r1 is None else r1
        ax = {'x': 0, 'y': 1, 'z': 2}[axis]; u, v = [i for i in range(3) if i != ax]
        ring = lambda rad, off: [tuple(c[i] + (off if i == ax else 0) + (rad * math.cos(rot + 2 * math.pi * k / n) if i == u else 0)
                                       + (rad * math.sin(rot + 2 * math.pi * k / n) if i == v else 0) for i in range(3)) for k in range(n)]
        a = ring(r, 0.0); b = ring(r1, length)
        def axis_pt(fc):
            q = list(c); q[ax] = fc[ax]; return tuple(q)
        mid = list(c); mid[ax] += length / 2
        self.quad_strip(a, b, key, close=True, away=axis_pt)
        if cap0 and r > 1e-6: self.face(a, keycap or key, away=tuple(mid))
        if cap1 and r1 > 1e-6: self.face(b, keycap or key, away=tuple(mid))
    def to_object(self, name, parent=None):
        self.bm.faces.ensure_lookup_table()
        for f, (tw, aw) in zip(self.bm.faces, self.orient):
            if tw is None and aw is None: continue
            f.normal_update(); fc = f.calc_center_median(); fcp = (fc.x, fc.z, -fc.y)
            ref = tw if tw is not None else aw
            ref = ref(fcp) if callable(ref) else ref
            d = B(ref) - fc
            if tw is None: d = -d
            if f.normal.dot(d) < 0: f.normal_flip()
        me = bpy.data.meshes.new(name)
        order = sorted(set(self.keys))
        for f, k in zip(self.bm.faces, self.keys): f.material_index = order.index(k)
        # Index set before merging: coincident faces collapse => later faces shift.
        bmesh.ops.remove_doubles(self.bm, verts=self.bm.verts, dist=1e-5)
        self.bm.to_mesh(me); self.bm.free()
        for k in order: me.materials.append(mat(k))
        ob = bpy.data.objects.get(name)
        if ob is not None:
            old = ob.data; ob.data = me; bpy.data.meshes.remove(old)
        else:
            ob = bpy.data.objects.new(name, me); bpy.context.scene.collection.objects.link(ob)
        if parent is not None:
            ob.parent = parent; ob.matrix_parent_inverse = parent.matrix_world.inverted()
        me.update()
        return ob

def annulus(b, centre, r, outline, y, key, n=32, up=True):
    """Flat plate at height y between polygon outline (pack xz) and circle hole (r around centre xz)."""
    cx, cz = centre
    poly = np.asarray(outline, float)
    def ray_hit(th):
        d = np.array([math.cos(th), math.sin(th)]); best = None
        for i in range(len(poly)):
            p, q = poly[i], poly[(i + 1) % len(poly)]
            e = q - p; m = np.array([[d[0], -e[0]], [d[1], -e[1]]])
            if abs(np.linalg.det(m)) < 1e-12: continue
            t, s = np.linalg.solve(m, p - np.array([cx, cz]))
            if t > 0 and 0 <= s <= 1 and (best is None or t < best): best = t
        return best
    inner, outer = [], []
    for k in range(n):
        th = 2 * math.pi * k / n
        inner.append((cx + r * math.cos(th), y, cz + r * math.sin(th)))
        t = ray_hit(th); outer.append((cx + t * math.cos(th), y, cz + t * math.sin(th)))
    for k in range(n):
        j = (k + 1) % n
        q = [inner[k], outer[k], outer[j], inner[j]]
        b.face(q, key, toward=lambda fc: (fc[0], fc[1] + (1 if up else -1), fc[2]))


def borrow(glb, node, lo=None, hi=None):
    """Faces of another pack model's mesh node (pack frame, model space), optionally only those inside box lo..hi.
    Seats: m983 `08_CAB_INTERIOR_mesh` (-0.91..-0.37, 1.64..2.45, 2.87..3.50) = cushion + backrest, pedestal below 1.64.
    Controls: humvee `steering_wheel`, `pedal_brake`, `pedal_throttle`, `gear_lever`, `handbrake`."""
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=glb)
    new = [o for o in bpy.data.objects if o not in before]
    try:
        src = next(o for o in new if o.name.split('.')[0] == node)
        M = src.matrix_world; me = src.data; out = []
        for p in me.polygons:
            pts = [(w.x, w.z, -w.y) for w in (M @ me.vertices[i].co for i in p.vertices)]
            if lo is None or all(lo[k] <= q[k] <= hi[k] for q in pts for k in range(3)):
                out.append(pts)
        return out
    finally:
        for o in new: bpy.data.objects.remove(o)

def place(b, polys, key, xf):
    """Borrowed polys into Builder b via xf(pack Vector) -> pack point; winding kept."""
    for pts in polys:
        b.face([tuple(xf(Vector(q))) for q in pts], key)

def cut_hole(objname, centre, r, ylo, yhi, up, n=32):
    """Circular hole (pack centre x,z; radius r) through the horizontal faces of objname in height band [ylo,yhi]
    whose world normal points up (up=True) or down. EXACT boolean on a detached copy: soup hulls break whole-mesh booleans."""
    ob = bpy.data.objects[objname]; M = ob.matrix_world; Mi = M.inverted(); R = M.to_3x3()
    cx, cz = centre
    bm = bmesh.new(); bm.from_mesh(ob.data)
    pick = []
    for f in bm.faces:
        n_ = (R @ f.normal).normalized(); c = M @ f.calc_center_median()
        if (n_.z > 0.95 if up else n_.z < -0.95) and ylo <= c.z <= yhi:
            xs = [(M @ v.co).x for v in f.verts]; zs = [-(M @ v.co).y for v in f.verts]
            if min(xs) < cx + r and max(xs) > cx - r and min(zs) < cz + r and max(zs) > cz - r:
                pick.append(f)
    if not pick:
        bm.free(); return 0
    sub = bmesh.new()
    uvl = bm.loops.layers.uv.active; suvl = sub.loops.layers.uv.new(uvl.name if uvl else 'UVMap')
    vmap = {}
    for f in pick:
        vs = []
        for v in f.verts:
            if v not in vmap: vmap[v] = sub.verts.new(v.co)
            vs.append(vmap[v])
        nf = sub.faces.new(vs); nf.material_index = f.material_index
        for l0, l1 in zip(f.loops, nf.loops):
            if uvl: l1[suvl].uv = l0[uvl].uv
    bmesh.ops.delete(bm, geom=pick, context='FACES')
    bm.to_mesh(ob.data); bm.free()
    tmp_me = bpy.data.meshes.new('_cut_tmp'); sub.to_mesh(tmp_me); sub.free()
    for m in ob.data.materials: tmp_me.materials.append(m)
    tmp = bpy.data.objects.new('_cut_tmp', tmp_me); bpy.context.scene.collection.objects.link(tmp); tmp.matrix_world = M
    bpy.ops.mesh.primitive_cylinder_add(vertices=n, radius=r, depth=(yhi - ylo) + 0.4, location=(cx, -cz, (ylo + yhi) / 2))
    cutter = bpy.context.active_object; cutter.name = '_cutter'
    mod = tmp.modifiers.new('cut', 'BOOLEAN'); mod.operation = 'DIFFERENCE'; mod.object = cutter; mod.solver = 'EXACT'
    mod.use_hole_tolerant = True
    dg = bpy.context.evaluated_depsgraph_get()
    res = tmp.evaluated_get(dg).to_mesh()
    bm = bmesh.new(); bm.from_mesh(ob.data)
    uvl = bm.loops.layers.uv.active
    rb = bmesh.new(); rb.from_mesh(res); ruv = rb.loops.layers.uv.active
    added = 0; newv = []
    for f in rb.faces:
        # boolean leaves cutter walls
        if abs(f.normal.z) < 0.5: continue
        vs = [bm.verts.new(v.co) for v in f.verts]; newv += vs
        nf = bm.faces.new(vs); nf.material_index = f.material_index; added += 1
        for l0, l1 in zip(f.loops, nf.loops):
            if uvl and ruv: l1[uvl].uv = l0[ruv].uv
    rb.free()
    bmesh.ops.remove_doubles(bm, verts=newv, dist=1e-5)
    bm.to_mesh(ob.data); bm.free(); ob.data.update()
    tmp.evaluated_get(dg).to_mesh_clear()
    bpy.data.objects.remove(tmp); bpy.data.meshes.remove(tmp_me)
    cm = cutter.data; bpy.data.objects.remove(cutter); bpy.data.meshes.remove(cm)
    return len(pick), added


def bake_atlas(objs, png, size=1024, ao_distance=0.6, ao_floor=0.30, samples=64):
    """Shared smart-UV atlas over objs; DIFFUSE colour x (ao_floor + (1-ao_floor) AO) -> png; one `interior_decor`
    material (backface culled) replaces the per-colour ones. Exterior meshes visible to render occlude the AO."""
    for o in objs:
        if not o.data.uv_layers: o.data.uv_layers.new(name='UVMap')
    win = bpy.context.window_manager.windows[0] if bpy.context.window_manager.windows else None
    area = next((a for a in win.screen.areas if a.type == 'VIEW_3D'), None) if win else None
    ctx = dict(window=win, area=area) if area else {}
    with bpy.context.temp_override(**ctx):
        if bpy.context.object and bpy.context.object.mode != 'OBJECT': bpy.ops.object.mode_set(mode='OBJECT')
        bpy.ops.object.select_all(action='DESELECT')
        for o in objs: o.select_set(True)
        bpy.context.view_layer.objects.active = objs[0]
        bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT')
        bpy.ops.uv.smart_project(angle_limit=1.15, island_margin=0.004, area_weight=0.0, scale_to_bounds=False)
        bpy.ops.uv.pack_islands(rotate=True, margin=0.004)
        bpy.ops.object.mode_set(mode='OBJECT')
        imgs = [bpy.data.images.new(n, size, size, alpha=False, float_buffer=True) for n in ('_bake_col', '_bake_ao')]
        mats = {m for o in objs for m in o.data.materials}
        sc = bpy.context.scene; sc.render.engine = 'CYCLES'; sc.cycles.samples = samples
        sc.render.bake.margin = 6; sc.render.bake.use_clear = True
        sc.world = sc.world or bpy.data.worlds.new('w'); sc.world.light_settings.distance = ao_distance
        for im, kind in zip(imgs, ('DIFFUSE', 'AO')):
            for m in mats:
                n = m.node_tree.nodes.get('_bake') or m.node_tree.nodes.new('ShaderNodeTexImage')
                n.name = '_bake'; n.image = im; m.node_tree.nodes.active = n
            bpy.ops.object.bake(type=kind, pass_filter={'COLOR'} if kind == 'DIFFUSE' else set())
    col = np.array(imgs[0].pixels[:], np.float32).reshape(size, size, 4)
    ao = np.array(imgs[1].pixels[:], np.float32).reshape(size, size, 4)[..., :1]
    lin = col[..., :3] * (ao_floor + (1 - ao_floor) * ao)
    srgb = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * np.power(np.clip(lin, 0, 1), 1 / 2.4) - 0.055)
    out = bpy.data.images.new('_bake_out', size, size, alpha=False)
    out.pixels.foreach_set(np.concatenate([np.clip(srgb, 0, 1), np.ones((size, size, 1), np.float32)], -1).ravel())
    out.filepath_raw = png; out.file_format = 'PNG'; out.save()
    for im in imgs + [out]: bpy.data.images.remove(im)
    im = bpy.data.images.load(png, check_existing=True); im.reload()
    m = bpy.data.materials.get('interior_decor') or bpy.data.materials.new('interior_decor')
    m.use_nodes = True; m.use_backface_culling = True
    nt = m.node_tree; bsdf = nt.nodes.get('Principled BSDF')
    tex = nt.nodes.get('tex') or nt.nodes.new('ShaderNodeTexImage'); tex.name = 'tex'; tex.image = im
    nt.links.new(tex.outputs['Color'], bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = 1.0; bsdf.inputs['Metallic'].default_value = 0.0
    for o in objs:
        o.data.materials.clear(); o.data.materials.append(m)
        for p in o.data.polygons: p.material_index = 0
    for old in [x for x in bpy.data.materials if x.name.startswith('int_')]: bpy.data.materials.remove(old)
