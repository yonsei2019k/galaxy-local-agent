# ZipHap.Agent

ZipHap.Agent is a local-first Android assistant for Galaxy S26. It uses Kotlin, Jetpack Compose, and LiteRT-LM. Routine model inference runs on-device and does not require a paid API.

## Project status — 2026-10-30

Signed releases and a development build are installed on Galaxy S26/Android17/API37. GitHub CI, APK/manifest signing and automatic in-app upgrades passed. Current source adds native image generation, bounded whole-provider SMS traversal, reviewed calendar writes, conversation diagnostics and goal/result verification. See PROJECT_STATE.md for measured evidence and remaining workflow checks.

### Model measurements

- **Gemma 4 E2B, CPU smoke:** load 3,891 ms; TTFT 1,376 ms; generation 12.13 tokens/s; PSS 1,367,901 KiB. This was a short smoke run, not an endurance result.
- **Gemma 4 E4B, CPU smoke:** load 6,151 ms; TTFT 3,070 ms; generation 4.58 tokens/s.
- **Gemma 4 E4B, 10-minute run:** 609.4 seconds, 67 generated responses, 4.19 tokens/s, peak PSS 2,916,447 KiB, battery 68%→64%, temperature 37.3→40.8 °C, maximum thermal status 2, no failures.
- E2B's 10-minute run was stopped during user chat use. This was a deliberate stop, not a spontaneous crash. The integrated E2B build now passes the device SYSTEM-role test and identifies itself as ZipHap.Agent; a synthetic Kakao summary correctly extracted a report draft by the morning and a meeting at 3 p.m. Identity TTFT was 5.89 seconds and summary TTFT 3.43 seconds.
- Official `llama.cpp` v0.8.0 GGUF CPU runtime is configured for four threads. Only one inference engine/model runs in memory at a time. Qwen3.5 4B Q4_K_M is installed and passed CPU smoke plus synthetic Unicode/tokenization/cancellation tests. Smoke decode was 5.38tokens/sec; longer Korean prompts currently prefill slowly, so E2B remains the fast default.

These measurements are device-specific and do not establish performance on other phones.

## Current capabilities

- Local chat, model management, encrypted notes and approved memories, reminders, and typed tools with user approval for consequential actions.
- Basic text, CSV, PDF, and email-draft workflows; document functions are limited to the implemented screens and operations.
- User-selected Kakao or other conversation export import, encrypted storage, source catalog, scoped analysis, and explicit deletion.
- Separate, explicit Android permission flows for SMS import and call-log metadata summaries. Call-log access does not expose call audio.
- User-selected audio-file decode/transcription pipeline is implemented in Kotlin. Synthetic decoder tests passed; current-device end-to-end transcription validation remains pending.
- Optional notification and accessibility integrations remain bounded by Android grants, user actions, and the data each API exposes. They do not provide all historical messages or all app-private data.
- SMS analysis (100,000 rows / 50 MiB maximum), Korean appointment extraction, encrypted editable candidates and reviewed Calendar Provider insertion with real ID readback and deduplication.
- Local DreamShaper8 LCM image generation through pinned stable-diffusion.cpp, a separate verified model download and private output files. A 512×512 four-step S26 sample took124.4seconds.
- Paged assistant conversation history, search, deletion and owner-selected JSON/text export. Developer exports remain local and excluded from Git.
- Typed goal/evidence harness, duplicate-action guards and write readback. Preparing an external app screen is distinguished from completing its action.

See the [Korean user guide](docs/user-guide.ko.md) for current screens and limits, and [imports](docs/imports.md) for import behavior and storage bounds.

### Not established or not implemented

General DOCX/XLSX editing, reliable OCR, video generation and autonomous operation of every app/private history remain unverified or unimplemented. Qwen ten-minute endurance and comparative answer quality remain unverified. Model weights remain fixed; approved corrections, local memory, tools and tested source updates improve behavior.

## Build

- JDK 21, Android SDK 37.0, Build Tools 37.0.0, Gradle 9.5, AGP 9.3.3.
- `./gradlew :core:test :app:assembleDebug`
- Models are not bundled in the APK. A model must be separately downloaded or selected by the owner.

## Privacy and release

Private notes and imported content are stored locally in the encrypted repository. The app only reads phone-provider data after the owner grants the corresponding Android permission and starts the specific action. A SAF file is read only after the owner selects it. Do not commit private records, recordings, model weights, signing material, or device logs.

The GitHub repository is `yonsei2019k/galaxy-local-agent`. Source push, CI verification, release signing and trusted OTA have passed on the phone. New Android permissions or capability changes receive separate review. Never put secrets into source, logs, or prompts.

## Upstream and licensing

The project references public Google AI Edge Gallery and LiteRT-LM APIs and design material; it does not claim feature parity with Gallery. Application code is Apache-2.0. External model and dependency licenses remain separate and must be checked for each distribution.

- https://github.com/google-ai-edge/gallery
- https://github.com/google-ai-edge/LiteRT-LM
