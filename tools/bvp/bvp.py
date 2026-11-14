"""Bert's Vehicle Pack (Superb Warfare add-on, Forge 1.20.1) source reader.

Frames:
- geo (custom_geo/*.geo.json, Bedrock pixels, x mirrored, nose -Z) -> CA blocks: (x, y, -z) / 16.
- poly_mesh facing varies per bone (exporters: hull inward + wheels outward, ...); polys CCW about stored normals, which
  agree in sign only. Facing = sign of the bone's closed-mesh volume; ~0 (open) => the file's.
- server/client def points (`Position`, `Attachments`, `VEHICLE_LOCAL_BLOCKS`) = CA frame already, blocks.
- `GENERATED_MODEL_PIXELS` (client AircraftRig, DefensiveStationPresentation) = geo frame.
- armor/*.json plates: profile frame -> CA = rotY(180) = (-x, y, -z).
- BVP pitch ranges: + = up (SBW clamps xRot to [-max, -min]); CA x_rot: - = up.
"""
import base64
import json
import re
from pathlib import Path

import numpy as np

GEO_TO_CA = np.array([1.0, 1.0, -1.0])
NS_SRC = "berts_vehicle_pack"


def ca_point(p):
    return np.asarray(p, dtype=float) * GEO_TO_CA / 16.0


def ca_dir(d):
    return np.asarray(d, dtype=float) * GEO_TO_CA


class Bone:
    def __init__(self, raw):
        self.name = raw["name"]
        self.parent = raw.get("parent")
        self.pivot = ca_point(raw.get("pivot", [0, 0, 0]))
        self.cubes = raw.get("cubes", [])
        self.pos, self.nrm, self.uv = triangles(raw.get("poly_mesh"))
        self.children = []

    def volume(self):
        """Signed, about the centroid: + = CCW outward."""
        if not self.has_mesh():
            return 0.0
        t = self.pos.reshape(-1, 3, 3) - self.pos.mean(axis=0)
        return float(np.einsum("ij,ij->", t[:, 0], np.cross(t[:, 1], t[:, 2]))) / 6

    def face_out(self, fallback):
        """Reorient to CCW outward; normals onto the face side."""
        if not self.has_mesh():
            return
        v = self.volume()
        if (v if abs(v) > 1e-9 else fallback) < 0:
            for a in (self.pos, self.nrm, self.uv):
                a.reshape(len(a) // 3, 3, -1)[:, [1, 2]] = a.reshape(len(a) // 3, 3, -1)[:, [2, 1]]
        t = self.pos.reshape(-1, 3, 3)
        face = np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0])
        n = self.nrm.reshape(-1, 3, 3)
        n *= np.where(np.einsum("ij,ikj->ik", face, n) < 0, -1.0, 1.0)[..., None]

    def has_mesh(self):
        return len(self.pos) > 0


def triangles(pm):
    """poly_mesh -> unindexed (pos, nrm, uv) per triangle corner, CA blocks; empty `polys` = not drawn."""
    empty = (np.zeros((0, 3)), np.zeros((0, 3)), np.zeros((0, 2)))
    if not pm:
        return empty
    polys = pm.get("polys")
    P = np.asarray(pm["positions"], dtype=float)
    N = np.asarray(pm["normals"], dtype=float) if pm.get("normals") else np.zeros((0, 3))
    U = np.asarray(pm["uvs"], dtype=float)
    assert pm.get("normalized_uvs", False), "pixel uvs unsupported"
    if isinstance(polys, str):
        size = {"quad_list": 4, "tri_list": 3}[polys]
        idx = np.arange(len(P) - len(P) % size).reshape(-1, size)
        polys = [[[i, i, i] for i in row] for row in idx]
    if not polys:
        return empty
    tris = []
    for poly in polys:
        seen, corners = set(), []
        for c in poly:
            if c[0] not in seen:
                seen.add(c[0])
                corners.append(c)
        for j in range(1, len(corners) - 1):
            tris.append((corners[0], corners[j], corners[j + 1]))
    if not tris:
        return empty
    t = np.asarray(tris, dtype=int)  # (n, 3, 3): corner -> (pos, nrm, uv)
    pos = ca_point(P[t[:, :, 0]].reshape(-1, 3))
    nrm = ca_dir(N[t[:, :, 1]].reshape(-1, 3)) if len(N) else np.tile([0.0, 1.0, 0.0], (len(pos), 1))
    length = np.linalg.norm(nrm, axis=1, keepdims=True)
    nrm = np.where(length > 1e-9, nrm / np.maximum(length, 1e-9), [0.0, 1.0, 0.0])
    uv = U[t[:, :, 2]].reshape(-1, 2).copy()
    uv[:, 1] = 1.0 - uv[:, 1]
    return pos, nrm, uv


