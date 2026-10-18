#!/usr/bin/env python3
"""Build/test the Wiki-ready ARM64 release using a private local credential input.

Set EAY_LOCAL_BUILD_CREDENTIAL for this process only, or supply EAY_API_KEY_FILE.
No credential value is written to tracked files or printed. Generated Android
build files and the APK necessarily contain the embedded distribution key.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def main():
    android_root = Path(__file__).resolve().parents[1]
    repository = android_root.parents[1]
    destination = repository.parent / "Edge-Ai-v1.0.25-arm64-wiki-ready.apk"
    log = repository.parent / "edge-ai-v1.0.25-bundled-build.log"
    provided_file = os.environ.get("EAY_API_KEY_FILE")
    key = os.environ.pop("EAY_LOCAL_BUILD_CREDENTIAL", "").strip()
    if not key and provided_file:
        key = Path(provided_file).read_text().strip()
    if not key:
        raise SystemExit("A private EAY build credential is required.")
    descriptor, local_input = tempfile.mkstemp(prefix="edge-eay-build-", suffix=".key")
    try:
        os.chmod(local_input, 0o600)
        with os.fdopen(descriptor, "w") as output:
            output.write(key)
        environment = {k: v for k, v in os.environ.items() if not k.startswith("MANUS_SECRET_")}
        environment["EAY_API_KEY_FILE"] = local_input
        command = [
            "./gradlew", ":app:testDebugUnitTest", ":app:assembleRelease",
            "--console=plain", "--max-workers=1",
            "-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8",
        ]
        with log.open("w") as output:
            process = subprocess.Popen(command, cwd=android_root, env=environment,
                                       stdout=output, stderr=subprocess.STDOUT)
            while True:
                try:
                    status = process.wait(timeout=45)
                    break
                except subprocess.TimeoutExpired:
                    lines = log.read_text(errors="replace").splitlines()
                    tasks = [line for line in lines if line.startswith("> Task")]
                    phase = tasks[-1] if tasks else "menyiapkan Gradle"
                    print("Build berjalan: " + phase.replace(key, "[REDACTED]"), flush=True)
        contents = log.read_text(errors="replace")
        if key in contents:
            log.write_text(contents.replace(key, "[REDACTED]"))
            raise SystemExit("Credential appeared in the build log; it was redacted. Do not distribute this build.")
        print("\n".join(contents.splitlines()[-65:]), flush=True)
        if status:
            raise SystemExit(status)
        names = subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
            cwd=repository, text=True,
        ).splitlines()
        if any((repository / name).is_file() and key.encode() in (repository / name).read_bytes()
               for name in names):
            raise SystemExit("Credential found in non-ignored source files; do not distribute or push.")
        apk = android_root / "app/build/outputs/apk/release/app-arm64-v8a-release.apk"
        with zipfile.ZipFile(apk) as archive:
            native = [name for name in archive.namelist()
                      if name.startswith("lib/") and name.endswith(".so")]
            if not native or not all(name.startswith("lib/arm64-v8a/") for name in native):
                raise SystemExit("Expected an ARM64-only APK.")
            if not any(key.encode() in archive.read(name) for name in archive.namelist()
                       if name.endswith(".dex")):
                raise SystemExit("Distribution credential is missing from the release APK.")
        shutil.copy2(apk, destination)
        print("AUDIT OK: key embedded in APK, absent from source/log; ARM64-only.", flush=True)
        print("APK: " + str(destination), flush=True)
        print("SHA256: " + hashlib.sha256(destination.read_bytes()).hexdigest(), flush=True)
    finally:
        os.unlink(local_input)


if __name__ == "__main__":
    main()
