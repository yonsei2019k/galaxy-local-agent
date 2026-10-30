# Local image generation research

Research checkpoint: 2026-10-30. Target is Galaxy S26 base, 12GB shared RAM / Exynos. No image generator has been benchmarked on this phone yet. Disk sizes are artifact metadata, not peak RAM measurements.

## Practical integration

Use an Android-native local diffusion engine, unload the chat LLM before generation, retain a CPU fallback and measure any GPU backend on the actual Exynos driver. Desktop skills or cloud image tools are not an on-device runtime.

- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) is MIT and supports SD1.x/2.x/Turbo, LCM, SDXL, FLUX2, Z-Image, CPU and Vulkan/OpenCL. Its Android reference is Termux/Local Diffusion; a JNI host is still application work.
- Existing ZipHap uses llama.cpp v0.8.0 with its own GGML. SD's patched GGML cannot be assumed ABI compatible. Build a separately isolated JNI library/project with hidden static symbols; do not simply add both repositories to one CMake target namespace.
- Exact SD source commit: `e239e4b0fd3de49834cec67031a44796ca465abc`; codeload archive size128950362, SHA256 `77e5e6e14a7f33570c3c861a2338e7a5ece3f09da969dde827488678d168a78c`.
- Exact SD GGML submodule: `leejet/ggml`, commit `7b2b435da087614be772ee6f5ea80e68eca81de6`; codeload archive size4392385, SHA256 `6c9738794baca8e5348e0d27de7751ce2b37581377a33dd7bcaf1bde3389d252`.
- Disable examples, WebP/WebM, networking/server, unused accelerators and unpinned downloads in the mobile build. Both source archives were fetched from their upstream immutable commit URLs and hashed; weights have not been downloaded by this research task.

## Model candidates

