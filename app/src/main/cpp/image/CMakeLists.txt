# Image runtime links the already pinned upstream-compatible GGML target. This
# first CPU/F16 build does not enable the SD fork's INT8/custom convolution ops.
if(NOT TARGET ggml)
    message(FATAL_ERROR "Add image/ after the application's pinned llama.cpp GGML target")
endif()
include(FetchContent)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_USE_UPSTREAM_GGML ON CACHE BOOL "" FORCE)
# The existing target is deliberately treated as system GGML: otherwise SD
# adds GGML_MAX_NAME=160 only to its own translation units, changing tensor ABI.
set(SD_USE_SYSTEM_GGML ON CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_WEBP OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_WEBM OFF CACHE BOOL "" FORCE)
set(SD_CUDA OFF CACHE BOOL "" FORCE)
set(SD_HIPBLAS OFF CACHE BOOL "" FORCE)
set(SD_METAL OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(SD_SYCL OFF CACHE BOOL "" FORCE)
set(SD_MUSA OFF CACHE BOOL "" FORCE)
FetchContent_Declare(ziphap_sd
    URL "https://codeload.github.com/leejet/stable-diffusion.cpp/tar.gz/e239e4b0fd3de49834cec67031a44796ca465abc"
    URL_HASH SHA256=77e5e6e14a7f33570c3c861a2338e7a5ece3f09da969dde827488678d168a78c
)
FetchContent_MakeAvailable(ziphap_sd)

add_library(ziphap_image SHARED image_jni.cpp)
target_compile_features(ziphap_image PRIVATE cxx_std_17)
target_link_libraries(ziphap_image PRIVATE stable-diffusion android log)
target_compile_options(ziphap_image PRIVATE -Wall -Wextra -Werror=return-type)
set_target_properties(ziphap_image PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
# Each JNI DSO owns its static GGML state. Do not interpose callbacks/registries
# with ziphap_llama or other image libraries loaded in the application process.
target_link_options(ziphap_image PRIVATE
    -Wl,--exclude-libs,ALL -Wl,-Bsymbolic -Wl,-z,max-page-size=16384
)
