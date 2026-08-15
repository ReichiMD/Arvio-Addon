#!/usr/bin/env python3
"""
Patch Java .class files to rename Kotlin coroutine/function types to ARVIO's R8-obfuscated
names, so the .cs3 DEX (compiled by d8 from these classes) contains the obfuscated type
descriptors natively. This makes suspend-function override method signatures match ARVIO's
runtime WITHOUT post-build DEX surgery.

ARVIO's R8 obfuscation map (verified from ARVIO v1.9.983 sideload APK):
  kotlin.coroutines.Continuation      -> j7.d
  kotlin.coroutines.CoroutineContext  -> j7.j
  kotlin.jvm.functions.Function1      -> x7.l

Why patch .class (not the DEX): the DEX string_ids table must be sorted in unsigned UTF-16
byte order, and string_data items must be densely packed. Renaming a string in-place in the
DEX both (a) breaks the sort order (j7/d sorts before kotlin/...) and (b) — if shortened with
zero padding — breaks ART's sequential string_data walk. Patching the .class constant_pool
instead lets d8 build a correct, sorted, valid DEX from scratch.

Constant_pool Utf8 entries hold both type descriptors ('Lkotlin/coroutines/Continuation;')
and class internal names ('kotlin/coroutines/Continuation'). We replace the type descriptor
form 'L'+name+';' and the exact internal-name form, never a bare prefix substring (so
'kotlin/coroutines/Continuation' does NOT corrupt 'ContinuationInterceptor', and
'kotlin/jvm/functions/Function1' does NOT corrupt 'Function10'..'Function19').
"""
import os
import struct
import sys
import zipfile

# (internal name, obfuscated internal name)
# Map extracted from ARVIO v1.9.983 sideload APK (classes5.dex) by matching method signatures
# to the known kotlin.coroutines.* / kotlin.jvm.functions.* interfaces.
RENAMES = [
    # kotlin.coroutines.*
    ("kotlin/coroutines/Continuation", "j7/d"),
    ("kotlin/coroutines/CoroutineContext", "j7/j"),
    ("kotlin/coroutines/CoroutineContext$Element", "j7/j$a"),
    ("kotlin/coroutines/CoroutineContext$Key", "j7/j$b"),
    ("kotlin/coroutines/ContinuationInterceptor", "j7/g"),
    ("kotlin/coroutines/ContinuationInterceptor$Key", "j7/f"),
    ("kotlin/coroutines/ContinuationInterceptor$DefaultImpls", "j7/a"),
    ("kotlin/coroutines/CombinedContext", "j7/c"),
    ("kotlin/coroutines/EmptyCoroutineContext", "j7/k"),
    ("kotlin/coroutines/CoroutineContext$DefaultImpls", "j7/e"),
    ("kotlin/coroutines/CoroutineContextKt", "j7/h"),
    ("kotlin/coroutines/StackFrameContinuation", "j7/m"),
    # kotlin.jvm.functions.* (Function base + Function0..Function21, FunctionN)
    ("kotlin/jvm/functions/Function", "d7/o"),
    ("kotlin/jvm/functions/Function0", "x7/a"),
    ("kotlin/jvm/functions/Function1", "x7/l"),
    ("kotlin/jvm/functions/Function2", "x7/p"),
    ("kotlin/jvm/functions/Function3", "x7/q"),
    ("kotlin/jvm/functions/Function4", "x7/r"),
    ("kotlin/jvm/functions/Function5", "x7/s"),
    ("kotlin/jvm/functions/Function6", "x7/t"),
    ("kotlin/jvm/functions/Function7", "x7/u"),
    ("kotlin/jvm/functions/Function8", "x7/v"),
    ("kotlin/jvm/functions/Function9", "x7/w"),
    ("kotlin/jvm/functions/Function10", "x7/b"),
    ("kotlin/jvm/functions/Function11", "x7/c"),
    ("kotlin/jvm/functions/Function12", "x7/e"),
    ("kotlin/jvm/functions/Function13", "x7/f"),
    ("kotlin/jvm/functions/Function14", "x7/g"),
    ("kotlin/jvm/functions/Function15", "x7/h"),
    ("kotlin/jvm/functions/Function16", "x7/i"),
    ("kotlin/jvm/functions/Function17", "x7/j"),
    ("kotlin/jvm/functions/Function18", "x7/k"),
    ("kotlin/jvm/functions/Function19", "x7/m"),
    ("kotlin/jvm/functions/Function20", "x7/d"),
    ("kotlin/jvm/functions/Function21", "x7/n"),
    ("kotlin/jvm/functions/FunctionN", "x7/x"),
]

