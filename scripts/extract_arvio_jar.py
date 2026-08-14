#!/usr/bin/env python3
"""
Extract cloudstream3 + obfuscated kotlin classes from an ARVIO sideload APK
and combine them into a single compileOnly JAR.

Usage: extract_arvio_jar.py <apk_path> <d2j_dex2jar_sh> <output_jar>

The resulting JAR contains:
  - com/lagradost/cloudstream3/** (cloudstream3 API classes, kept by -keep)
  - All R8-obfuscated packages (j7, x7, d7, etc.) — these are the renamed
    kotlin.coroutines/jvm.functions types needed for method descriptor matching.
"""
import sys
import os
import zipfile
import subprocess
import tempfile


def extract_jar(apk_path, d2j_sh, output_jar):
    with tempfile.TemporaryDirectory() as tmpdir:
        # Extract all classes*.dex from APK
        with zipfile.ZipFile(apk_path) as z:
            dex_names = sorted(
                n for n in z.namelist()
                if n.startswith('classes') and n.endswith('.dex')
            )
            for d in dex_names:
                z.extract(d, tmpdir)
                print(f"Extracted {d}")

        # Convert each DEX to JAR
        jars = []
        for d in dex_names:
            dpath = os.path.join(tmpdir, d)
            jpath = dpath.replace('.dex', '.jar')
            subprocess.run(
                ['bash', d2j_sh, dpath, '-o', jpath, '--force'],
                check=True,
                capture_output=True, text=True
            )
            jars.append(jpath)
            print(f"Converted {d} -> {os.path.basename(jpath)}")

        # Combine into a trimmed JAR
        seen = set()
        kept = 0
        with zipfile.ZipFile(output_jar, 'w', zipfile.ZIP_DEFLATED) as out:
            for jar in jars:
                with zipfile.ZipFile(jar) as z:
                    for name in z.namelist():
                        if name in seen or not name.endswith('.class'):
                            continue
                        # Keep cloudstream3 classes + obfuscated packages
                        if (name.startswith('com/lagradost/cloudstream3/') or
                            not name.startswith(('com/', 'org/', 'java/', 'android/',
                                                 'androidx/', 'kotlin', 'sun/', 'javax/'))):
                            seen.add(name)
                            out.writestr(name, z.read(name))
                            kept += 1

        size = os.path.getsize(output_jar)
        print(f"\nCreated {output_jar}: {kept} classes, {size} bytes")

        # Verify key classes
        with zipfile.ZipFile(output_jar) as z:
            check = [
                'com/lagradost/cloudstream3/MainAPI.class',
                'com/lagradost/cloudstream3/metaproviders/TmdbProvider.class',
                'j7/d.class', 'x7/l.class', 'd7/o.class', 'j7/j.class',
            ]
            for c in check:
                status = 'FOUND' if c in z.namelist() else 'MISSING'
                print(f"  {c}: {status}")


if __name__ == '__main__':
    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <apk_path> <d2j_dex2jar_sh> <output_jar>")
        sys.exit(1)
    extract_jar(sys.argv[1], sys.argv[2], sys.argv[3])
