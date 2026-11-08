"""Pack model container: .glb (BIN chunk = buffer 0) or .gltf (+ buffer file / resourceLocation)."""
import json
import struct

MAGIC = 0x46546C67
JSON_CHUNK = 0x4E4F534A
BIN_CHUNK = 0x004E4942


def is_glb(path):
    return str(path).lower().endswith(".glb")


def read(path):
    """(document, BIN chunk or None); a .gltf yields no chunk."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 12 or struct.unpack_from("<I", data, 0)[0] != MAGIC:
        return json.loads(data.decode("utf-8")), None
    length, kind = struct.unpack_from("<II", data, 12)
    assert kind == JSON_CHUNK, f"{path}: first GLB chunk is not JSON"
    doc = json.loads(data[20:20 + length].decode("utf-8"))
    at = 20 + length
    if at + 8 <= len(data):
        size, kind = struct.unpack_from("<II", data, at)
        if kind == BIN_CHUNK:
            return doc, data[at + 8:at + 8 + size]
    return doc, None


def write(path, doc, blob):
    """GLB with `blob` as the BIN chunk; buffer 0 carries no uri / extras."""
    doc["buffers"][0].pop("uri", None)
    doc["buffers"][0].pop("extras", None)
    doc["buffers"][0]["byteLength"] = len(blob)
    text = json.dumps(doc, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    text += b" " * (-len(text) % 4)
    blob = bytes(blob) + b"\0" * (-len(blob) % 4)
    total = 12 + 8 + len(text) + 8 + len(blob)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", MAGIC, 2, total) + struct.pack("<II", len(text), JSON_CHUNK) + text
                + struct.pack("<II", len(blob), BIN_CHUNK) + blob)
