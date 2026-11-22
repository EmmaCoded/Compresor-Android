# Compresor

Open-source Android app to compress, cut, crop and convert your videos and audio files **on your phone, offline**.
It runs its own FFmpeg 7.1.1 build (x264, Opus, LAME, AV1), so nothing is uploaded anywhere.

The interface is available in **English and Spanish** (it follows the phone language, or pick one in Options).

## Features

- **Compress video** with three modes:
  - **Size**: pick a resolution from a list with the approximate resulting size, or type your own target in MB
    (2-pass x264, the size is verified and retried up to 3 times if it comes out too big).
  - **Quality**: resolution, frame rate and CRF or bitrate.
  - **Advanced**: encoder preset and speed, 2 passes, GOP, threads and extra encoder parameters.
- **Codecs**: x264 (H.264, fast, plays everywhere) or AV1 (maximum compression, very slow; SVT-AV1 on 64-bit phones, libaom on 32-bit ones).
- **Cut video**: a time bar with zoom, start/end handles, a frame line, fast cut (no re-encoding) or precise cut.
- **Crop video**: crop rectangle with aspect ratios and rotate left/right.
- **Compress or convert audio** to MP3, Opus, OGG (Opus) or AAC (M4A), keeping cover art and tags unless you remove them.
- Runs as a foreground service: pause, stop, leave it in the background and follow the progress in the notification.
- Your original files are **never deleted or modified**; results are saved to `DCIM/Compresor_CC` and `Music/Compresor_CC` (or any folder you choose).
- Dark theme by default, light theme available.

## Two APKs

| APK | Phones | AV1 encoder |
| --- | --- | --- |
| `arm64-v8a` | 64-bit | SVT-AV1 v2.3.0 |
| `armeabi-v7a` | 32-bit | libaom (about 13x slower, expect hours) |

Minimum Android version: 5.0 (API 21).

## Building

Requirements: JDK 17, Gradle 8.10.2, Android SDK with platform 35 (put its path in `local.properties` using `/` separators).

1. Build the FFmpeg binaries (x264, Opus, LAME, SVT-AV1/libaom) for each ABI in WSL Debian or any Linux with `curl`, `git`, `make`, `cmake` and `pkg-config`:

   ```
   bash tools/build_ffmpeg.sh arm64-v8a
   bash tools/build_ffmpeg.sh armeabi-v7a
   ```

   Copy `ffmpeg` and `ffprobe` from `~/ffbuild/out/<abi>/` to `app/src/main/jniLibs/<abi>/` as `libffmpeg.so` and `libffprobe.so`
   (they ship as `.so` files so Android extracts them to the native library folder, where they can be executed).

2. Build and test:

   ```
   gradle :app:testDebugUnitTest :app:assembleDebug
   ```

   The APKs are written to `app/build/outputs/apk/debug/`. Add `-Pemulator` to also build an x86_64 APK (no FFmpeg inside).

The design tokens (colors, spacing, sizes) live in `design/tokens.json`; regenerate `Tokens.kt` with `py -3 tools/gen_tokens.py`.

## Project layout

- `app/src/main/java/app/compresor/engine`: pure Kotlin logic (FFmpeg commands, size estimation, cut/crop math, names). Covered by JVM unit tests.
- `app/src/main/java/app/compresor/execution`: FFmpeg process control, probing, the foreground service and the job queue.
- `app/src/main/java/app/compresor/ui`: Jetpack Compose screens.
- `Language.kt` and `SpanishStrings.kt`: the English text in the code is the key; Spanish lives in the `SPANISH` map.

## License

GPL-3.0 (the bundled FFmpeg build includes x264, which is GPL). See `LICENSE`.
