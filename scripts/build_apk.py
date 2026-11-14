#!/usr/bin/env python3
"""Build signed MuktoCut APKs without Gradle/Android Studio.

Designed for the Arena sandbox's allowlisted network (GitHub + PyPI). It fetches
pinned build dependencies into .local/toolchain, compiles the native Java app,
runs JVM tests, packages per-ABI APKs, aligns native libraries for 16 KB pages,
signs with the repository's development key, and verifies the signatures.

Usage:
  python3 scripts/build_apk.py                 # arm64-v8a + armeabi-v7a
  python3 scripts/build_apk.py --abis arm64-v8a
  python3 scripts/build_apk.py --skip-tests

The signing key is a PUBLIC development key (password: android). Replace it with
a private release key before publishing outside this project.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / ".local" / "toolchain"
BUILD = ROOT / "build"
RELEASE = ROOT / "releases"
APP = ROOT / "android" / "src" / "main"
PKG = "com.yeaminul.muktocut"
VERSION_CODE = "2"
VERSION_NAME = "0.1.1"
MIN_SDK = "26"
TARGET_SDK = "35"

# Pinned upstream/mirror artifacts. Mirrors are used because the sandbox cannot
# reach dl.google.com / Maven Central. See THIRD_PARTY_NOTICES.md for provenance.
ANDROID_JAR = {
    "repo": "Sable/android-platforms",
    "ref": "1e98db1a199e8f7f85541af26bfc27019501b132",
    "path": "android-35/android.jar",
    "sha256": "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b",
}
BUILD_TOOLS = {
    "url": "https://codeload.github.com/ag-sdc-ci/prebuilts_host_sdk-build-tools/zip/399b0d3d0019f04d8b70b81b9a2298fec1b2c456",
    "sha256": "b251d7453e7f12d37bc44ccf24c26b503e63f7e6bca4fe3492016cf9c2ecfa71",
    "dir": "prebuilts_host_sdk-build-tools-399b0d3d0019f04d8b70b81b9a2298fec1b2c456",
}
ECJ = {
    "url": "https://codeload.github.com/Shiniga-OP/Compilador-de-Java-para-DEX/zip/313e8b851f2bece4511722198faa7f3877e17b88",
    "sha256": "206c719cfd7fb522e0803b1181e2a6db5f0ddaaba58ef13c27748228a183b063",
    "jar": "Compilador-de-Java-para-DEX-313e8b851f2bece4511722198faa7f3877e17b88/app/biblis/ecj-4.8.jar",
    "sha256_jar": "5d53b25fd09b8d83157634af682b4c64bce9def70f6533f81d9115526057ae69",
}
FFMPEG_NATIVE = {
    "url": "https://codeload.github.com/VineshChauhan24/ffmpeg-kit-android-16kb-lts-full-gpl/zip/842bd28527958e8ab97130e8f437ef7266bee1d2",
    "sha256": "60738bfde3879c98a5aa256bbc2749152552a9931916082b9f002ef956d63ac4",
}
FFMPEG_SOURCE = {
    "url": "https://codeload.github.com/arthenica/ffmpeg-kit/zip/refs/tags/v6.0.LTS",
    "sha256": "22fe0791ed5fa9cac8d55b598e3776e2befa1daa2efa3dfbb396e82f0011aade",
}
SMART_EXCEPTION = [
    {
        "path": "flutter/flutter/android/libs/smart-exception-common-0.2.1.jar",
        "out": "smart-exception-common.jar",
        "sha256": "1cad0fb4dfa01755a014331b5ed199281d2c3fab5aca5c9d7abd0b41d0ec3f7b",
    },
    {
        "path": "flutter/flutter/android/libs/smart-exception-java-0.2.1.jar",
        "out": "smart-exception-java.jar",
        "sha256": "5b96aaa5f191dedbef72fb0c38f1a2b01807920afc0d92a75a2acd6e0cc7703c",
    },
]
SMART_REF = "2a7bb33f5658ca690b333c153628b1ecc5752300"


def run(cmd: list[str | Path], **kwargs) -> subprocess.CompletedProcess:
    print("+", " ".join(str(x) for x in cmd), flush=True)
    return subprocess.run([str(x) for x in cmd], check=True, **kwargs)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def download(url: str, dest: Path, expected: str | None = None,
             headers: dict[str, str] | None = None) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and expected and sha256(dest) == expected:
        print(f"cached {dest.name}", flush=True)
        return
    req = urllib.request.Request(url, headers={"User-Agent": "MuktoCut-build", **(headers or {})})
    print(f"download {url}", flush=True)
    tmp = dest.with_suffix(dest.suffix + ".tmp")
    with urllib.request.urlopen(req, timeout=300) as response, tmp.open("wb") as out:
        shutil.copyfileobj(response, out, length=1024 * 1024)
    if expected and sha256(tmp) != expected:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"SHA-256 mismatch for {url}")
    tmp.replace(dest)


def github_file(repo: str, ref: str, path: str, dest: Path, expected: str) -> None:
    if dest.exists() and sha256(dest) == expected:
        print(f"cached {dest.name}", flush=True)
        return
    api = f"https://api.github.com/repos/{repo}/contents/{path}?ref={ref}"
    with urllib.request.urlopen(urllib.request.Request(api, headers={"User-Agent": "MuktoCut-build"}), timeout=60) as r:
        meta = json.load(r)
    download(meta["url"], dest, expected, {"Accept": "application/vnd.github.raw+json"})


def java_home() -> Path:
    home = TOOL / "python" / "jdk4py" / "java-runtime"
    if not (home / "bin" / "java").exists():
        run([sys.executable, "-m", "pip", "install", "--target", TOOL / "python",
             "jdk4py==17.0.9.2", "--disable-pip-version-check", "-q"])
    return home


def ensure_dependencies() -> dict[str, Path]:
    TOOL.mkdir(parents=True, exist_ok=True)
    downloads = TOOL / "downloads"
    downloads.mkdir(exist_ok=True)
    home = java_home()
    java = home / "bin" / "java"

    android_jar = TOOL / "android.jar"
    github_file(ANDROID_JAR["repo"], ANDROID_JAR["ref"], ANDROID_JAR["path"],
                android_jar, ANDROID_JAR["sha256"])

    tools_dir = TOOL / "build-tools"
    if not (tools_dir / "bin" / "aapt2").exists():
        archive = downloads / "build-tools.zip"
        download(BUILD_TOOLS["url"], archive, BUILD_TOOLS["sha256"])
        with zipfile.ZipFile(archive) as z:
            z.extractall(downloads)
        extracted = downloads / BUILD_TOOLS["dir"]
        if tools_dir.exists():
            shutil.rmtree(tools_dir)
        extracted.rename(tools_dir)
        for name in ("aapt", "aapt2", "aidl", "zipalign", "dexdump"):
            (tools_dir / "bin" / name).chmod(0o755)

    ecj = TOOL / "ecj.jar"
    if not ecj.exists():
        archive = downloads / "ecj.zip"
        download(ECJ["url"], archive, ECJ["sha256"])
        with zipfile.ZipFile(archive) as z:
            z.extractall(downloads / "ecj")
        shutil.copyfile(downloads / "ecj" / ECJ["jar"], ecj)
    if sha256(ecj) != ECJ["sha256_jar"]:
        raise RuntimeError("Unexpected ECJ jar hash")

    ffmpeg_dir = TOOL / "ffmpeg-native"
    if not (ffmpeg_dir / "jni" / "arm64-v8a" / "libffmpegkit.so").exists():
        archive = downloads / "ffmpeg-native.zip"
        download(FFMPEG_NATIVE["url"], archive, FFMPEG_NATIVE["sha256"])
        extract = downloads / "ffmpeg-native-extract"
        extract.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                if "/src/main/jniLibs/" in name:
                    z.extract(name, extract)
        base = next(p for p in extract.iterdir() if p.is_dir())
        shutil.copytree(base / "android" / "ffmpeg-kit-android-lib" / "src" / "main" / "jniLibs",
                        ffmpeg_dir / "jni", dirs_exist_ok=True)

    ffmpeg_java = TOOL / "ffmpeg-java"
    if not (ffmpeg_java / "com" / "arthenica" / "ffmpegkit" / "FFmpegKit.java").exists():
        archive = downloads / "ffmpeg-kit-source.zip"
        download(FFMPEG_SOURCE["url"], archive, FFMPEG_SOURCE["sha256"])
        extract = downloads / "ffmpeg-source-extract"
        extract.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                if "/android/ffmpeg-kit-android-lib/src/main/java/" in name:
                    z.extract(name, extract)
        base = next(p for p in extract.iterdir() if p.is_dir())
        shutil.copytree(base / "android" / "ffmpeg-kit-android-lib" / "src" / "main" / "java",
                        ffmpeg_java, dirs_exist_ok=True)

    smart_jars = []
    for item in SMART_EXCEPTION:
        dest = TOOL / item["out"]
        github_file("nightmarefsm007/ffmpeg-kit-new", SMART_REF, item["path"], dest, item["sha256"])
        smart_jars.append(dest)

    return {
        "java": java,
        "keytool": home / "bin" / "keytool",
        "android": android_jar,
        "tools": tools_dir,
        "ecj": ecj,
        "ffmpeg": ffmpeg_dir,
        "ffmpeg_java": ffmpeg_java,
        "smart": smart_jars,
    }


def compile_resources(dep: dict[str, Path]) -> Path:
    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True)
    res_zip = BUILD / "res.zip"
    gen = BUILD / "gen"
    gen.mkdir()
    unsigned = BUILD / "unsigned.apk"
    aapt2 = dep["tools"] / "bin" / "aapt2"
    run([aapt2, "compile", "--dir", APP / "res", "-o", res_zip])
    run([aapt2, "link", "-o", unsigned, "-I", dep["android"],
         "--manifest", APP / "AndroidManifest.xml", "-R", res_zip,
         "--java", gen, "-A", APP / "assets",
         "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
         "--version-code", VERSION_CODE, "--version-name", VERSION_NAME,
         "--auto-add-overlay"])
    return unsigned


def compile_java(dep: dict[str, Path], unsigned: Path, skip_tests: bool) -> None:
    classes = BUILD / "classes"
    classes.mkdir()
    cp = [dep["android"], *dep["smart"]]
    sources = (sorted((APP / "java").rglob("*.java"))
               + sorted((BUILD / "gen").rglob("*.java"))
               + sorted(dep["ffmpeg_java"].rglob("*.java")))
    run([dep["java"], "-jar", dep["ecj"], "-source", "1.8", "-target", "1.8",
         "-encoding", "UTF-8", "-proc:none", "-nowarn", "-g", "-classpath", os.pathsep.join(map(str, cp)),
         "-d", classes, *sources])

    if not skip_tests:
        test_classes = BUILD / "test-classes"
        test_classes.mkdir()
        tests = sorted((ROOT / "android" / "src" / "test" / "java").rglob("*.java"))
        run([dep["java"], "-jar", dep["ecj"], "-source", "1.8", "-target", "1.8",
             "-encoding", "UTF-8", "-proc:none", "-nowarn", "-classpath", classes,
             "-d", test_classes, *tests])
        test_cp = os.pathsep.join([str(classes), str(test_classes)])
        run([dep["java"], "-cp", test_cp, f"{PKG}.EditProjectTest"])
        run([dep["java"], "-cp", test_cp, f"{PKG}.ExportPlannerTest"])

    dex = BUILD / "dex"
    dex.mkdir()
    class_files = sorted(classes.rglob("*.class"))
    run([dep["java"], "-cp", dep["tools"] / "lib" / "d8.jar", "com.android.tools.r8.D8",
         "--release", "--min-api", MIN_SDK, "--lib", dep["android"], "--output", dex,
         *class_files, *dep["smart"]])
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as z:
        for dex_file in sorted(dex.glob("*.dex")):
            z.write(dex_file, dex_file.name)


def ensure_key(dep: dict[str, Path]) -> Path:
    key = ROOT / "signing" / "debug.keystore"
    key.parent.mkdir(exist_ok=True)
    if not key.exists():
        run([dep["keytool"], "-genkeypair", "-keystore", key, "-storepass", "android",
             "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=MuktoCut Debug, O=MuktoCut, C=BD"])
    return key


def package(dep: dict[str, Path], unsigned: Path, abis: list[str], key: Path) -> None:
    RELEASE.mkdir(exist_ok=True)
    apksigner = dep["tools"] / "lib" / "apksigner.jar"
    zipalign = dep["tools"] / "bin" / "zipalign"
    for abi in abis:
        native = dep["ffmpeg"] / "jni" / abi
        if not native.exists():
            raise RuntimeError(f"FFmpeg AAR has no ABI: {abi}")
        unaligned = BUILD / f"{abi}-unaligned.apk"
        aligned = BUILD / f"{abi}-aligned.apk"
        final = RELEASE / f"MuktoCut-{VERSION_NAME}-{abi}.apk"
        with zipfile.ZipFile(unsigned, "r") as src, zipfile.ZipFile(unaligned, "w") as out:
            for info in src.infolist():
                out.writestr(info, src.read(info.filename))
            for so in sorted(native.glob("*.so")):
                info = zipfile.ZipInfo(f"lib/{abi}/{so.name}")
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o644 << 16
                out.writestr(info, so.read_bytes())
        run([zipalign, "-f", "-P", "16", "4", unaligned, aligned])
        run([dep["java"], "-jar", apksigner, "sign", "--ks", key,
             "--ks-pass", "pass:android", "--key-pass", "pass:android",
             "--ks-key-alias", "androiddebugkey", "--v4-signing-enabled", "false",
             "--out", final, aligned])
        run([dep["java"], "-jar", apksigner, "verify", "--verbose", final])
        run([zipalign, "-c", "-P", "16", "4", final])
        print(f"\nAPK {final.name}: {final.stat().st_size / 1024 / 1024:.1f} MiB")
        print(f"SHA-256 {sha256(final)}\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abis", default="arm64-v8a,armeabi-v7a")
    parser.add_argument("--skip-tests", action="store_true")
    parser.add_argument("--deps-only", action="store_true")
    args = parser.parse_args()
    abis = [a.strip() for a in args.abis.split(",") if a.strip()]
    dep = ensure_dependencies()
    if args.deps_only:
        print("Dependencies ready in", TOOL)
        return
    unsigned = compile_resources(dep)
    compile_java(dep, unsigned, args.skip_tests)
    key = ensure_key(dep)
    package(dep, unsigned, abis, key)
    run([dep["tools"] / "bin" / "aapt", "dump", "badging",
         RELEASE / f"MuktoCut-{VERSION_NAME}-{abis[0]}.apk"])


if __name__ == "__main__":
    main()
