cmake_minimum_required(VERSION 3.22.1)
project(starmark_jpeg C)

# The upstream build deliberately disallows add_subdirectory. Extract a verified, vendored
# archive into the build directory and build only its static libjpeg target. No network is used.
set(MOZJPEG_ARCHIVE "${CMAKE_CURRENT_SOURCE_DIR}/third_party/mozjpeg-4.1.5.tar.gz")
set(MOZJPEG_SHA256 "9fcbb7171f6ac383f5b391175d6fb3acde5e64c4c4727274eade84ed0998fcc1")
file(SHA256 "${MOZJPEG_ARCHIVE}" MOZJPEG_ACTUAL_SHA256)
if(NOT MOZJPEG_ACTUAL_SHA256 STREQUAL MOZJPEG_SHA256)
    message(FATAL_ERROR "Vendored mozjpeg archive checksum mismatch")
endif()
set(MOZJPEG_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/vendor/mozjpeg-4.1.5")
set(MOZJPEG_BINARY "${CMAKE_CURRENT_BINARY_DIR}/mozjpeg-build")
if(NOT EXISTS "${MOZJPEG_SOURCE}/CMakeLists.txt")
    file(ARCHIVE_EXTRACT INPUT "${MOZJPEG_ARCHIVE}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/vendor")
endif()
file(MAKE_DIRECTORY "${MOZJPEG_BINARY}")

# ARM NEON is compiler built-in. x86 SIMD would add an external NASM dependency; use its C
# implementation on x86 emulators so all four Android ABIs build with the stock NDK alone.
set(MOZJPEG_SIMD OFF)
if(ANDROID_ABI MATCHES "^(arm64-v8a|armeabi-v7a)$")
    set(MOZJPEG_SIMD ON)
endif()
include(ExternalProject)
ExternalProject_Add(mozjpeg_build
    SOURCE_DIR "${MOZJPEG_SOURCE}"
    BINARY_DIR "${MOZJPEG_BINARY}"
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DANDROID_STL=none"
        "-DCMAKE_BUILD_TYPE=Release"
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DENABLE_SHARED=OFF"
        "-DENABLE_STATIC=ON"
        "-DWITH_TURBOJPEG=OFF"
        "-DWITH_JAVA=OFF"
        "-DPNG_SUPPORTED=OFF"
        "-DWITH_SIMD=${MOZJPEG_SIMD}"
        "-DBUILD=20230227"
    BUILD_COMMAND "${CMAKE_COMMAND}" --build "${MOZJPEG_BINARY}" --target jpeg-static
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${MOZJPEG_BINARY}/libjpeg.a"
)
add_library(mozjpeg STATIC IMPORTED)
set_target_properties(mozjpeg PROPERTIES IMPORTED_LOCATION "${MOZJPEG_BINARY}/libjpeg.a")
add_dependencies(mozjpeg mozjpeg_build)

add_library(starmark_jpeg SHARED streaming_jpeg.c)
set_target_properties(starmark_jpeg PROPERTIES C_STANDARD 99 C_VISIBILITY_PRESET hidden)
target_include_directories(starmark_jpeg PRIVATE "${MOZJPEG_SOURCE}" "${MOZJPEG_BINARY}")
target_compile_options(starmark_jpeg PRIVATE -Wall -Wextra -Werror)
# Applies to every ABI and is explicit even though NDK r28 defaults to flexible page sizes.
target_link_options(starmark_jpeg PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "-Wl,--exclude-libs,ALL")
target_link_libraries(starmark_jpeg PRIVATE mozjpeg jnigraphics)
add_dependencies(starmark_jpeg mozjpeg_build)
