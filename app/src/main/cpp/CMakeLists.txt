cmake_minimum_required(VERSION 3.22.1)
project(connectanytime C)
set(VENDOR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../Vendor/out/${ANDROID_ABI}")
add_library(connectanytime SHARED ocbridge.c jni.c)
target_include_directories(connectanytime PRIVATE "${VENDOR}/include" "${VENDOR}/include/libxml2")
target_compile_options(connectanytime PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(connectanytime PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--no-undefined" "-Wl,--exclude-libs,ALL")
target_link_libraries(connectanytime PRIVATE
    "${VENDOR}/lib/libopenconnect.a" "${VENDOR}/lib/libssl.a"
    "${VENDOR}/lib/libcrypto.a" "${VENDOR}/lib/libxml2.a" z log android dl m)
