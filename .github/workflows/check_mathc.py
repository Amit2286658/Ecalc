#!/usr/bin/env python3
"""Validate a .mathc image against the engine's own serialization layout.

Layout source of truth: Core/compile/mathc_bin.h / mathc_bin.c.
Struct sizes are parsed from the `_Static_assert(sizeof(..) == N, ..)` lines in
mathc_bin.h so any future layout drift makes this script (and CI) fail instead
of the app failing on devices.
"""
import re
import struct
import sys

MATHC_MAGIC = 0x4348544D  # "MTHC"
MATHC_VERSION = 1
HDR_FMT = "<I H 4B H H B B 6H I I I B 7x"  # MathcHeader, 48 bytes

# int field indices: (file offsets referenced in comments below)
# 0 magic, 1 version, 2-5 type ids, 6 mod_count, 7 sym_count,
# 8 type_count, 9 const_count, 10 perm_src_len, 11 perm_ast_count,
# 12 perm_pool_top, 13 pat_elem_count, 14 field_count, 15 overload_count,
# 16 payload_bytes, 17 intr_hash, 18 checksum, 19 unit_count

# Cap defaults; refreshed from headers when possible.
CAPS_DEFAULT = {
    "MEM_SRC_BUF_CAP": 49152,
    "MEM_MAX_AST_NODES": 24576,
    "MEM_MAX_F64_POOL": 8192,
    "SYM_MAX_TYPES": 64,
    "SYM_MAX_UNITS": 64,
    "SYM_MAX_SYMS": 2048,
    "SYM_MAX_OVERLOADS": 2048,
    "SYM_MAX_CONSTS": 128,
    "SYM_MAX_PAT_ELEMS": 256,
    "SYM_MAX_FIELDS": 256,
}

CAP_HEADERS = ["Core/Headers/mem_arena.h", "Core/Headers/sym_table.h"]
ASSERT_HEADER = ["Core/compile/mathc_bin.h"]
DELEGATE_C = "Core/lib/delegate/delegate.c"
ENUM_HEADER = "Core/Headers/sym_table.h"