class Geo:
    def __init__(self, path):
        g = json.loads(Path(path).read_text())["minecraft:geometry"][0]
        self.description = g["description"]
        self.bones = {}
        for raw in g["bones"]:
            rot = raw.get("rotation")
            assert not rot or not any(rot), f"{path}: bone {raw['name']} rotated (unsupported)"
            b = Bone(raw)
            assert b.name not in self.bones, f"{path}: duplicate bone {b.name}"
            self.bones[b.name] = b
        total = sum(b.volume() for b in self.bones.values())
        for b in self.bones.values():
            b.face_out(total)
            if b.parent:
                self.bones[b.parent].children.append(b)

    def get(self, name):
        return self.bones.get(name)

    def ancestors(self, name):
        out = []
        b = self.bones[name]
        while b.parent:
            b = self.bones[b.parent]
            out.append(b.name)
        return out


class Source:
    """One BVP vehicle id: every file the converter reads."""

    def __init__(self, root, vid):
        self.root = Path(root)
        self.vid = vid
        a = self.root / "assets" / NS_SRC
        d = self.root / "data" / NS_SRC
        self.server = json.loads((d / "sbw/vehicles" / f"{vid}.json").read_text())
        self.client = json.loads((a / "sbw/vehicles" / f"{vid}.json").read_text())
        self.geo = Geo(a / "custom_geo" / f"{vid}.geo.json")
        self.texture = a / "textures/entity" / f"{vid}.png"
        self.wreck_texture = a / "textures/entity_dead" / f"{vid}.png"
        self.armor = self._opt(d / "armor" / f"{vid}.json")
        self.flight = self._opt(d / "flight_reference" / f"{vid}.json")
        self.armament = self._opt(d / "sbw/aircraft_armaments" / f"{vid}.json")
        self.audio = self._audio()
        self.lang = json.loads((a / "lang/en_us.json").read_text())

    @staticmethod
    def _opt(path):
        return json.loads(path.read_text()) if path.exists() else None

    def _audio(self):
        """Vehicle audio profile: superbwarfare override > own; `extends` chains resolved (child keys win)."""
        for ns in ("superbwarfare", NS_SRC):
            p = self.root / "assets" / ns / "sbw/vehicle_audio" / f"{self.vid}.json"
            if p.exists():
                return self._resolve_audio(json.loads(p.read_text()))
        return None

    def _resolve_audio(self, doc):
        base = doc.pop("extends", None)
        if not base:
            return doc
        ns, path = base.split(":", 1)
        parent = self._resolve_audio(json.loads((self.root / "assets" / ns / "sbw/vehicle_audio" / f"{path}.json").read_text()))
        for k, v in doc.items():
            parent[k] = dict(parent.get(k, {}), **v) if isinstance(v, dict) else v
        return parent

    def name(self):
        return self.lang[f"entity.{NS_SRC}.{self.vid}"]

    def text(self, key):
        """Lang value for a key, else the key."""
        return self.lang.get(key, key)

    def deck(self):
        return json.loads((self.root / "data" / NS_SRC / "sbw/decks" / f"{self.vid}.json").read_text())

    def store(self, store_id):
        ns, path = store_id.split(":", 1)
        return json.loads((self.root / "data" / ns / "sbw/aircraft_stores" / f"{path}.json").read_text())

    def attachment(self, name):
        """Attachment -> (frame bone key, CA offset from that frame's pivot)."""
        a = self.server["Attachments"][name]
        return a["Parent"], np.asarray(a["Position"], dtype=float)

    def health_scale(self):
        """Product of unconditional `All * k` damage modifiers (BVP takes k of every hit)."""
        k = 1.0
        for m in self.server.get("DamageModifiers", []):
            hit = re.fullmatch(r"All \* ([0-9.]+)", m)
            if hit:
                k *= float(hit.group(1))
        return k


