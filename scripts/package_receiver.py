#!/usr/bin/env python3
"""Package a locally built C3 receiver for offline Android installation, or check the bundle."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIRMWARE = ROOT / 'firmware/esp32c3-usb'
ASSETS = ROOT / 'app/src/main/assets/receiver'
SOURCES = ['CMakeLists.txt', 'sdkconfig.defaults', 'main/CMakeLists.txt', 'main/commands.hpp', 'main/receiver.cpp']
LAYOUT = [('bootloader.bin', 0, 0x8000), ('partition-table.bin', 0x8000, 0x1000),
          ('settings-reset.bin', 0x9000, 0x7000), ('receiver.bin', 0x10000, 0x100000)]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source_hash():
    return sha(b''.join(name.encode() + b'\0' + (FIRMWARE / name).read_bytes() + b'\0' for name in SOURCES))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify assets and source match without rebuilding')
    args = parser.parse_args()
    if not args.check:
        build = FIRMWARE / 'build'
        data = [(build / 'bootloader/bootloader.bin').read_bytes(),
                (build / 'partition_table/partition-table.bin').read_bytes(), b'\xff' * 0x7000,
                (build / 'fieldwatch_usb_receiver.bin').read_bytes()]
        ASSETS.mkdir(parents=True, exist_ok=True)
        parts = []
        for (name, offset, limit), image in zip(LAYOUT, data):
            assert 0 < len(image) <= limit, name
            if name in ('bootloader.bin', 'receiver.bin'):
                assert image[0] == 0xe9 and image[12:14] == b'\x05\0' and image[3] >> 4 == 2, name
            (ASSETS / name).write_bytes(image)
            parts.append(dict(file=name, offset=offset, size=len(image), sha256=sha(image)))
        manifest = dict(schema=1, chip='ESP32-C3', flash_size=0x400000,
                        firmware='fieldwatch-ng-usb-0.1.0', idf='6.0.2', source_sha256=source_hash(), parts=parts)
        (ASSETS / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    manifest = json.loads((ASSETS / 'manifest.json').read_text())
    assert manifest['source_sha256'] == source_hash(), 'Receiver source changed; rebuild and repackage firmware'
    assert len(manifest['parts']) == len(LAYOUT)
    for part, (name, offset, limit) in zip(manifest['parts'], LAYOUT):
        image = (ASSETS / name).read_bytes()
        assert (part['file'], part['offset']) == (name, offset)
        assert 0 < len(image) <= limit and part['size'] == len(image) and part['sha256'] == sha(image), name
    print('Receiver bundle verified: four images, SHA-256, and firmware source fingerprint.')


if __name__ == '__main__':
    main()