# Precompute descriptor-form and exact-form replacements for fast, safe substitution.
# Order: apply descriptor form and exact form. Since each form is anchored ('L'+name+';' or
# exact name), there is no prefix-collision (e.g. 'L.../Continuation;' is not a substring of
# 'L.../ContinuationInterceptor;'; exact 'kotlin/.../Continuation' != 'kotlin/.../ContinuationInterceptor').
_DESCRIPTOR_RENAMES = [("L" + name + ";", "L" + obf + ";") for name, obf in RENAMES]
_EXACT_RENAMES = dict(RENAMES)


def rename_in_utf8(s):
    """Apply obfuscation renames to a single Utf8 string (Python str), safely."""
    if not s:
        return s
    # exact internal-name form
    if s in _EXACT_RENAMES:
        return _EXACT_RENAMES[s]
    # descriptor form(s) — replace any 'L<name>;' occurrences
    out = s
    changed = False
    for desc, obf_desc in _DESCRIPTOR_RENAMES:
        if desc in out:
            out = out.replace(desc, obf_desc)
            changed = True
    return out if changed else s


# constant_pool tag sizes (bytes after the 1-byte tag), excluding Utf8 (variable).
# Tags 5 (Long) and 6 (Double) take TWO constant_pool slots.
_TAG_FIXED_SIZE = {
    3: 4, 4: 4,              # Integer, Float
    5: 8, 6: 8,              # Long, Double (2 slots)
    7: 2,                    # Class (name_index)
    8: 2,                    # String (string_index)
    9: 4, 10: 4, 11: 4,      # Fieldref, Methodref, InterfaceMethodref
    12: 4,                   # NameAndType
    15: 3,                   # MethodHandle (reference_kind:u1 + reference_index:u2)
    16: 2,                   # MethodType
    17: 4,                   # Dynamic (bootstrap_method_attr_index:u2 + name_and_type_index:u2)
    18: 4,                   # InvokeDynamic (bootstrap_method_attr_index:u2 + name_and_type_index:u2)
    19: 2,                   # Module
    20: 2,                   # Package
}
# Tag 1 (Utf8): u2 length + bytes.


def _parse_cp(out):
    """Parse the constant pool of a (possibly already Utf8-patched) class bytearray.
    Returns (entries, end_offset) where entries[i] = (tag, offset, fields_tuple).
    entries[0] is unused (1-based). Long/Double occupy 2 slots (second is None)."""
    cp_count = struct.unpack_from('>H', out, 8)[0]
    pos = 10
    entries = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = out[pos]
        if tag == 1:  # Utf8
            length = struct.unpack_from('>H', out, pos + 1)[0]
            entries[i] = (tag, pos, ('utf8', bytes(out[pos + 3: pos + 3 + length])))
            pos += 3 + length
        else:
            sz = _TAG_FIXED_SIZE[tag]
            if tag == 7:  # Class: name_index
                entries[i] = (tag, pos, ('name_index', struct.unpack_from('>H', out, pos + 1)[0]))
            elif tag == 9:  # Fieldref: class_index, name_and_type_index
                entries[i] = (tag, pos, ('class_index', 'nat_index',
                                         struct.unpack_from('>H', out, pos + 1)[0],
                                         struct.unpack_from('>H', out, pos + 3)[0]))
            elif tag == 12:  # NameAndType: name_index, descriptor_index
                entries[i] = (tag, pos, ('name_index', 'desc_index',
                                         struct.unpack_from('>H', out, pos + 1)[0],
                                         struct.unpack_from('>H', out, pos + 3)[0]))
            else:
                entries[i] = (tag, pos, ())
            pos += 1 + sz
            if tag in (5, 6):
                i += 1
        i += 1
    return entries, pos