def plate_axes(rotation_deg):
    """Armor box axes (columns X, Y, Z = thin axis) after BVP's rotateX -> rotateY -> rotateZ, profile frame."""
    m = np.eye(3)
    for axis, deg in zip((0, 1, 2), rotation_deg):
        a = np.radians(deg)
        c, s = np.cos(a), np.sin(a)
        r = {0: [[1, 0, 0], [0, c, -s], [0, s, c]], 1: [[c, 0, s], [0, 1, 0], [-s, 0, c]],
             2: [[c, -s, 0], [s, c, 0], [0, 0, 1]]}[axis]
        m = np.array(r) @ m
    return m


PROFILE_TO_CA = np.array([-1.0, 1.0, -1.0])
FACES = {"front": [(0, 0, 1)], "rear": [(0, 0, -1)], "side": [(1, 0, 0), (-1, 0, 0)], "top": [(0, 1, 0)],
         "bottom": [(0, -1, 0)]}
GRID = 48
MIN_COS = 0.2


def aspect_armor(plates):
    """Plates of one frame -> CA face values (KE mm along the face axis): rays on a GRID x GRID lattice from each
    aspect, first plate struck => mm / max(cos incidence, MIN_COS); mean over rays that hit."""
    if not plates:
        return None
    c = np.array([np.asarray(p["center"], float) * PROFILE_TO_CA for p in plates])
    axes = np.array([np.diag(PROFILE_TO_CA) @ plate_axes(p["rotation"]) for p in plates])
    u, v, n = axes[:, :, 0], axes[:, :, 1], axes[:, :, 2]
    h = np.abs(np.array([p["half_size"] for p in plates], float))
    mm = np.array([float(p["armor_mm"]) for p in plates])
    lo, hi = c.min(axis=0) - h.max(), c.max(axis=0) + h.max()
    out = {}
    for face, dirs in FACES.items():
        los = []
        for d in dirs:
            d = -np.asarray(d, float)  # ray travels into the face
            k = int(np.argmax(np.abs(d)))
            a, b = [j for j in range(3) if j != k]
            for sa in np.linspace(lo[a], hi[a], GRID):
                for sb in np.linspace(lo[b], hi[b], GRID):
                    o = np.zeros(3)
                    o[a], o[b], o[k] = sa, sb, (hi[k] + 1) if d[k] < 0 else (lo[k] - 1)
                    dn = n @ d
                    ok = np.abs(dn) > 1e-6
                    t = np.where(ok, np.einsum("ij,ij->i", c - o, n) / np.where(ok, dn, 1), np.inf)
                    hit = o + np.where(ok, t, 0)[:, None] * d
                    rel = hit - c
                    inside = ok & (t > 0) & (np.abs(np.einsum("ij,ij->i", rel, u)) <= h[:, 0]) \
                        & (np.abs(np.einsum("ij,ij->i", rel, v)) <= h[:, 1])
                    if inside.any():
                        i = np.argmin(np.where(inside, t, np.inf))
                        los.append(mm[i] / max(abs(dn[i]), MIN_COS))
        out[face] = round(float(np.mean(los))) if los else 0
    return out


def deck_heights(deck):
    """BVP deck grid -> int16 [z, x] heights (1/16 block above the waterline; -32768 = no column)."""
    raw = np.frombuffer(base64.b64decode(deck["Top"]), dtype="<i2").reshape(-1, 2)
    flat = np.repeat(raw[:, 0], raw[:, 1].astype(np.int64))
    return flat.reshape(deck["SizeZ"], deck["SizeX"])
