#pragma once

#include <stdexcept>
#if defined(__ANDROID__) && defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif

namespace ziphap_native {
class UnsupportedCpu final : public std::runtime_error {
public:
    UnsupportedCpu():std::runtime_error(
        "Unsupported CPU: this native runtime requires ARM FP16 arithmetic and DOTPROD. Select a LiteRT model on this device."){}
};

// This guard is compiled in the default-architecture JNI translation units,
// before any optimized GGML backend initializes or executes tensor kernels.
inline void require_fp16_dotprod_cpu(){
#if defined(__ANDROID__) && defined(__aarch64__)
    constexpr unsigned long required=HWCAP_FPHP|HWCAP_ASIMDHP|HWCAP_ASIMDDP;
    const unsigned long available=getauxval(AT_HWCAP);
    if((available&required)!=required)throw UnsupportedCpu{};
#else
    throw UnsupportedCpu{};
#endif
}
}
