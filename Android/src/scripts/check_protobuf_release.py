#!/usr/bin/env python3
"""Verify Protobuf Lite reflection fields in the actual minified release DEX.

Run after assembleRelease from Android/src. R8's mapping omits some unchanged
fields, so inspect DEX definitions directly instead of treating omission as loss.
Only Python's standard library is needed, including in CI.
"""
import argparse
from functools import lru_cache
from pathlib import Path
import re
import struct
import sys
from zipfile import ZipFile


def dex_fields(data):
    if data[:4] != b"dex\n" or struct.unpack_from("<I", data, 40)[0] != 0x12345678:
        raise ValueError("Expected a standard little-endian Android DEX")

    def uleb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7
            if shift > 35:
                raise ValueError("Invalid DEX uleb128")

    _, string_offset = struct.unpack_from("<II", data, 56)
    _, type_offset = struct.unpack_from("<II", data, 64)
    _, field_offset = struct.unpack_from("<II", data, 80)
    class_count, class_offset = struct.unpack_from("<II", data, 96)

    @lru_cache(None)
    def string(index):
        offset = struct.unpack_from("<I", data, string_offset + index * 4)[0]
        _, start = uleb(offset)
        return data[start:data.index(0, start)].decode("utf-8", errors="replace")

    classes = {}
    for index in range(class_count):
        row = class_offset + index * 32
        type_index = struct.unpack_from("<I", data, row)[0]
        descriptor = string(struct.unpack_from("<I", data, type_offset + type_index * 4)[0])
        offset = struct.unpack_from("<I", data, row + 24)[0]
        fields = set()
        if offset:
            counts = []
            for _ in range(4):
                count, offset = uleb(offset)
                counts.append(count)
            for count in counts[:2]:
                field_index = 0
                for _ in range(count):
                    delta, offset = uleb(offset)
                    _, offset = uleb(offset)  # access flags
                    field_index += delta
                    name_index = struct.unpack_from("<I", data, field_offset + field_index * 8 + 4)[0]
                    fields.add(string(name_index))
        classes[descriptor] = fields
    return classes


def schemas(source, stem):
    # Mask Java literals/comments while retaining offsets for nested class scopes.
    masked = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                    lambda match: " " * len(match.group()), source, flags=re.DOTALL)
    stack, ends = [], {}
    for index, char in enumerate(masked):
        if char == "{":
            stack.append(index)
        elif char == "}" and stack:
            ends[stack.pop()] = index
    scopes = []
    pattern = r"class\s+(\w+)\s+extends\s+com\.google\.protobuf\.GeneratedMessageLite\s*<"
    for match in re.finditer(pattern, masked):
        start = masked.index("{", match.end())
        scopes.append((start, ends[start], match.group(1)))
    results = {}
    for start, end, name in scopes:
        parents = [scope[2] for scope in scopes if scope[0] < start < end < scope[1]]
        qualified = "$".join(parents + [name])
        results[qualified] = set()
    for match in re.finditer(r'"(\w+_)"', source):
        owners = [scope for scope in scopes if scope[0] < match.start() < scope[1]]
        if owners:
            start, end, name = max(owners)
            parents = [scope[2] for scope in scopes if scope[0] < start < end < scope[1]]
            results["$".join(parents + [name])].add(match.group(1))
    return results.items()


def check(generated_dir, mapping_path, apk_path):
    mappings = {}
    for line in mapping_path.read_text().splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if match:
            mappings[match.group(1)] = match.group(2)
    classes = {}
    with ZipFile(apk_path) as apk:
        for entry in apk.namelist():
            if re.fullmatch(r"classes\d*\.dex", entry):
                classes.update(dex_fields(apk.read(entry)))
    checked_classes = checked_fields = 0
    failures = []
    for source_path in sorted(generated_dir.rglob("*.java")):
        source = source_path.read_text()
        if "com.google.protobuf.GeneratedMessageLite<" not in source:
            continue
        package = re.search(r"^package ([\w.]+);", source, re.MULTILINE)
        if not package:
            continue
        for message, fields in schemas(source, source_path.stem):
            original = f"{package.group(1)}.{message}"
            renamed = mappings.get(original, original)
            descriptor = "L" + renamed.replace(".", "/") + ";"
            if descriptor not in classes or not fields:
                continue  # Entirely unreachable messages may be removed.
            checked_classes += 1
            checked_fields += len(fields)
            for field in sorted(fields - classes[descriptor]):
                failures.append(f"{original}.{field}: absent from release DEX")
    if not checked_classes or not checked_fields:
        print("FAIL: no retained schema fields checked", file=sys.stderr)
        return 1
    if failures:
        print(f"FAIL: {len(failures)}/{checked_fields} schema fields missing", file=sys.stderr)
        for failure in failures[:25]:
            print(f"  {failure}", file=sys.stderr)
        return 1
    print(f"PASS: {checked_fields} reflection schema fields intact in DEX across {checked_classes} message classes")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generated-dir", type=Path, default=Path("app/build/generated/java/generateReleaseProto/java"))
    parser.add_argument("--mapping", type=Path, default=Path("app/build/outputs/mapping/release/mapping.txt"))
    parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/release/app-arm64-v8a-release.apk"))
    args = parser.parse_args()
    if not args.generated_dir.is_dir() or not args.mapping.is_file() or not args.apk.is_file():
        parser.error("generated sources, mapping and APK must exist; run assembleRelease first")
    return check(args.generated_dir, args.mapping, args.apk)


if __name__ == "__main__":
    sys.exit(main())
