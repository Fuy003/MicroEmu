#!/usr/bin/env python3
"""Rebuild the narrow loopback fix without recompiling the obfuscated Manager."""
import argparse
import hashlib
import pathlib
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE_SHA256 = 'd8c64d36f46ac2fe9fdef8d6143a7272f35630ae0ba3f3c64fb47c6c300e6011'
HELPER = 'org/microemu/manager/net/LoopbackProxySocket'


def u2(value):
    return struct.pack('>H', value)


def utf8(value):
    data = value.encode('utf-8')
    return b'\x01' + u2(len(data)) + data


def patch_bridge(data):
    """Replace only the constructor expression; preserve all code offsets."""
    count = struct.unpack_from('>H', data, 8)[0]
    pos, index = 10, 1
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            size = struct.unpack_from('>H', data, pos)[0]
            pos += 2 + size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        else:
            raise ValueError('Unsupported constant pool tag: %s' % tag)
        index += 1

    # This exact expression is pinned to the verified original JAR below:
    # new ServerSocket; dup; getstatic c.e; iconst_1; iaload; invokespecial (I)V.
    before = b'\xbb' + u2(35) + b'\x59\xb2' + u2(43) + b'\x04\x2e\xb7' + u2(84)
    body = data[pos:]
    if body.count(before) != 1:
        raise ValueError('Expected exactly one original proxy socket expression')

    # Six appended constants leave all existing constant-pool indexes intact.
    extra = (utf8(HELPER) + b'\x07' + u2(count)
             + utf8('create') + utf8('(I)Ljava/net/ServerSocket;')
             + b'\x0c' + u2(count + 2) + u2(count + 3)
             + b'\x0a' + u2(count + 1) + u2(count + 4))
    # Remove new/dup with NOPs and use invokestatic at the same bytecode offset.
    after = b'\x00' * 4 + before[4:9] + b'\xb8' + u2(count + 5)
    assert len(before) == len(after)
    return data[:8] + u2(count + 6) + data[10:pos] + extra + body.replace(before, after, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=pathlib.Path,
                        default=ROOT.parent / 'MicroEmulator Manager.jar',
                        help='Unmodified Manager JAR (defaults to the original outside Emu_1)')
    parser.add_argument('--output', type=pathlib.Path,
                        default=ROOT / 'MicroEmulator Manager.jar')
    args = parser.parse_args()
    try:
        args.output.resolve().relative_to(ROOT.resolve())
    except ValueError:
        raise SystemExit('Refusing to write outside Emu_1; original files must remain unchanged')
    original = args.input.read_bytes()
    if hashlib.sha256(original).hexdigest() != BASE_SHA256:
        raise SystemExit('Refusing to patch: input SHA-256 does not match the audited original')

    with tempfile.TemporaryDirectory() as directory:
        tmp = pathlib.Path(directory)
        classes = tmp / 'classes'
        source = ROOT / 'src/main/java' / (HELPER + '.java')
        javac = shutil.which('javac')
        compiler = [javac] if javac else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
        subprocess.run(compiler + ['--release', '8', '-g:none', '-d', str(classes), str(source)], check=True)
        rebuilt = tmp / 'manager.jar'
        with zipfile.ZipFile(args.input) as zin, zipfile.ZipFile(rebuilt, 'w') as zout:
            for info in zin.infolist():
                content = zin.read(info.filename)
                if info.filename == 'c.class':
                    content = patch_bridge(content)
                zout.writestr(info, content)
            info = zipfile.ZipInfo(HELPER + '.class', (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zout.writestr(info, (classes / (HELPER + '.class')).read_bytes())
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(rebuilt, args.output)
    print('Patched:', args.output)
    print('SHA-256:', hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
