#!/usr/bin/env python3
"""Check that the bundled IJK ELF libraries have all non-system dependencies."""
from pathlib import Path
import re
import subprocess


def main():
    root = Path(__file__).resolve().parents[1] / 'app/src/main/jniLibs'
    system = {
        'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
        'libOpenSLES.so', 'libEGL.so', 'libGLESv2.so', 'libz.so',
        'libstdc++.so', 'libjnigraphics.so',
    }
    failures = []
    for abi, machine in [('armeabi-v7a', 'ARM'), ('arm64-v8a', 'AArch64')]:
        folder = root / abi
        libraries = {p.name for p in folder.glob('*.so')}
        for required in ['libijkplayer.so', 'libijksdl.so', 'libijkffmpeg.so']:
            if required not in libraries:
                failures.append(f'{abi}: missing {required}')
        for name in sorted(libraries):
            path = folder / name
            header = subprocess.check_output(['readelf', '-h', str(path)], text=True)
            if not re.search(r'Machine:\s+' + re.escape(machine) + r'\s*$', header, re.M):
                failures.append(f'{abi}/{name}: incorrect ELF architecture')
            dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
            for dependency in re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic):
                if dependency not in libraries | system:
                    failures.append(f'{abi}/{name}: missing {dependency}')
    if failures:
        raise SystemExit('\n'.join(failures))
    print('IJK ELF architecture and packaged dependencies verified for ARMv7 and ARM64')


if __name__ == '__main__':
    main()
