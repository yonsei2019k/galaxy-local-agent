cmake_minimum_required(VERSION 3.22.1)
project(ziphap_llama LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "ZipHap llama.cpp runtime supports Android arm64-v8a only")
endif()
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set_property(GLOBAL PROPERTY JOB_POOLS ziphap_compile=2 ziphap_link=1)
set(CMAKE_JOB_POOL_COMPILE ziphap_compile)
set(CMAKE_JOB_POOL_LINK ziphap_link)

# Official stable v0.8.0, published 2026-10-28. SHA-256 was computed from this exact
# immutable-commit source archive (38,685,236 bytes); this is source, never weights.
include(FetchContent)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE) # official Jinja chat templates
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL OFF CACHE BOOL "" FORCE)
set(LLAMA_TESTS_INSTALL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_CONNECT OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "Portable arm64 baseline" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE) # no additional unpinned downloads
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
FetchContent_Declare(llama_cpp
    URL "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/8642541b255bfb5dc39cd351a178be09013a55d6"
    URL_HASH SHA256=1688a167a8edd9499cdd0c19b8bd044f0fa184b466981977bbf2222d884953a4
)
FetchContent_MakeAvailable(llama_cpp)

add_library(ziphap_llama SHARED llama_jni.cpp)
target_include_directories(ziphap_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/common
    ${llama_cpp_SOURCE_DIR}/ggml/include
)
target_link_libraries(ziphap_llama PRIVATE llama llama-common android log)
target_compile_options(ziphap_llama PRIVATE -Wall -Wextra -Werror=return-type)
# Android 15+ 16 KiB page-size compatibility for the packaged JNI shared library.
target_link_options(ziphap_llama PRIVATE -Wl,-z,max-page-size=16384)