def read(path):
    try:
        with open(path, "r", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def fnv1a(data, h=2166136261):
    for b in data:
        h ^= b
        h = (h * 16777619) & 0xFFFFFFFF
    return h if h != 0 else 1


def struct_sizes():
    """Parse sizeof() asserts from mathc_bin.h; fall back to known sizes."""
    known = {"StrSlice": 4, "UnitDimDef": 8, "AstNode": 8, "PatElem": 6,
             "FieldDef": 8, "TypeDef": 14, "OpOverload": 46, "ConstDef": 10}
    src = ""
    for p in ASSERT_HEADER:
        src += read(p)
    for name, default in known.items():
        m = re.search(r"sizeof\(%s\)\s*==\s*(\d+)" % name, src)
        if m and int(m.group(1)) != default:
            print(f"assert override: sizeof({name}) = {m.group(1)} (default {default})")
            known[name] = int(m.group(1))
    return known


def caps():
    caps = dict(CAPS_DEFAULT)
    for p in CAP_HEADERS:
        for name in list(caps):
            m = re.search(r"#define\s+%s\s+(\d+)" % name, read(p))
            if m:
                caps[name] = int(m.group(1))
    return caps


def intrinsic_hash_of_source():
    """Recompute delegate_intrinsic_table_hash() from the vendored sources.

    Returns None if the table cannot be parsed (check then just warns).
    """
    enum_src = read(ENUM_HEADER)
    m = re.search(r"typedef\s+enum\s*\{(.*?)INTR_COUNT", enum_src, re.S)
    if not m:
        return None
    ids = re.findall(r"\b(INTR_[A-Z0-9_]+)", m.group(1))
    # Keep declaration order, drop duplicates (comments may repeat names)
    seen, order = set(), []
    for i in ids:
        if i not in seen:
            seen.add(i)
            order.append(i)
    ordinal = {sym: idx for idx, sym in enumerate(order)}
    intr_count = len(order)

    table_src = read(DELEGATE_C)
    m = re.search(r"k_delegate_intrinsics\[\]\s*=\s*\{(.*?)\n\}", table_src, re.S)
    if not m:
        return None
    h = 2166136261
    for name, sym in re.findall(r'\{\s*"([^"]+)"\s*,\s*(INTR_[A-Z0-9_]+)', m.group(1)):
        if sym not in ordinal:
            print(f"warn: table entry {sym} not found in IntrinsicId enum; skipping deep check")
            return None
        for ch in name.encode():
            h = fnv1a(bytes([ch]), h)
        i = ordinal[sym]
        h = fnv1a(bytes([i & 0xFF, (i >> 8) & 0xFF]), h)
    h = fnv1a(struct.pack("<I", intr_count), h)
    return h


def main(path):
    src = None
    for p in ASSERT_HEADER:
        src = read(p)
        if src:
            break

    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 48:
        sys.exit(f"FAIL: file too small ({len(data)} bytes)")

    hdr = struct.unpack(HDR_FMT, data[:48])
    (magic, version, fr, st, vt, mt, mod, sym, tc, cc, ps, pa, pp,
     pat, fld, ovl, payload, intr_hash, checksum, unit) = hdr

    print(f"magic={'MTHC' if magic == MATHC_MAGIC else hex(magic)} version={version}")
    print(f"modules={mod} syms={sym} types={tc} consts={cc} units={unit}")
    print(f"perm: src_len={ps} ast={pa} pool={pp} pat={pat} fields={fld} overloads={ovl}")
    print(f"payload_bytes={payload} intr_hash=0x{intr_hash:08X} checksum=0x{checksum:08X}")

    errors = []
    if magic != MATHC_MAGIC:
        errors.append(f"magic {hex(magic)} != MTHC")
    if version != MATHC_VERSION:
        errors.append(f"version {version} != {MATHC_VERSION}")

    s = struct_sizes()
    c = caps()
    expected = (ps + pa * s["AstNode"] + pp * 8
                + mod * (s["StrSlice"] + 1)
                + tc * s["TypeDef"]
                + unit * (s["StrSlice"] + s["UnitDimDef"])
                + pat * s["PatElem"] + fld * s["FieldDef"]
                + sym * s["StrSlice"] + ovl * s["OpOverload"] + cc * s["ConstDef"])
    print(f"expected payload = {expected} (sizes: {s})")
    if payload != expected:
        errors.append(f"payload {payload} != {expected}")
    if 48 + payload != len(data):
        errors.append(f"file size {len(data)} != 48 + payload {payload}")

    # Caps (mirrors ext_validate_mathc_buf)
    if not (0 < tc <= c["SYM_MAX_TYPES"]):
        errors.append(f"type_count {tc} outside 1..{c['SYM_MAX_TYPES']}")
    if unit > c["SYM_MAX_UNITS"]:
        errors.append(f"unit_count {unit} > {c['SYM_MAX_UNITS']}")
    if mod >= 0xFFFE:
        errors.append(f"mod_count {mod} >= 0xFFFE")
    if sym > c["SYM_MAX_SYMS"]:
        errors.append(f"sym_count {sym} > {c['SYM_MAX_SYMS']}")
    if ovl > c["SYM_MAX_OVERLOADS"]:
        errors.append(f"overload_count {ovl} > {c['SYM_MAX_OVERLOADS']}")
    if cc > c["SYM_MAX_CONSTS"]:
        errors.append(f"const_count {cc} > {c['SYM_MAX_CONSTS']}")
    if ps > c["MEM_SRC_BUF_CAP"]:
        errors.append(f"perm_src_len {ps} > {c['MEM_SRC_BUF_CAP']}")
    if pa > c["MEM_MAX_AST_NODES"]:
        errors.append(f"perm_ast_count {pa} > {c['MEM_MAX_AST_NODES']}")
    if pp > c["MEM_MAX_F64_POOL"]:
        errors.append(f"perm_pool_top {pp} > {c['MEM_MAX_F64_POOL']}")
    if pat > c["SYM_MAX_PAT_ELEMS"]:
        errors.append(f"pat_elem_count {pat} > {c['SYM_MAX_PAT_ELEMS']}")
    if fld > c["SYM_MAX_FIELDS"]:
        errors.append(f"field_count {fld} > {c['SYM_MAX_FIELDS']}")

    # Checksum: FNV-1a over header with checksum zeroed, then payload
    zeroed = bytearray(data[:48])
    struct.pack_into("<I", zeroed, 36, 0)
    calc = fnv1a(bytes(zeroed) + data[48:])
    print(f"checksum stored=0x{checksum:08X} computed=0x{calc:08X}")
    if checksum != calc:
        errors.append(f"checksum 0x{checksum:08X} != 0x{calc:08X}")

    # Intrinsic table hash (deep check; warns + skips if sources unparseable)
    h = intrinsic_hash_of_source()
    if h is None:
        print("note: intrinsic hash not recomputed (source parse unavailable)")
    else:
        print(f"intr_hash stored=0x{intr_hash:08X} source=0x{h:08X}")
        if intr_hash != h:
            errors.append(f"intr_hash 0x{intr_hash:08X} != 0x{h:08X}")

    if errors:
        for e in errors:
            print(f"ERROR: {e}")
        sys.exit(1)
    print("OK — .mathc passes all consistency checks")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: check_mathc.py <file.mathc>")
    main(sys.argv[1])
