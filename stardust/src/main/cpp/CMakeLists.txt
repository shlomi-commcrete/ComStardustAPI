cmake_minimum_required(VERSION 3.22.1)
project(stardust_native C)

find_library(log-lib log)

# ─── PigN compression library (always built) ─────────────────────────────────
# Method 4 (JPEG_JXL — lossless JPEG recompression, ~18-20% smaller photos) is optional: it needs
# libjxl, which is C++ and must be built for every Android ABI first. It is on only when
# STARDUST_JXL_PREFIX is given (Gradle: -PstardustJxlPrefix=<dir>), a directory holding one libjxl
# install per ABI, each with lib/pkgconfig/libjxl.pc:
#     <prefix>/arm64-v8a/lib/pkgconfig/libjxl.pc
#     <prefix>/armeabi-v7a/...   <prefix>/x86_64/...
# Both radios must be built the same way: a receiver without it fails a method-4 image as ERROR
# (PigN.decompress → ERR_METHOD), so turn it on for the whole fleet or not at all.
set(STARDUST_JXL_PREFIX "" CACHE PATH "Directory with a libjxl install per ANDROID_ABI; empty = PigN without JPEG_JXL")
set(STARDUST_WITH_JXL OFF)
if(STARDUST_JXL_PREFIX)
    set(JXL_ABI_DIR ${STARDUST_JXL_PREFIX}/${ANDROID_ABI})
    if(NOT EXISTS ${JXL_ABI_DIR}/lib/pkgconfig/libjxl.pc)
        message(FATAL_ERROR "STARDUST_JXL_PREFIX is set but ${JXL_ABI_DIR}/lib/pkgconfig/libjxl.pc is missing")
    endif()
    set(STARDUST_WITH_JXL ON)
    enable_language(CXX) # libjxl is C++; PigN's pkg-config lookup and the final link need it
    set(PIGN_WITH_JXL ON CACHE BOOL "PigN method 4, JPEG_JXL" FORCE)
    # LIBDIR, not PATH: pkg-config must not fall back to a host libjxl (wrong architecture).
    set(ENV{PKG_CONFIG_LIBDIR} ${JXL_ABI_DIR}/lib/pkgconfig)
    message(STATUS "stardust: PigN built with JPEG_JXL from ${JXL_ABI_DIR}")
endif()

set(PIGN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/PigN)
add_subdirectory(${PIGN_DIR} ${CMAKE_CURRENT_BINARY_DIR}/pign)

add_library(pign_jni SHARED pign_jni.c)
target_link_libraries(pign_jni PRIVATE pign ${log-lib})
if(STARDUST_WITH_JXL)
    # libpign.a pulls in libjxl's C++ runtime: link with the C++ driver so libc++ comes along.
    set_target_properties(pign_jni PROPERTIES LINKER_LANGUAGE CXX)
    # PigN's -ljxl -lhwy -lbrotli* reach pign_jni through libpign.a, but its link directory does not
    # (target_link_directories is not transitive): without this the link fails with "unable to find library -ljxl".
    target_link_directories(pign_jni PRIVATE ${JXL_ABI_DIR}/lib)
    # The link line PigN hands over ends up with -lbrotlienc -lbrotlidec but without -lbrotlicommon (a
    # Requires.private of both), leaving BrotliGetDictionary etc. undefined. Static archives resolve left to
    # right, so it has to come after them: CMAKE_CXX_STANDARD_LIBRARIES goes at the very end of the link line.
    set(CMAKE_CXX_STANDARD_LIBRARIES "${CMAKE_CXX_STANDARD_LIBRARIES} ${JXL_ABI_DIR}/lib/libbrotlicommon.a")
endif()

# ─── RNNoise (optional, only when sources are present) ───────────────────────
set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

if(EXISTS ${RNNOISE_DIR}/include/rnnoise.h)
    file(GLOB RNNOISE_SOURCES CONFIGURE_DEPENDS ${RNNOISE_DIR}/src/*.c)
    list(FILTER RNNOISE_SOURCES EXCLUDE REGEX ".*[/\\\\]examples?[/\\\\].*")
    list(FILTER RNNOISE_SOURCES EXCLUDE REGEX ".*[/\\\\]dump_data\\.c$")
    list(FILTER RNNOISE_SOURCES EXCLUDE REGEX ".*[/\\\\]dump_features\\.c$")
    list(FILTER RNNOISE_SOURCES EXCLUDE REGEX ".*[/\\\\]compile\\.c$")
    list(FILTER RNNOISE_SOURCES EXCLUDE REGEX ".*[/\\\\]test_.+\\.c$")

    if(RNNOISE_SOURCES)
        include_directories(
            ${RNNOISE_DIR}/include
            ${RNNOISE_DIR}/src
        )
        add_library(rnnoise_jni SHARED
            rnnoise_jni.c
            ${RNNOISE_SOURCES}
        )
        target_compile_definitions(rnnoise_jni PRIVATE
            COMPILE_OPUS=0
            FLOAT_APPROX
        )
        target_compile_options(rnnoise_jni PRIVATE
            -O3
            -ffast-math
            -fvisibility=hidden
            -DNDEBUG
            -Wno-implicit-function-declaration
            -Wno-unused-variable
            -Wno-unused-function
        )
        target_link_options(rnnoise_jni PRIVATE
            "$<$<CONFIG:Release>:-Wl,--gc-sections>"
            "$<$<CONFIG:Release>:-Wl,--strip-all>"
        )
        target_link_libraries(rnnoise_jni ${log-lib})
    endif()
endif()
