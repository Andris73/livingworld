#!/usr/bin/env python3
"""
Generate a minimal 1x1x1 vanilla-format structure NBT file containing a single
structure_void block.

This is used as a "marker" placed at vanilla village town-center positions.
It's invisible, non-collidable, and leaves existing terrain blocks untouched —
but causes vanilla's jigsaw system to register a valid StructureStart with a
bounding box at proper ground level (because vanilla terrain adaptation
projects to the heightmap before placement).

Output: <repo_root>/livingworld/src/main/resources/marker_template.nbt
That file is then copied to every vanilla town-center path by the build system.

NBT structure produced (decoded):
    {
        "DataVersion": 3465,        # MC 1.20.1
        "size": [1, 1, 1],
        "palette": [{ "Name": "minecraft:structure_void" }],
        "entities": [],
        "blocks": [{ "pos": [0,0,0], "state": 0 }]
    }
"""

import gzip
import struct
import sys
from pathlib import Path

# ---------------------------------------------------------- NBT writer helpers

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def w_byte(buf, v):
    buf.append(v & 0xFF)


def w_short(buf, v):
    buf.extend(struct.pack(">h", v))


def w_int(buf, v):
    buf.extend(struct.pack(">i", v))


def w_string(buf, s):
    encoded = s.encode("utf-8")
    w_short(buf, len(encoded))
    buf.extend(encoded)


def w_named_tag_header(buf, tag_type, name):
    """Write the type byte + name for a tag inside a compound."""
    w_byte(buf, tag_type)
    w_string(buf, name)


def w_named_int(buf, name, value):
    w_named_tag_header(buf, TAG_INT, name)
    w_int(buf, value)


def w_named_string(buf, name, value):
    w_named_tag_header(buf, TAG_STRING, name)
    w_string(buf, value)


def w_named_list_int(buf, name, ints):
    """Named TAG_List of TAG_Int."""
    w_named_tag_header(buf, TAG_LIST, name)
    w_byte(buf, TAG_INT)
    w_int(buf, len(ints))
    for v in ints:
        w_int(buf, v)


def w_named_list_compound_header(buf, name, count):
    """Named TAG_List of TAG_Compound; caller must then write `count` compounds (each ending with TAG_END)."""
    w_named_tag_header(buf, TAG_LIST, name)
    w_byte(buf, TAG_COMPOUND)
    w_int(buf, count)


def w_empty_named_list(buf, name):
    """Named TAG_List with 0 elements. Element type is TAG_END (standard for empty lists)."""
    w_named_tag_header(buf, TAG_LIST, name)
    w_byte(buf, TAG_END)
    w_int(buf, 0)


def w_compound_end(buf):
    w_byte(buf, TAG_END)


# -------------------------------------------------------------- build the NBT


def build_marker_nbt() -> bytes:
    buf = bytearray()

    # Root compound. Anonymous (empty name).
    w_byte(buf, TAG_COMPOUND)
    w_string(buf, "")

    # DataVersion (1.20.1 = 3465)
    w_named_int(buf, "DataVersion", 3465)

    # size: [1, 1, 1]
    w_named_list_int(buf, "size", [1, 1, 1])

    # entities: []
    w_empty_named_list(buf, "entities")

    # palette: [{ "Name": "minecraft:structure_void" }]
    w_named_list_compound_header(buf, "palette", 1)
    w_named_string(buf, "Name", "minecraft:structure_void")
    w_compound_end(buf)

    # blocks: [{ "pos": [0,0,0], "state": 0 }]
    w_named_list_compound_header(buf, "blocks", 1)
    w_named_list_int(buf, "pos", [0, 0, 0])
    w_named_int(buf, "state", 0)
    w_compound_end(buf)

    # End root
    w_compound_end(buf)

    return bytes(buf)


def main():
    repo_root = Path(__file__).resolve().parents[2]  # .../mc-mod
    out_dir = repo_root / "livingworld" / "build" / "generated-nbt"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "marker.nbt"

    raw = build_marker_nbt()
    compressed = gzip.compress(raw)
    out_path.write_bytes(compressed)

    print(f"Wrote {out_path} ({len(compressed)} bytes gzipped, {len(raw)} bytes raw)")


if __name__ == "__main__":
    sys.exit(main() or 0)
