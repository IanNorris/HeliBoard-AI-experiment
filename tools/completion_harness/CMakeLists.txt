# SPDX-License-Identifier: GPL-3.0-only
cmake_minimum_required(VERSION 3.16)
project(completion_harness CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Point at the vendored llama.cpp submodule and its prebuilt host library.
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../llama/src/main/cpp/llama.cpp")
set(LLAMA_BUILD "${LLAMA_DIR}/build-host")

add_executable(completion_harness harness.cpp)

target_include_directories(completion_harness PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include")

# Prefer the built targets if the llama.cpp build tree is available; otherwise fall back to locating
# the built shared/static libraries directly.
if (EXISTS "${LLAMA_BUILD}/CMakeCache.txt")
    find_library(LLAMA_LIB NAMES llama libllama PATHS "${LLAMA_BUILD}/src" "${LLAMA_BUILD}/bin" "${LLAMA_BUILD}" REQUIRED)
    find_library(GGML_LIB NAMES ggml libggml PATHS "${LLAMA_BUILD}/ggml/src" "${LLAMA_BUILD}/bin" "${LLAMA_BUILD}" REQUIRED)
    target_link_libraries(completion_harness PRIVATE ${LLAMA_LIB} ${GGML_LIB})
else()
    message(FATAL_ERROR "Build llama.cpp host library first (see tools/completion_harness/README.md)")
endif()

find_package(Threads REQUIRED)
target_link_libraries(completion_harness PRIVATE Threads::Threads)
