#!/usr/bin/env python3
"""Write the gametest templates the naval tests float in: a stone basin full of still water.

  water_pool  48 x 20 x 48, water 10 deep, for the patrol boat
  sea_trench  96 x 52 x 24, water 36 deep, for the submarine

Structure NBT written by hand (gzip, big-endian tags); DataVersion is 1.21.1's.

Run from the combinedassault directory:  python3 tools/make_water_pool.py
"""
import gzip
import os
import struct

DATA_VERSION = 3955
OUT = "src/main/resources/data/combinedassault/structure"


def tag_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag_int(v):
    return struct.pack(">i", v)


def named(kind, name, payload):
    return bytes([kind]) + tag_string(name) + payload


def compound(entries):
    return b"".join(entries) + b"\x00"


def int_list(values):
    return bytes([3]) + tag_int(len(values)) + b"".join(tag_int(v) for v in values)


def compound_list(items):
    return bytes([10]) + tag_int(len(items)) + b"".join(items)


def block_state(name, props=None):
    entries = [named(8, "Name", tag_string(name))]
    if props:
        entries.append(named(10, "Properties", compound([named(8, k, tag_string(v)) for k, v in props.items()])))
    return compound(entries)


def basin(size_x, size_y, size_z, water_depth):
    """Stone floor and walls, water to water_depth above the floor, air above that."""
    palette = [block_state("minecraft:stone"), block_state("minecraft:water", {"level": "0"}),
               block_state("minecraft:air")]
    blocks = []
    for x in range(size_x):
        for y in range(size_y):
            for z in range(size_z):
                wall = x == 0 or z == 0 or x == size_x - 1 or z == size_z - 1 or y == 0
                if wall:
                    state = 0
                elif y <= water_depth:
                    state = 1
                else:
                    state = 2
                blocks.append(compound([named(9, "pos", int_list([x, y, z])), named(3, "state", tag_int(state))]))
    root = compound([
        named(9, "size", int_list([size_x, size_y, size_z])),
        named(9, "palette", compound_list(palette)),
        named(9, "blocks", compound_list(blocks)),
        named(9, "entities", compound_list([])),
        named(3, "DataVersion", tag_int(DATA_VERSION)),
    ])
    return named(10, "", root)


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    for name, dims in {"water_pool": (48, 20, 48, 10), "sea_trench": (96, 52, 24, 36)}.items():
        data = basin(*dims)
        path = os.path.join(OUT, f"{name}.nbt")
        with gzip.open(path, "wb") as f:
            f.write(data)
        print(f"{path}: {dims[0]}x{dims[1]}x{dims[2]}, water {dims[3]} deep, {os.path.getsize(path)} bytes gzipped")


if __name__ == "__main__":
    main()
