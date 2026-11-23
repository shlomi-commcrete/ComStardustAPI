cmake_minimum_required(VERSION 3.22.1)
project(stardust_native C)

find_library(log-lib log)

# ─── PigN compression library (always built) ─────────────────────────────────
set(PIGN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/PigN)
add_subdirectory(${PIGN_DIR} ${CMAKE_CURRENT_BINARY_DIR}/pign)

add_library(pign_jni SHARED pign_jni.c)
target_link_libraries(pign_jni PRIVATE pign ${log-lib})

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
