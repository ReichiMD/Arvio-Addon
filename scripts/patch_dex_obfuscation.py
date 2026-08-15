#!/usr/bin/env python3
"""
Patch a .cs3 DEX file to replace kotlin type descriptors with ARVIO's R8-obfuscated
equivalents. This makes suspend-function override method signatures match ARVIO's
runtime, so virtual dispatch calls our overrides instead of the parent.

ARVIO's R8 obfuscation map (verified from ARVIO v1.9.983 sideload APK):
  kotlin.coroutines.Continuation          -> j7.d
  kotlin.coroutines.CoroutineContext      -> j7.j
  kotlin.jvm.functions.Function1         -> x7.l
  kotlin.jvm.functions.Function           -> d7.o

The patch replaces the STRING DATA in the DEX string table, keeping each string_data_item
the same total byte length by padding freed space with zeros. This avoids shifting any
offsets — no string_id, type_id, proto_id, or method_id tables need updating.
"""
import struct
import sys
import os
import zipfile
import hashlib
import zlib

# ULEB128 encoding/decoding
def read_uleb128(data, offset):
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        result |= (byte & 0x7f) << shift
        offset += 1
        if (byte & 0x80) == 0:
            break
        shift += 7
    return result, offset

def encode_uleb128(value):
    out = bytearray()
    while True:
        byte = value & 0x7f
        value >>= 7
        if value != 0:
            byte |= 0x80
        out.append(byte)
        if value == 0:
            break
    return bytes(out)

# Obfuscation map: original type descriptor -> obfuscated
OBFUSCATION_MAP = {
    'Lkotlin/coroutines/Continuation;': 'Lj7/d;',
    'Lkotlin/coroutines/CoroutineContext;': 'Lj7/j;',
    'Lkotlin/jvm/functions/Function1;': 'Lx7/l;',
    'Lkotlin/jvm/functions/Function;': 'Ld7/o;',
}


