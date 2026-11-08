"""Verify the Flutter engine in a delivery APK, not just its ABI directory names."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
from urllib.parse import unquote
import zipfile

ELF_ABIS = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86_64': (2, 62)}


def validate_apk(path, expected_abis):
    path = Path(path)
    required = set(expected_abis)
    if not required or not required <= ELF_ABIS.keys():
        raise ValueError(f'Unsupported expected ABIs: {sorted(required)}')
    engines = []
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entries')
        for name in names:
            asset = unquote(name).lower()
            if (asset.startswith(('assets/flutter_assets/assets/brands/',
                                  'assets/flutter_assets/assets/models/')) or
                    (asset.startswith('assets/flutter_assets/assets/samples/') and
                     asset.endswith(('.jpg', '.jpeg')))):
                raise ValueError(f'Retired bundled asset: {name}')
        libraries = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
        engine_abis = {n.split('/')[1] for n in libraries if n.endswith('/libflutter.so')}
        missing = required - engine_abis
        if missing:
            raise ValueError(f'Missing Flutter engine libflutter.so for {", ".join(sorted(missing))}; found {sorted(engine_abis)}')
        native_abis = {n.split('/')[1] for n in libraries}
        if native_abis != required:
            raise ValueError(f'Native ABI mismatch: expected {sorted(required)}, found {sorted(native_abis)}')
        for name in libraries:
            abi = name.split('/')[1]
            with archive.open(name) as entry:
                header = entry.read(64)
            elf_class, machine = ELF_ABIS[abi]
            if (len(header) < 20 or header[:4] != b'\x7fELF' or header[4] != elf_class or
                    header[5] != 1 or int.from_bytes(header[18:20], 'little') != machine):
                raise ValueError(f'ELF architecture mismatch for {abi}: {name}')
            if name.endswith('/libflutter.so'):
                engines.append({'abi': abi, 'elfMachine': machine, 'bytes': archive.getinfo(name).file_size})
        corrupt = archive.testzip()
        if corrupt is not None:
            raise ValueError(f'APK CRC failed: {corrupt}')
        index_path = 'assets/flutter_assets/assets/watermarks/index.json'
        if index_path in names:
            from PIL import Image
            index = json.loads(archive.read(index_path))
            for row in index['styles']:
                with Image.open(io.BytesIO(archive.read('assets/flutter_assets/' + row['preview']))) as image:
                    if image.width * 4 != image.height * 5:
                        raise ValueError(f'Factory previews must be 5:4: {row["id"]} {image.size}')
    with path.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    return {'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest, 'abis': sorted(required), 'flutterEngines': engines}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--abis', nargs='+', required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(validate_apk(args.apk, args.abis)))
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(f'APK validation failed: {error}', file=sys.stderr)
        sys.exit(1)
