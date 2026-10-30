# ZipHap.Agent

ZipHap.Agent is a local-first Android assistant for Galaxy S26. It uses Kotlin, Jetpack Compose, and LiteRT-LM. Routine model inference runs on-device and does not require a paid API.

## Project status — 2026-10-30

Release0.1.2 is installed on Galaxy S26/Android17/API37. GitHub CI, APK/manifest signing and automatic in-app upgrade from1000 to1002 passed. The installed APK hash is3cc3a19064688231feedc649575c92e2099cb3f18407d4b457f6a52f12092707. Core79/79 tests, release lint and synthetic phone checks passed; detailed scope and remaining UI/audio checks are in PROJECT_STATE.md.

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

See the [Korean user guide](docs/user-guide.ko.md) for current screens and limits, and [imports](docs/imports.md) for import behavior and storage bounds.

### Not established or not implemented

The current app does not provide general DOCX/XLSX editing, reliable OCR, image generation, video generation, or an autonomous agent that can operate every app or read all device history. Qwen has a short CPU smoke result; ten-minute endurance and comparative answer quality remain unverified. Fixed model weights are not continually retrained; corrections can update approved memories only where the feature supports it.

## Build

- JDK 21, Android SDK 37.0, Build Tools 37.0.0, Gradle 9.5, AGP 9.3.3.
- `./gradlew :core:test :app:assembleDebug`
- Models are not bundled in the APK. A model must be separately downloaded or selected by the owner.

## Privacy and release

Private notes and imported content are stored locally in the encrypted repository. The app only reads phone-provider data after the owner grants the corresponding Android permission and starts the specific action. A SAF file is read only after the owner selects it. Do not commit private records, recordings, model weights, signing material, or device logs.

The GitHub repository is `yonsei2019k/galaxy-local-agent`. Signing secrets are configured, but source push, CI verification, release signing, and OTA have not yet been completed. Never put secrets into source, logs, or prompts.

## Upstream and licensing

The project references public Google AI Edge Gallery and LiteRT-LM APIs and design material; it does not claim feature parity with Gallery. Application code is Apache-2.0. External model and dependency licenses remain separate and must be checked for each distribution.

- https://github.com/google-ai-edge/gallery
- https://github.com/google-ai-edge/LiteRT-LM