def _utf8_value(entries, idx):
    if idx is None or idx >= len(entries) or entries[idx] is None:
        return None
    tag, off, f = entries[idx]
    if tag != 1:
        return None
    return f[1].decode('utf-8', errors='surrogateescape')


# Fieldref rewrites: when our bundled code does getstatic ContinuationInterceptor.Key
# (companion object singleton), ARVIO's R8 removed the 'Key' field from ContinuationInterceptor
# (j7/g) and represents the Key singleton as a static field 'i' on the Key class itself (j7/f).
# So getstatic j7/g -> Key : Lj7/f; must become getstatic j7/f -> i : Lj7/f;.
# NOTE: in .class files, CONSTANT_Class_info holds the INTERNAL name (e.g. "j7/g"), NOT the
# descriptor form ("Lj7/g;"). Field TYPE is a descriptor ("Lj7/f;").
# (class_internal, field_name, field_type_desc) -> (new_class_internal, new_name, new_type)
_FIELDREF_REWRITES = [
    ("j7/g", "Key", "Lj7/f;", "j7/f", "i", "Lj7/f;"),
]


def patch_class_bytes(data):
    """Patch a single .class file: (1) rename kotlin.coroutines.*/kotlin.jvm.functions.* Utf8
    entries to ARVIO's obfuscated names, (2) rewrite ContinuationInterceptor.Key fieldrefs to
    j7/f.i (the Key singleton, since ARVIO's R8 moved it). Returns (new_bytes, patched_count)."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        return data, 0
    out = bytearray(data)
    # --- Phase 1: Utf8 renames (in-place, same-or-shorter length) ---
    cp_count = struct.unpack_from('>H', out, 8)[0]
    pos = 10
    patched = 0
    i = 1
    while i < cp_count:
        tag = out[pos]
        if tag == 1:
            length = struct.unpack_from('>H', out, pos + 1)[0]
            raw = bytes(out[pos + 3: pos + 3 + length])
            try:
                s = raw.decode('utf-8')
            except UnicodeDecodeError:
                s = raw.decode('utf-8', errors='surrogateescape')
            new_s = rename_in_utf8(s)
            if new_s != s:
                new_raw = new_s.encode('utf-8', errors='surrogateescape')
                new_len = len(new_raw)
                if new_len > 0xFFFF:
                    raise ValueError("patched Utf8 exceeds 65535 bytes")
                old_entry_len = 3 + length
                new_entry_len = 3 + new_len
                out[pos: pos + old_entry_len] = struct.pack('>BH', tag, new_len) + new_raw
                patched += 1
                pos += new_entry_len
            else:
                pos += 3 + length
        elif tag in _TAG_FIXED_SIZE:
            sz = _TAG_FIXED_SIZE[tag]
            pos += 1 + sz
            if tag in (5, 6):
                i += 1
        else:
            raise ValueError(f"unknown constant_pool tag {tag} at index {i} (pos {pos})")
        i += 1

    # --- Phase 2: fieldref rewrites ---
    entries, end_off = _parse_cp(out)
    append_buf = bytearray()

    def find_or_append_utf8(value):
        nonlocal append_buf
        vb = value.encode('utf-8', errors='surrogateescape')
        for idx in range(1, len(entries)):
            e = entries[idx]
            if e is not None and e[0] == 1 and e[2][1] == vb:
                return idx
        idx = len(entries)
        entries.append((1, None, ('utf8', vb)))
        append_buf += struct.pack('>BH', 1, len(vb)) + vb
        return idx

    def find_or_append_class(internal_name):
        # CONSTANT_Class_info uses internal name (e.g. "j7/f"), NOT descriptor.
        nonlocal append_buf
        for idx in range(1, len(entries)):
            e = entries[idx]
            if e is not None and e[0] == 7:
                if _utf8_value(entries, e[2][1]) == internal_name:
                    return idx
        ni = find_or_append_utf8(internal_name)
        idx = len(entries)
        entries.append((7, None, ('name_index', ni)))
        append_buf += struct.pack('>BH', 7, ni)
        return idx

    def find_or_append_nat(name, descriptor):
        nonlocal append_buf
        for idx in range(1, len(entries)):
            e = entries[idx]
            if e is not None and e[0] == 12:
                if _utf8_value(entries, e[2][2]) == name and _utf8_value(entries, e[2][3]) == descriptor:
                    return idx
        ni = find_or_append_utf8(name)
        di = find_or_append_utf8(descriptor)
        idx = len(entries)
        entries.append((12, None, ('name_index', 'desc_index', ni, di)))
        append_buf += struct.pack('>BHH', 12, ni, di)
        return idx

    fieldref_patches = 0
    for idx in range(1, len(entries)):
        e = entries[idx]
        if e is None or e[0] != 9:  # Fieldref
            continue
        _, off, _ = e
        class_idx = struct.unpack_from('>H', out, off + 1)[0]
        nat_idx = struct.unpack_from('>H', out, off + 3)[0]
        # resolve class descriptor
        cls_e = entries[class_idx]
        if cls_e is None or cls_e[0] != 7:
            continue
        cls_desc = _utf8_value(entries, cls_e[2][1])
        # resolve name+type (NameAndType fields tuple: ('name_index','desc_index', ni, di))
        nat_e = entries[nat_idx]
        if nat_e is None or nat_e[0] != 12:
            continue
        fname = _utf8_value(entries, nat_e[2][2])
        ftype = _utf8_value(entries, nat_e[2][3])
        for (cd, nm, ty, new_cd, new_nm, new_ty) in _FIELDREF_REWRITES:
            if cls_desc == cd and fname == nm and ftype == ty:
                new_class_idx = find_or_append_class(new_cd)
                new_nat_idx = find_or_append_nat(new_nm, new_ty)
                # patch the fieldref bytes in place (class_index, nat_index)
                struct.pack_into('>HH', out, off + 1, new_class_idx, new_nat_idx)
                fieldref_patches += 1
                break

    if append_buf:
        # Append new constant_pool entries right after the existing pool (this shifts all
        # offsets after the pool, which is fine since we rebuild `out` and constant_pool
        # entries only reference other entries by index, never by absolute offset).
        cp_end = end_off  # offset where pool ends (access_flags follow)
        out[cp_end:cp_end] = append_buf
        new_cp_count = len(entries)
        struct.pack_into('>H', out, 8, new_cp_count)
    patched += fieldref_patches

    return bytes(out), patched


def patch_jar_or_dir(target):
    """Patch every .class under a JAR file or a directory in place."""
    total = 0
    if os.path.isfile(target) and target.endswith(('.jar',)):
        # Rewrite the JAR with patched classes.
        import tempfile, shutil
        tmp = target + '.patching'
        with zipfile.ZipFile(target, 'r') as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename.endswith('.class'):
                    nd, n = patch_class_bytes(data)
                    if n:
                        total += n
                        data = nd
                zout.writestr(item, data)
        os.replace(tmp, target)
    elif os.path.isdir(target):
        for root, _, files in os.walk(target):
            for fn in files:
                if fn.endswith('.class'):
                    p = os.path.join(root, fn)
                    with open(p, 'rb') as f:
                        data = f.read()
                    nd, n = patch_class_bytes(data)
                    if n:
                        total += n
                        with open(p, 'wb') as f:
                            f.write(nd)
    else:
        raise ValueError(f"target must be a .jar or directory: {target}")
    return total


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <dir-or-jar> [dir-or-jar ...]")
        sys.exit(1)
    grand = 0
    for t in sys.argv[1:]:
        n = patch_jar_or_dir(t)
        grand += n
        print(f"{t}: patched {n} Utf8 entries")
    print(f"TOTAL patched Utf8 entries: {grand}")
