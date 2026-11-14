# Third-party notices and build provenance

MuktoCut's own application code is licensed under **GPL-3.0-or-later** (`LICENSE`).
The distributed APKs combine that code with the FFmpegKit Android **full-gpl**
binary, so the combined work is distributed under GPL-3.0. The app assets include
the bundled GPL/LGPL and external-library license texts under
`android/src/main/assets/licenses/`.

This project does **not** copy CapCut/ByteDance branding, paid assets, or
proprietary effects. It is an independent editor inspired by familiar timeline
editing workflows.

## Runtime dependency

| Component | Version / variant | License | Source / binary provenance |
|---|---|---|---|
| FFmpegKit Java API | upstream `v6.0.LTS`, compiled from source in this build | LGPL-3.0-or-later | <https://github.com/arthenica/ffmpeg-kit/tree/v6.0.LTS>. Source archive SHA-256 `22fe0791ed5fa9cac8d55b598e3776e2befa1daa2efa3dfbb396e82f0011aade`. |
| FFmpegKit native libraries | 6.0 LTS full-gpl, NDK r27d rebuild; arm64-v8a ELF `p_align = 0x4000` verified for every shipped `.so` | Combined FFmpeg build GPL-3.0 | <https://github.com/VineshChauhan24/ffmpeg-kit-android-16kb-lts-full-gpl>, commit `842bd28527958e8ab97130e8f437ef7266bee1d2`, archive SHA-256 `60738bfde3879c98a5aa256bbc2749152552a9931916082b9f002ef956d63ac4`. The older 32-bit `armeabi-v7a` APK is for legacy 4 KB-page phones. |
| Smart Exception | common/java 0.2.1 | See upstream | <https://github.com/arthenica/smart-exception>. JARs fetched from the pinned FFmpegKit Flutter mirror `nightmarefsm007/ffmpeg-kit-new@2a7bb33f5658ca690b333c153628b1ecc5752300`. |
| FFmpeg external libraries | as enabled in the full-gpl build | Individual licenses bundled in `license_*.txt` | License texts were collected from a full-gpl FFmpegKit AAR and are bundled unchanged. Upstream corresponding-source links: <https://github.com/arthenica/ffmpeg-kit/wiki/Source>. |

The FFmpegKit project has been retired upstream and its old Maven artifacts are
no longer reliably available. The build script therefore compiles the pinned
upstream Java API from source and downloads a pinned, hash-checked community
native rebuild. These mirrors/rebuilds are not official endorsements. For a
public production release, audit/rebuild FFmpegKit from corresponding source
with your own toolchain and replace the development signing key.

## Build-time tools (not shipped in the APK runtime)

The Arena sandbox only allows GitHub and PyPI outbound hosts, so
`scripts/build_apk.py` uses these pinned mirrors/artifacts:

| Tool | Provenance | Notes |
|---|---|---|
| Android platform `android.jar` API 35 | `Sable/android-platforms@1e98db1a199e8f7f85541af26bfc27019501b132`, `android-35/android.jar`, SHA-256 `4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b` | Compile-time stubs only. |
| Android SDK build-tools | `ag-sdc-ci/prebuilts_host_sdk-build-tools@399b0d3d0019f04d8b70b81b9a2298fec1b2c456`, archive SHA-256 `a52cfd0b4413a43af88af734599bfea87f9db7f8fd36b57742ed153c6d8b2093` | AOSP host prebuilt shadow; aapt/aapt2/d8/apksigner/zipalign. |
| Eclipse Compiler for Java | `Shiniga-OP/Compilador-de-Java-para-DEX@313e8b851f2bece4511722198faa7f3877e17b88`, `ecj-4.8.jar`, SHA-256 `5d53b25fd09b8d83157634af682b4c64bce9def70f6533f81d9115526057ae69` | EPL-1.0. Used because the PyPI JDK package is a runtime without `javac`. |
| Java runtime | PyPI `jdk4py==17.0.9.2` (Temurin 17.0.9) | GPL-2.0 with Classpath exception. |

## Signing

`signing/debug.keystore` is a **public development key** with the standard
debug-style password `android` and alias `androiddebugkey`. It is included only
so repeated sideload builds from this repository can update the same package.
Do not use it for a production/store release. Generate and protect a private
release key before publishing broadly.
