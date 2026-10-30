# Local CPU image runtime

`CMakeLists.txt` pins the official stable-diffusion.cpp source archive and links
its documented `SD_USE_UPSTREAM_GGML` mode against the application's pinned GGML.
The JNI library hides its static dependency symbols to avoid cross-runtime
callback or backend-registry interposition with the text engine.
The top-level build must define `GGML_MAX_NAME=160` before creating any GGML,
llama, SD or JNI target. SD requires long tensor names; all consumers must use
the same tensor ABI. `SD_USE_SYSTEM_GGML=ON` prevents a second per-directory
definition from creating inconsistent consumers.
The optimized S26 CPU target is `armv8.2-a+fp16+dotprod`. Default-architecture
JNI entry points check Android `AT_HWCAP` for scalar/vector FP16 and DOTPROD
before initializing optimized backends. Unsupported CPUs get an explicit
exception and may continue using the separate LiteRT text runtime.

The initial runtime accepts consolidated SD1.x/LCM F16 safetensors or compatible
GGUF model files. No model is bundled. A successful build is not evidence that a
particular checkpoint loads or performs acceptably on Galaxy S26; root must run
the synthetic device benchmark before enabling a model by default.

The caller owns the global inference gate and must unload text inference before
loading an image context. `ImageGenerationEngine` serializes image operations,
has cooperative cancellation, and frees the native context on unload. The first
profile uses four CPU threads, at most512x512pixels and1–8sampling steps. These are
initial measured-resource bounds, not content classification. More expensive
profiles require separate device memory/temperature/latency validation.

Only pixel output and numeric progress cross JNI. Native log callbacks discard
prompt/model output; no prompt is sent to a server or automatically published.
Model licenses are independent of the MIT runtime license and must be displayed
by the model manager. In particular, DreamShaper8LCM uses CreativeML OpenRAIL-M;
it must not be advertised as an unrestricted-license model.
