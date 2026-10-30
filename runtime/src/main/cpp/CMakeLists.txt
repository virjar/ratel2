cmake_minimum_required(VERSION 3.28)
project(ratel)

set(LSPLANT_BUILD_SHARED OFF CACHE INTERNAL "" FORCE)
set(DEX_BUILDER_BUILD_SHARED OFF CACHE INTERNAL "" FORCE)
set(DOBBY_GENERATE_SHARED OFF CACHE INTERNAL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/lsplant lsplant)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/dobby dobby)

# xz-embedded: .gnu_debugdata decompression (XZ_SINGLE mode, no internal allocation)
add_library(xz_static STATIC
    xz/src/xz_crc32.c
    xz/src/xz_dec_lzma2.c
    xz/src/xz_dec_stream.c
)
target_include_directories(xz_static PRIVATE xz/include/linux xz/userspace xz/src)

add_library(ratel SHARED ratel_jni.cpp ratel_native.cpp)
set_target_properties(ratel PROPERTIES CXX_STANDARD 23)
target_include_directories(ratel PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/dobby/include
    ${CMAKE_CURRENT_SOURCE_DIR}/xz/include/linux
    ${CMAKE_CURRENT_SOURCE_DIR}/xz/userspace
)
target_link_libraries(ratel lsplant_static dobby xz_static log)