def patch_dex(dex_data):
    """Patch DEX string_data with obfuscated type descriptors, repacked compactly in place.

    ARVIO's R8 obfuscates kotlin.coroutines.Continuation -> j7.d etc. Our .cs3 is compiled
    against the unobfuscated cloudstream3 stub, so override method descriptors use the
    unobfuscated names. We replace the 4 type-descriptor strings.

    Implementation: rebuild the string_data section COMPACTLY (all items back-to-back, no
    gaps) within its ORIGINAL byte extent, padding the freed tail with zeros. Because the
    section's total byte length and end offset are unchanged, NO other section, header field,
    map_list entry, or embedded offset moves — only the string_id offsets (which now point to
    the compact positions) change. This is minimal and safe.

    Why compact (not zero-pad-in-place): ART's verifier walks string_data items SEQUENTIALLY
    by the map_list item count. Zero-padding inserted BETWEEN shortened items is parsed as
    phantom empty-string items, so the walk ends prematurely and ART rejects the DEX
    ("Non-zero padding b before section of type 8196"). Trailing zero padding AFTER the last
    real item (i.e. alignment/section padding) IS allowed by the DEX spec and ART. So we keep
    all real items contiguous at the start and push the freed bytes to the tail as zeros.
    """
    data = bytearray(dex_data)

    if data[:4] != b'dex\n':
        raise ValueError(f"Not a DEX file: magic={data[:8]!r}")

    string_ids_size = struct.unpack_from('<I', data, 0x38)[0]
    string_ids_off = struct.unpack_from('<I', data, 0x3C)[0]

    print(f"DEX: string_ids_size={string_ids_size}")

    # 1. Read every string_data item (in string_id order): (utf16_size, mutf8_bytes)
    items = []
    for i in range(string_ids_size):
        sdi_off = struct.unpack_from('<I', data, string_ids_off + i * 4)[0]
        utf16_size, data_start = read_uleb128(data, sdi_off)
        null_pos = data.index(b'\x00', data_start)
        items.append([utf16_size, bytes(data[data_start:null_pos])])

    # 2. Apply the obfuscation map to item values
    patched_count = 0
    for it in items:
        s = it[1].decode('utf-8', errors='replace')
        if s in OBFUSCATION_MAP:
            new_s = OBFUSCATION_MAP[s]
            it[0] = len(new_s)  # ASCII: 1 utf16 code unit per char
            it[1] = new_s.encode('utf-8')
            print(f"  '{s}' -> '{new_s}'")
            patched_count += 1

    if patched_count == 0:
        print("WARNING: no strings patched — DEX may already be obfuscated")
        return bytes(data), 0

    # 3. Find the string_data section extent [sd_off, sd_end) from the map_list.
    map_off_val = struct.unpack_from('<I', data, 0x34)[0]
    map_count = struct.unpack_from('<I', data, map_off_val)[0]
    section_offsets = []
    sd_off = None
    for i in range(map_count):
        t, _, _, off = struct.unpack_from('<HHII', data, map_off_val + 4 + i * 12)
        section_offsets.append(off)
        if t == 0x2002:  # TYPE_STRING_DATA_ITEM
            sd_off = off
    if sd_off is None:
        raise ValueError("string_data section not found in map_list")
    sd_end = min(o for o in section_offsets if o > sd_off)  # next section start
    sd_byte_len = sd_end - sd_off

    # 4. Rebuild compactly; record new per-item offsets.
    new_blob = bytearray()
    new_offsets = []
    for utf16_size, mutf8 in items:
        new_offsets.append(sd_off + len(new_blob))
        new_blob += encode_uleb128(utf16_size)
        new_blob += mutf8
        new_blob += b'\x00'

    freed = sd_byte_len - len(new_blob)
    if freed < 0:
        raise ValueError(f"string_data repack grew by {-freed} bytes — obfuscated names must be shorter")
    # Pad the tail with zeros so the section keeps its original byte extent/end offset.
    new_blob += b'\x00' * freed
    print(f"string_data: repacked {len(new_blob)} bytes (compact={sd_byte_len - freed}, freed tail={freed} zeros)")

    # 5. Overwrite the string_data region in place.
    data[sd_off:sd_end] = new_blob

    # 6. Update string_id offsets to the compact positions.
    for i, noff in enumerate(new_offsets):
        struct.pack_into('<I', data, string_ids_off + i * 4, noff)

    # 7. Recompute SHA-1 signature (bytes 32..file_size) + Adler32 checksum (bytes 12..file_size).
    file_size = struct.unpack_from('<I', data, 32)[0]
    data[12:32] = hashlib.sha1(bytes(data[32:file_size])).digest()
    struct.pack_into('<I', data, 8, zlib.adler32(bytes(data[12:file_size])) & 0xffffffff)

    print(f"\nPatched {patched_count} strings. DEX size unchanged: {len(data)} bytes")
    return bytes(data), patched_count


def patch_cs3(cs3_path, output_path=None):
    """Patch the classes.dex inside a .cs3 (ZIP) file."""
    if output_path is None:
        output_path = cs3_path

    with zipfile.ZipFile(cs3_path, 'r') as z:
        names = z.namelist()
        contents = {name: z.read(name) for name in names}

    if 'classes.dex' not in contents:
        raise ValueError(f"No classes.dex in {cs3_path}")

    print(f"Patching {cs3_path}...")
    patched_dex, count = patch_dex(contents['classes.dex'])
    contents['classes.dex'] = patched_dex

    # Write the patched .cs3
    with zipfile.ZipFile(output_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in names:
            z.writestr(name, contents[name])

    print(f"Written patched .cs3 to {output_path}")
    return count


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <input.cs3> [output.cs3]")
        sys.exit(1)

    cs3_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else cs3_path
    count = patch_cs3(cs3_path, output_path)

    # A zero count means the DEX already uses obfuscated names (or the mapping found nothing).
    # This is not a build-breaking error: the .cs3 is still valid. We print a warning and exit 0
    # so the build can proceed; the build's correctness is verified on-device via logcat.
    if count == 0:
        print("WARNING: patch_dex_obfuscation patched 0 strings (DEX may already be obfuscated).")
