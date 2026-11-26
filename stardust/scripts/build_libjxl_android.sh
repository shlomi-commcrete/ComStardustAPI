#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  build_libjxl_android.sh
#
#  Builds libjxl (static) for every Android ABI the stardust module ships, for
#  PigN method 4 (JPEG_JXL). Same job as build_libjxl_android.ps1, for Linux /
#  macOS / Git Bash — and for JitPack, which has no libjxl and builds releases
#  from a clean checkout (see ../../jitpack.yml).
#
#  Output: $JXL_OUT/install/<abi>/lib/pkgconfig/libjxl.pc  (the layout
#  STARDUST_JXL_PREFIX expects). Idempotent: an ABI that is already installed
#  is skipped.
#
#  Environment (all optional except the SDK):
#    ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK. NDK and CMake are installed
#                                     into it with sdkmanager when missing.
#    JXL_OUT          default $HOME/libjxl-android. No spaces: pkg-config splits
#                     the -I paths of libjxl.pc at a space and the compile then
#                     fails with "'jxl/decode.h' file not found".
#    JXL_TAG          libjxl release, default v0.11.1
#    JXL_ABIS         default "arm64-v8a armeabi-v7a x86_64"
#    JXL_SRC          an existing libjxl checkout (with submodules) to build from
#    JXL_NDK_VERSION  default 25.1.8937393 (what AGP 8.2 picks, so Gradle reuses it)
#    JXL_CMAKE_VERSION default 3.22.1 (the version stardust/build.gradle.kts asks for)
#    PKG_CONFIG       pkg-config to use; built from source when none is found
#
#  --write-gradle-properties  append stardustJxlPrefix (and stardustPkgConfig)
#                             to ~/.gradle/gradle.properties, which is how the
#                             Gradle build finds the result.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

OUT="${JXL_OUT:-$HOME/libjxl-android}"
TAG="${JXL_TAG:-v0.11.1}"
ABIS="${JXL_ABIS:-arm64-v8a armeabi-v7a x86_64}"
API=26 # the stardust module's minSdk
NDK_VERSION="${JXL_NDK_VERSION:-25.1.8937393}"
CMAKE_VERSION="${JXL_CMAKE_VERSION:-3.22.1}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
WRITE_PROPS=0
[ "${1:-}" = "--write-gradle-properties" ] && WRITE_PROPS=1

case "$OUT" in *" "*) echo "JXL_OUT must not contain spaces (got '$OUT')" >&2; exit 1 ;; esac
[ -n "$SDK" ] || { echo "ANDROID_HOME (or ANDROID_SDK_ROOT) is not set" >&2; exit 1; }
JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)"

# ── SDK pieces ────────────────────────────────────────────────────────────────
sdkmanager_bin() {
    local c
    for c in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK"/cmdline-tools/*/bin/sdkmanager "$SDK/tools/bin/sdkmanager"; do
        [ -x "$c" ] && { echo "$c"; return 0; }
    done
    command -v sdkmanager
}

sdk_install() { # sdk_install "<package>"
    local sm
    sm="$(sdkmanager_bin)" || { echo "'$1' is missing and there is no sdkmanager to install it" >&2; exit 1; }
    echo "== installing $1"
    yes | "$sm" --sdk_root="$SDK" "$1" >/dev/null
}

NDK="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}"
[ -d "$NDK" ] || { sdk_install "ndk;$NDK_VERSION"; NDK="$SDK/ndk/$NDK_VERSION"; }
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[ -f "$TOOLCHAIN" ] || { echo "NDK toolchain file not found: $TOOLCHAIN" >&2; exit 1; }

CMAKE_DIR="$SDK/cmake/$CMAKE_VERSION/bin"
[ -d "$CMAKE_DIR" ] || sdk_install "cmake;$CMAKE_VERSION" || true
if [ -d "$CMAKE_DIR" ]; then
    CMAKE="$CMAKE_DIR/cmake"
    NINJA="$(ls "$CMAKE_DIR"/ninja "$CMAKE_DIR"/ninja.exe 2>/dev/null | head -n1)"
else
    CMAKE="$(command -v cmake)"
    NINJA="$(command -v ninja)"
