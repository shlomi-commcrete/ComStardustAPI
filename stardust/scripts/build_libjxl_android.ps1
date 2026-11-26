# Builds libjxl (static) for every Android ABI the stardust module ships, for PigN method 4 (JPEG_JXL).
#
#   .\scripts\build_libjxl_android.ps1                 # -> C:\libjxl-android\install\<abi>
#   .\scripts\build_libjxl_android.ps1 -OutDir D:\jxl  # somewhere else (the path must have no spaces)
#
# Then build the SDK with the printed property, or put it in ~/.gradle/gradle.properties:
#   stardustJxlPrefix=C:/libjxl-android/install
#
# Needs: git, the Android SDK's CMake 3.22.1 (ships ninja) and an NDK, and pkg-config on PATH
# (MSYS2: pacman -S mingw-w64-x86_64-pkgconf; PigN's CMake finds libjxl through it). Gradle must see
# pkg-config too: add C:\msys64\mingw64\bin to PATH (or set it for the build) before running gradlew.
# Both radios must get the same build: a receiver without method 4 fails a JPEG_JXL image with ERR_METHOD.
param(
    [string]$OutDir     = "C:\libjxl-android",   # no spaces allowed, see below
    [string]$Tag        = "v0.11.1",
    [string[]]$Abis     = @("arm64-v8a", "armeabi-v7a", "x86_64"),
    [int]$ApiLevel      = 26,                      # the stardust module's minSdk
    [string]$Sdk        = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }),
    [string]$Ndk        = ""
)
$ErrorActionPreference = "Stop"

# pkg-config splits the unquoted -I / -L paths in libjxl.pc at a space, and PigN's CMake finds libjxl through it:
# an install under "C:\Users\First Last\..." fails to compile with "'jxl/decode.h' file not found".
if ($OutDir -match '\s') { throw "-OutDir must not contain spaces (got '$OutDir'); use e.g. -OutDir C:\libjxl-android" }

# ---- tools ---------------------------------------------------------------------------------------------
$cmakeBin = Get-ChildItem (Join-Path $Sdk "cmake") -Directory | Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "bin" } | Where-Object { Test-Path (Join-Path $_ "ninja.exe") } | Select-Object -First 1
if (-not $cmakeBin) { throw "No Android SDK CMake with ninja.exe under $Sdk\cmake (install it from the SDK Manager)" }
$cmake = Join-Path $cmakeBin "cmake.exe"
$ninja = Join-Path $cmakeBin "ninja.exe"

if (-not $Ndk) {
    $Ndk = Get-ChildItem (Join-Path $Sdk "ndk") -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
$toolchain = Join-Path $Ndk "build\cmake\android.toolchain.cmake"
if (-not (Test-Path $toolchain)) { throw "NDK toolchain file not found: $toolchain" }

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "git not found" }
Write-Host "cmake: $cmake`nndk:   $Ndk"

# ---- source --------------------------------------------------------------------------------------------
$src = Join-Path $OutDir "src"
if (-not (Test-Path (Join-Path $src "CMakeLists.txt"))) {
    New-Item -ItemType Directory -Force $OutDir | Out-Null
    git clone --depth 1 --branch $Tag --recurse-submodules --shallow-submodules https://github.com/libjxl/libjxl.git $src
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}

# ---- one static install per ABI ------------------------------------------------------------------------
foreach ($abi in $Abis) {
    $build   = Join-Path $OutDir "build\$abi"
    $install = Join-Path $OutDir "install\$abi"
    if (Test-Path (Join-Path $install "lib\pkgconfig\libjxl.pc")) { Write-Host "== $abi already built, skipping"; continue }
    Write-Host "== $abi"

    # Only the library: no tools, tests, plugins or JPEG codecs we don't call. JPEG transcoding (what
    # PigN uses) is part of libjxl itself and needs none of them.
    & $cmake -S $src -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" "-DANDROID_PLATFORM=android-$ApiLevel" `
        "-DANDROID_STL=c++_static" -DCMAKE_BUILD_TYPE=Release "-DCMAKE_INSTALL_PREFIX=$install" `
        -DBUILD_SHARED_LIBS=OFF -DJPEGXL_STATIC=ON -DBUILD_TESTING=OFF `
        -DJPEGXL_ENABLE_TOOLS=OFF -DJPEGXL_ENABLE_VIEWERS=OFF -DJPEGXL_ENABLE_PLUGINS=OFF `
        -DJPEGXL_ENABLE_DEVTOOLS=OFF -DJPEGXL_ENABLE_BENCHMARK=OFF -DJPEGXL_ENABLE_EXAMPLES=OFF `
        -DJPEGXL_ENABLE_MANPAGES=OFF -DJPEGXL_ENABLE_DOXYGEN=OFF -DJPEGXL_ENABLE_JNI=OFF `
        -DJPEGXL_ENABLE_SJPEG=OFF -DJPEGXL_ENABLE_OPENEXR=OFF -DJPEGXL_ENABLE_JPEGLI=OFF `
        -DJPEGXL_ENABLE_TCMALLOC=OFF -DJPEGXL_ENABLE_COVERAGE=OFF -DJPEGXL_ENABLE_FUZZERS=OFF `
        -DJPEGXL_BUNDLE_LIBPNG=OFF -DJPEGXL_ENABLE_SKCMS=ON
    if ($LASTEXITCODE -ne 0) { throw "configure failed for $abi" }
    & $cmake --build $build --target install
    if ($LASTEXITCODE -ne 0) { throw "build failed for $abi" }
    if (-not (Test-Path (Join-Path $install "lib\pkgconfig\libjxl.pc"))) { throw "${abi}: libjxl.pc was not installed" }
}

$prefix = (Join-Path $OutDir "install") -replace '\\', '/'
Write-Host "`nDone. Build the SDK with:  -PstardustJxlPrefix=$prefix"
Write-Host "or add to ~/.gradle/gradle.properties:  stardustJxlPrefix=$prefix"
