"""Flan's Mod 1.12.2 config + dumped model -> CA vehicle frame.

Flan frame (model after flipAll, and configs): x forward, y up, z right, 1/16 block. CA: +X left, +Y up, +Z forward.
Model geometry is drawn x ModelScale; config positions are not.
"""
import json
import re
from pathlib import Path

import numpy as np

FLAN_PACKS = {
    "WW2 Pack": "flans_ww2",
    "Modern Weapons Pack": "flans_modern",
    "Apocalypse Pack": "flans_apocalypse",
    "Utility Pack": "flans_utility",
    "Ye Olde Pack": "flans_ye_olde",
}
MODEL_PACKAGES = {"mw": "modernweapons"}


def ca(v):
    """Flan (x, y, z) -> CA (-z, y, x); same units."""
    v = np.asarray(v, dtype=float)
    return np.stack([-v[..., 2], v[..., 1], v[..., 0]], axis=-1)


class Config:
    """Flan .txt: key -> list of argument lists, in file order. Keys case-sensitive as Flan registers them."""

    def __init__(self, path: Path):
        self.path = path
        self.lines = []
        for raw in path.read_text(errors="replace").splitlines():
            line = raw.split("//", 1)[0].strip()
            if line:
                parts = line.split()
                self.lines.append((parts[0], parts[1:]))

    def all(self, key):
        k = key.lower()
        return [args for name, args in self.lines if name.lower() == k]

    def get(self, key, default=None):
        found = self.all(key)
        return found[-1] if found else default

    def str(self, key, default=None):
        a = self.get(key)
        return " ".join(a) if a else default

    def float(self, key, default=0.0):
        a = self.get(key)
        return float(a[0].rstrip("fF")) if a else default

    def bool(self, key, default=False):
        a = self.get(key)
        return a[0].lower() == "true" if a else default

    def vec(self, key, default=None):
        """Config position (1/16 block) -> CA block units, unscaled."""
        a = self.get(key)
        return ca([float(x) for x in a[:3]]) / 16.0 if a else default


class Seat:
    def __init__(self, sid, pos, part, yaw=(-360, 360), pitch=(-89, 89), gun=None, gun_name=None):
        self.id, self.pos, self.part = sid, pos, part
        self.yaw, self.pitch, self.gun, self.gun_name = yaw, pitch, gun, gun_name
        self.rotated_offset = np.zeros(3)
        self.gun_origin = None


def seats(cfg: Config):
    out = {}
    d = cfg.get("Driver") or cfg.get("Pilot")
    if d:
        nums = [float(x) for x in d]
        out[0] = Seat(0, ca(nums[:3]) / 16, "core",
                      tuple(nums[3:5]) if len(nums) > 4 else (-360, 360), tuple(nums[5:7]) if len(nums) > 6 else (-89, 89))
    for a in cfg.all("Passenger"):
        sid = int(a[0])
        s = Seat(sid, ca([float(x) for x in a[1:4]]) / 16, a[4])
        if len(a) > 8:
            s.yaw, s.pitch = (float(a[5]), float(a[6])), (float(a[7]), float(a[8]))
        if len(a) > 10:
            s.gun, s.gun_name = a[9], a[10]
        out[sid] = s
    if 0 in out and cfg.get("RotatedDriverOffset"):
        out[0].rotated_offset = cfg.vec("RotatedDriverOffset")
    for a in cfg.all("RotatedPassengerOffset"):
        out[int(a[0])].rotated_offset = ca([float(x) for x in a[1:4]]) / 16
    for a in cfg.all("GunOrigin"):
        out[int(a[0])].gun_origin = ca([float(x) for x in a[1:4]]) / 16
    return [out[k] for k in sorted(out)]


class Model:
    """FlanDump JSON: groups[name] = [[box...] per sub-array]; vertices -> CA frame, x ModelScale / 16."""

    def __init__(self, path: Path, scale: float):
        d = json.loads(path.read_text())
        self.vectors = {k: ca(v) * scale / 16 for k, v in d["vectors"].items()}
        self.scalars = d["scalars"]
        self.groups = {}
        for name, arrays in d["groups"].items():
            out = []
            for boxes in arrays:
                bs = []
                for b in boxes:
                    polys = [(ca([p[:3] for p in poly]) * scale / 16, np.array([p[3:5] for p in poly]))
                             for poly in b["polys"]]
                    bs.append({"pivot": ca(b["pivot"]) * scale / 16, "polys": polys, "hidden": b["hidden"]})
                out.append(bs)
            self.groups[name] = out

    def boxes(self, *names, sub=None):
        """Boxes of these groups; sub = index into a [][] group, None = all."""
        out = []
        for n in names:
            for i, arr in enumerate(self.groups.get(n, [])):
                if sub is None or i == sub:
                    out += [b for b in arr if not b["hidden"]]
        return out

    def has(self, name):
        return any(self.groups.get(name, []))


def bounds(boxes):
    pts = np.concatenate([p for b in boxes for p, _ in b["polys"]]) if boxes else np.zeros((1, 3))
    return pts.min(axis=0), pts.max(axis=0)


def triangles(boxes, origin=(0, 0, 0)):
    """Fan each polygon; (pos, nrm, uv) per vertex, positions relative to origin. Flat normals, CCW = Flan winding."""
    pos, nrm, uv = [], [], []
    o = np.asarray(origin, dtype=float)
    for b in boxes:
        for p, t in b["polys"]:
            n = np.zeros(3)
            for i in range(len(p)):
                n += np.cross(p[i], p[(i + 1) % len(p)])
            ln = np.linalg.norm(n)
            if ln < 1e-12:
                continue
            n /= ln
            for k in range(1, len(p) - 1):
                for i in (0, k, k + 1):
                    pos.append(p[i] - o)
                    nrm.append(n)
                    uv.append(t[i])
    return np.array(pos).reshape(-1, 3), np.array(nrm).reshape(-1, 3), np.array(uv).reshape(-1, 2)


def model_class(model_id):
    pack, name = model_id.split(".", 1)
    return f"Model{name}", MODEL_PACKAGES.get(pack, pack)


def slug(short_name):
    return re.sub(r"[^a-z0-9_]+", "_", short_name.lower()).strip("_")


def ray_hits(tri, origin, dirs):
    """Möller-Trumbore: nearest hit distance per ray (inf = none). tri (n, 3, 3), dirs (m, 3) unit."""
    e1, e2 = tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]
    out = np.full(len(dirs), np.inf)
    for k, d in enumerate(dirs):
        p = np.cross(d, e2)
        det = np.einsum("ij,ij->i", e1, p)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
        s = origin - tri[:, 0]
        u = np.einsum("ij,ij->i", s, p) * inv
        q = np.cross(s, e1)
        v = (q @ d) * inv
        t = np.einsum("ij,ij->i", e2, q) * inv
        hit = ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > 1e-4)
        if hit.any():
            out[k] = t[hit].min()
    return out