fi
[ -n "${NINJA:-}" ] || { echo "ninja not found" >&2; exit 1; }
echo "cmake: $CMAKE"; echo "ndk:   $NDK"

# ── pkg-config: PigN's CMake finds libjxl through it ──────────────────────────
PKGCONF="${PKG_CONFIG:-$(command -v pkg-config || command -v pkgconf || true)}"
if [ -z "$PKGCONF" ]; then
    echo "== no pkg-config: building pkgconf 2.3.0"
    tools="$OUT/tools"; mkdir -p "$tools"
    ( cd "$tools" \
        && curl -fsSL "https://distfiles.ariadne.space/pkgconf/pkgconf-2.3.0.tar.xz" | tar xJ \
        && cd pkgconf-2.3.0 \
        && ./configure --prefix="$tools/pkgconf" --disable-shared >/dev/null \
        && make -j"$JOBS" >/dev/null && make install >/dev/null \
        && ln -sf pkgconf "$tools/pkgconf/bin/pkg-config" )
    PKGCONF="$tools/pkgconf/bin/pkg-config"
fi

# ── libjxl source ─────────────────────────────────────────────────────────────
SRC="${JXL_SRC:-$OUT/src}"
if [ ! -f "$SRC/CMakeLists.txt" ]; then
    mkdir -p "$OUT"
    git clone --depth 1 --branch "$TAG" --recurse-submodules --shallow-submodules \
        https://github.com/libjxl/libjxl.git "$SRC"
fi

# ── one static install per ABI ────────────────────────────────────────────────
for abi in $ABIS; do
    build="$OUT/build/$abi"; install="$OUT/install/$abi"
    if [ -f "$install/lib/pkgconfig/libjxl.pc" ]; then echo "== $abi already built, skipping"; continue; fi
    echo "== $abi"
    # Only the library: no tools, tests, plugins or JPEG codecs we don't call. JPEG transcoding (what
    # PigN uses) is part of libjxl itself and needs none of them.
    "$CMAKE" -S "$SRC" -B "$build" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" \
        -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$install" \
        -DBUILD_SHARED_LIBS=OFF -DJPEGXL_STATIC=ON -DBUILD_TESTING=OFF \
        -DJPEGXL_ENABLE_TOOLS=OFF -DJPEGXL_ENABLE_VIEWERS=OFF -DJPEGXL_ENABLE_PLUGINS=OFF \
        -DJPEGXL_ENABLE_DEVTOOLS=OFF -DJPEGXL_ENABLE_BENCHMARK=OFF -DJPEGXL_ENABLE_EXAMPLES=OFF \
        -DJPEGXL_ENABLE_MANPAGES=OFF -DJPEGXL_ENABLE_DOXYGEN=OFF -DJPEGXL_ENABLE_JNI=OFF \
        -DJPEGXL_ENABLE_SJPEG=OFF -DJPEGXL_ENABLE_OPENEXR=OFF -DJPEGXL_ENABLE_JPEGLI=OFF \
        -DJPEGXL_ENABLE_TCMALLOC=OFF -DJPEGXL_ENABLE_COVERAGE=OFF -DJPEGXL_ENABLE_FUZZERS=OFF \
        -DJPEGXL_BUNDLE_LIBPNG=OFF -DJPEGXL_ENABLE_SKCMS=ON
    "$CMAKE" --build "$build" --target install -j "$JOBS"
    [ -f "$install/lib/pkgconfig/libjxl.pc" ] || { echo "$abi: libjxl.pc was not installed" >&2; exit 1; }
    rm -rf "$build" # only the install is used; the build tree is several hundred MB
done

echo
echo "Done."
echo "  stardustJxlPrefix=$OUT/install"
echo "  stardustPkgConfig=$PKGCONF"

if [ "$WRITE_PROPS" = 1 ]; then
    props="$HOME/.gradle/gradle.properties"; mkdir -p "$(dirname "$props")"; touch "$props"
    for kv in "stardustJxlPrefix=$OUT/install" "stardustPkgConfig=$PKGCONF"; do
        grep -qxF "$kv" "$props" || echo "$kv" >> "$props"
    done
    echo "written to $props"
fi