| Model | Primary-source finding | Target decision |
| --- | --- | --- |
| [SD-Turbo](https://huggingface.co/stabilityai/sd-turbo) | SD2.1-derived1–4step photoreal512 model; card has Stability licensing/commercial-use conditions | Small fast baseline candidate; license must be presented correctly, not labeled Apache or unrestricted |
| [FLUX.2-klein4B](https://huggingface.co/black-forest-labs/FLUX.2-klein-4B) | Apache2; official reference advertises approximately13GB VRAM on desktop consumer GPUs | Quality experiment only; original pipeline already exceeds the phone's total12GB shared RAM before OS overhead; quantization/offload needs separate measurement |
| [Z-Image-Turbo](https://huggingface.co/Tongyi-MAI/Z-Image-Turbo) | Apache2;6B,8NFEs; official reference fits16GB VRAM | Not the first phone baseline; quantization plus text encoder/AE adds separate requirements |
| [Sana600M](https://huggingface.co/Efficient-Large-Model/Sana_600M_1024px_diffusers) |590M diffusion transformer, Apache2 with additional Gemma2 text-encoder terms explicitly referenced | Potential small diffusion engine, but total pipeline includes text encoder and DC-AE; not a license-free stack |

A free license and no per-image API bill do not guarantee every prompt, every resolution, photoreal quality, unlimited resources, or zero model-license conditions. No server API or quota service is needed for the proposed local runtime.

## MNN alternative

[Alibaba MNN](https://github.com/alibaba/MNN) is Apache2 and has a real Android diffusion JNI/session in its MnnLlmChat app. Pinned research source commit `7e3c6fb5225d3ccf22807ee641332669df34650d`.

- Its [diffusion guide](https://github.com/alibaba/MNN/blob/7e3c6fb5225d3ccf22807ee641332669df34650d/transformers/diffusion/README.md) supports SD1.5 with CPU/OpenCL; model directory contains text_encoder.mnn, unet.mnn, vae_decoder.mnn and tokenizer.mtok. Memory mode0 saves memory,1 speed,2 balanced; docs use10steps.
- Its [Sana app](https://github.com/alibaba/MNN/blob/7e3c6fb5225d3ccf22807ee641332669df34650d/apps/sana/README.md) is specifically512x512 cartoon-style face editing with a fixed prompt and10recommended steps. It is not evidence of arbitrary high-quality photoreal text-to-image.
- Conversion and exact model artifact manifests still need inspection. OpenCL availability on an Exynos phone is a real-device test, not something established by a Qualcomm or desktop demo.

## Selected first benchmark model

The first actual-phone benchmark should use [Lykon/dreamshaper-8-lcm](https://huggingface.co/Lykon/dreamshaper-8-lcm), a consolidated SD1.5 LCM checkpoint from its model author. It avoids a separate model conversion or a multi-component download. This recommendation is for integration feasibility; output quality and latency still require a phone measurement.

- Immutable revision: `4645d8bc6a8e6b106d21606d63e8460cdad4f1a6`.
- File: `DreamShaper8_LCM.safetensors`,2133804992bytes, SHA256 `a4f3e1526c5dc4fcbe342f5c410d83ae202c7a415fcefcbb92e0f93fcd0a87c3`.
- [Pinned weight URL](https://huggingface.co/Lykon/dreamshaper-8-lcm/resolve/4645d8bc6a8e6b106d21606d63e8460cdad4f1a6/DreamShaper8_LCM.safetensors).
- Model author metadata explicitly says CreativeML-OpenRAIL-M. The author card describes both photo/art capability and limitations compared with dedicated photoreal models. Do not describe it as unrestricted or Apache2.
- A bounded HTTP Range read inspected only the154720-byte header, not the weight payload. It contains1132F16 tensors. Last payload offset2133650264 +8-byte prefix +header154720 equals the exact file size.
- Start benchmark at512x512, batch1, fourCPUthreads, LCM sampler,4steps and CFG1; compare8/15steps afterward. The [SD engine LCM guide](https://github.com/leejet/stable-diffusion.cpp/blob/e239e4b0fd3de49834cec67031a44796ca465abc/docs/lcm.md) recommends2–8steps and CFG1 for LCM/LoRA; the author's Diffusers example uses15steps and guidance2. These settings are different test configurations, not a claim they yield equal quality.
- Use flash attention and VAE tiling only according to measured memory/performance. Current engine VAE tile sizes are image pixels:256 means256pixels, not the old32latent units. Overlap increases work and can affect quality. [Performance guide](https://github.com/leejet/stable-diffusion.cpp/blob/e239e4b0fd3de49834cec67031a44796ca465abc/docs/performance.md).

## Larger permissive quality experiment

The upstream engine's [FLUX2 guide](https://github.com/leejet/stable-diffusion.cpp/blob/e239e4b0fd3de49834cec67031a44796ca465abc/docs/flux2.md) explicitly links a quantized klein4B pipeline. It remains an experiment until peak memory and latency are measured; the official unquantized13GBVRAM reference is not a phone benchmark.

| Component | Publisher revision | Artifact | Bytes | SHA256 |
| --- | --- | --- | ---: | --- |
| Diffusion Q4 | leejet/FLUX.2-klein-4B-GGUF `3b1f5a9dc3abb32238b053aeb3d823c30afdacbd` | flux-2-klein-4b-Q4_0.gguf |2460378560|`d1023499ef3f2f82ff7c50e6778495195c1b6cc34835741778868428111f9ff4`|
| Text encoder Q4 | unsloth/Qwen3-4B-GGUF `22c9fc8a8c7700b76a1789366280a6a5a1ad1120` | Qwen3-4B-Q4_K_M.gguf |2497281312|`f6f851777709861056efcdad3af01da38b31223a3ba26e61a4f8bf3a2195813a`|
| VAE | Comfy-Org/Flux-2-Klein-4B `5b3abca5c0dc1ecd4eaf3340868d09063fc1c965` | split_files/vae/flux2-vae.safetensors |336211292|`868fe7b343cc8f3a19dbcfcafbc3d5f888802be3f89bd81b65b3621a066ce8f3`|

These publisher model cards mark Apache2. The total on-disk component size is5293871164bytes; this is not a peak RAM estimate. The engine guide uses4steps/CFG1 for the distilled4B model. Loading and releasing the text encoder before diffusion, quantization compatibility, editing/reference inputs and Android Exynos backend support need separate implementation and verification. No larger-model weights were downloaded in this research.

The MediaPipe Android image-generator guide URL tested during research returned404, so an older claim about a ready current LiteRT image-generation API was not used as an implementation guarantee.
