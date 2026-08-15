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
RENAMES = [
    ("kotlin/coroutines/Continuation", "j7/d"),
    ("kotlin/coroutines/CoroutineContext", "j7/j"),
    ("kotlin/jvm/functions/Function1", "x7/l"),
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


def patch_class_bytes(data):
    """Patch a single .class file's constant_pool Utf8 entries. Returns new bytes."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        return data  # not a class file (e.g. module-info or resource)
    out = bytearray(data)
    cp_count = struct.unpack_from('>H', out, 8)[0]
    pos = 10  # first constant_pool entry
    patched = 0
    i = 1
    while i < cp_count:
        tag = out[pos]
        if tag == 1:  # CONSTANT_Utf8
            length = struct.unpack_from('>H', out, pos + 1)[0]
            raw = bytes(out[pos + 3: pos + 3 + length])
            try:
                s = raw.decode('utf-8')
            except UnicodeDecodeError:
                # Modified UTF-8 (null encoded as 0xC0 0x80, supplementary chars as surrogate pairs).
                # Decode leniently; our targets are ASCII so this path is just pass-through safety.
                s = raw.decode('utf-8', errors='surrogateescape')
            new_s = rename_in_utf8(s)
            if new_s != s:
                new_raw = new_s.encode('utf-8', errors='surrogateescape')
                new_len = len(new_raw)
                if new_len > 0xFFFF:
                    raise ValueError("patched Utf8 exceeds 65535 bytes (shouldn't happen: names only shrink)")
                # Replace: [tag(1) + len(2) + old_raw] -> [tag + new_len + new_raw]
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
            if tag in (5, 6):  # Long/Double occupy 2 cp slots
                i += 1
        else:
            # Unknown tag — bail to avoid corrupting the file.
            raise ValueError(f"unknown constant_pool tag {tag} at index {i} (pos {pos})")
        i += 1
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
