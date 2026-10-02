cmake_minimum_required(VERSION 3.22)
project(longhand_llm C CXX)

include(FetchContent)

# Keep this machine's paths out of the binary: llama.cpp's assertions embed __FILE__.
add_compile_options(
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}/_deps/llama_cpp-src=llama.cpp"
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=longhand")

# llama.cpp, pinned to the release the summary model was evaluated with.
FetchContent_Declare(llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11308.tar.gz
    URL_HASH SHA256=3af8fa61fe0c2dea31f2819e6d7fa4eab7a58cb7dc580f3fe4fb025e2d4d3da6
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)

# Only the core library, linked statically into libllm.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Every Pixel that runs GrapheneOS (6 and newer) has dot-product and fp16 instructions.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(llama_cpp)

add_library(llm SHARED llm.cpp)
target_link_libraries(llm PRIVATE llama log)

# Batched decoding for sherpa-onnx's streaming recognizer, which its Kotlin API leaves out.
add_library(online-batch SHARED online_batch.c)
target_link_libraries(online-batch PRIVATE dl)
