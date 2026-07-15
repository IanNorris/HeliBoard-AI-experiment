cmake_minimum_required(VERSION 3.22.1)
project(llama_jni LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF)

# Android bionic exposes MADV_* but not the POSIX_MADV_* aliases llama-mmap.cpp uses.
add_compile_definitions(POSIX_MADV_WILLNEED=MADV_WILLNEED POSIX_MADV_RANDOM=MADV_RANDOM)

# Android 15+ requires 16KB-aligned shared libraries (and Play submission).
add_link_options("-Wl,-z,max-page-size=16384")

add_subdirectory(llama.cpp)

add_library(llama_jni SHARED llama_jni.cpp)
find_library(log-lib log)
target_link_libraries(llama_jni PRIVATE llama ${log-lib} android)
