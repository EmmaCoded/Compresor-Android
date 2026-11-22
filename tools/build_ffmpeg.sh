#!/usr/bin/env bash
# Builds ffmpeg + ffprobe for Android (x264 + Opus + LAME + SVT-AV1/libaom). Run in WSL Debian:
#   bash tools/build_ffmpeg.sh arm64-v8a        # 64 bit, AV1 with SVT-AV1
#   bash tools/build_ffmpeg.sh armeabi-v7a      # 32 bit, AV1 with libaom (very slow)
# Output: ~/ffbuild/out/<abi>/{ffmpeg,ffprobe}
set -euo pipefail
trap 'echo "FAILED at line $LINENO"' ERR
ABI="${1:-arm64-v8a}"
W="$HOME/ffbuild"; mkdir -p "$W/src" "$W/out/$ABI"
NDK_V=r27c; NDK="$W/android-ndk-$NDK_V"
API=21
if [ "$ABI" = arm64-v8a ]; then TRIPLE=aarch64-linux-android; ARCH=aarch64; FFARCH=aarch64; AV1=1
else TRIPLE=armv7a-linux-androideabi; ARCH=arm; FFARCH=arm; AV1=0; fi
cd "$W"
[ -d "$NDK" ] || { curl -sLO "https://dl.google.com/android/repository/android-ndk-$NDK_V-linux.zip"; unzip -q android-ndk-$NDK_V-linux.zip; rm android-ndk-$NDK_V-linux.zip; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TC/bin/${TRIPLE}${API}-clang"; CXX="$TC/bin/${TRIPLE}${API}-clang++"
AR="$TC/bin/llvm-ar"; RANLIB="$TC/bin/llvm-ranlib"; STRIP="$TC/bin/llvm-strip"; NM="$TC/bin/llvm-nm"
P="$W/prefix/$ABI"; mkdir -p "$P"
export PKG_CONFIG_LIBDIR="$P/lib/pkgconfig"; export PKG_CONFIG_PATH=""
LDX="-Wl,-z,max-page-size=16384"   # 16 KB pages (newer phones)

cd "$W/src"
# --- x264
[ -d x264 ] || git clone -q --depth 1 https://code.videolan.org/videolan/x264.git
( cd x264 && make distclean >/dev/null 2>&1 || true
  CC="$CC" AS="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
  ./configure --host=$TRIPLE --enable-static --disable-cli --disable-opencl --enable-pic \
    --prefix="$P" CC="$CC" AS="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
    --extra-cflags="-O2" --extra-ldflags="$LDX" >/dev/null && make -j8 >/dev/null && make install >/dev/null )
echo "x264 OK"
# --- opus
[ -d opus-1.5.2 ] || { curl -sLO https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz; tar xf opus-1.5.2.tar.gz; }
( cd opus-1.5.2 && make distclean >/dev/null 2>&1 || true
  ./configure --host=$TRIPLE --disable-shared --enable-static --disable-doc --disable-extra-programs \
    --prefix="$P" CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" LDFLAGS="$LDX" >/dev/null && make -j8 >/dev/null && make install >/dev/null )
echo "opus OK"
# --- LAME (MP3 encoder)
[ -d lame-3.100 ] || { curl -sL -o lame.tar.gz https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz; tar xf lame.tar.gz; rm -f lame.tar.gz; }
( cd lame-3.100 && make distclean >/dev/null 2>&1 || true
  ./configure --host=$TRIPLE --disable-shared --enable-static --disable-frontend --disable-decoder --disable-gtktest \
    --prefix="$P" CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" CFLAGS="-O2 -fPIC" LDFLAGS="$LDX" >/dev/null && make -j8 >/dev/null && make install >/dev/null )
echo "lame OK"
# --- SVT-AV1 (64 bit only)
EXTRA=""
if [ "$AV1" = 1 ]; then
  [ -d SVT-AV1 ] || git clone -q --depth 1 --branch v2.3.0 https://gitlab.com/AOMediaCodec/SVT-AV1.git
  rm -rf "SVT-AV1/build-$ABI"; mkdir "SVT-AV1/build-$ABI"
  ( cd "SVT-AV1/build-$ABI" && cmake .. -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
      -DBUILD_APPS=OFF -DBUILD_DEC=OFF -DBUILD_TESTING=OFF -DCMAKE_INSTALL_PREFIX="$P" \
      -DCMAKE_SHARED_LINKER_FLAGS="$LDX" -DCMAKE_EXE_LINKER_FLAGS="$LDX" >/dev/null && make -j8 >/dev/null && make install >/dev/null )
  echo "svtav1 OK"; EXTRA="--enable-libsvtav1"
fi
# --- libaom (AV1 for 32 bit, where SVT-AV1 does not exist)
if [ "$AV1" = 0 ]; then
  [ -d aom ] || git clone -q --depth 1 --branch v3.11.0 https://aomedia.googlesource.com/aom
  rm -rf "aom/build-$ABI"; mkdir "aom/build-$ABI"
  ( cd "aom/build-$ABI" && cmake .. -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"       -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API -DANDROID_ARM_NEON=ON -DCMAKE_BUILD_TYPE=Release       -DBUILD_SHARED_LIBS=0 -DENABLE_DOCS=0 -DENABLE_TESTS=0 -DENABLE_EXAMPLES=0 -DENABLE_TOOLS=0       -DCONFIG_RUNTIME_CPU_DETECT=0 -DCONFIG_PIC=1 -DCMAKE_INSTALL_PREFIX="$P"       -DCMAKE_SHARED_LINKER_FLAGS="$LDX" -DCMAKE_EXE_LINKER_FLAGS="$LDX" >/dev/null && make -j8 >/dev/null && make install >/dev/null )
  echo "libaom OK"; EXTRA="--enable-libaom"
fi
# --- ffmpeg
[ -d ffmpeg-7.1.1 ] || { curl -sLO https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz; tar xf ffmpeg-7.1.1.tar.xz; }
cd ffmpeg-7.1.1; make distclean >/dev/null 2>&1 || true
./configure --prefix="$W/out/$ABI/inst" --enable-cross-compile --target-os=android --arch=$FFARCH \
  --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --nm="$NM" \
  --pkg-config=pkg-config --pkg-config-flags=--static \
  --enable-gpl --enable-libx264 --enable-libopus --enable-libmp3lame $EXTRA \
  --disable-doc --disable-ffplay --enable-pic --disable-debug --disable-network \
  --extra-cflags="-I$P/include -O2" --extra-ldflags="-L$P/lib $LDX -static-libstdc++" >"$W/out/$ABI/configure.log" 2>&1
make -j8 >"$W/out/$ABI/make.log" 2>&1
cp ffmpeg ffprobe "$W/out/$ABI/"; "$STRIP" "$W/out/$ABI/ffmpeg" "$W/out/$ABI/ffprobe"
ls -la "$W/out/$ABI/"; echo "TERMINADO $ABI"
