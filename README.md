# ZipHap.Agent

ZipHap.Agent is a local-first Android assistant for Galaxy S26. It uses Kotlin, Jetpack Compose, LiteRT-LM and a paired-PC worker. Ordinary AI requests use the paired PC's local model; they stop if that PC is unavailable instead of falling back to phone inference. This default path requires no paid inference API key. The phone owns requests, conversations, approved memories, actions and final files.

## Project status — 2026-11-14

The latest recorded phone checkpoint is development build 1059 on Galaxy S26/Android17/API37, installed with existing data preserved. Its signed main/test APKs and installed hashes were independently matched. All 687 core and 581 app JVM tests and release/test assembly passed. Ten actual paired-PC local-model/phone cases passed their stated checks: six completed contact-list, exact-note and clock tasks; four paused for owner clarification or external SMS-draft confirmation. No SMS was sent. These limited QA cases do not establish reliable execution across all 98 registered tools.

Company/team internal distribution is being prepared. Later source changes need their own build, signed artifacts, installation and actual-phone validation before they can inherit a release claim. See [project state](PROJECT_STATE.md), [device evidence](docs/device-validation.md) and [1059 scope and intermediate failures](docs/recipient-completion-validation-2026-11-14.ko.md).

## Internal team setup

1. Use the team's designated signed APK and verify its version and SHA-256. The Android build targets arm64 devices, with minSdk31/target37; recorded phone evidence is specific to Galaxy S26. Keep the existing application/signing identity when updating to preserve data.
2. Prepare the team's verified PC worker, runtime and separately downloaded model files. Model weights are never bundled in the APK or committed. A new PC installation and a new phone's first-run setup require their own verification; the existing owner's working setup is not proof of a general team installer.
3. In **내 PC의 계산 자원 → 내 PC 연결**, start on the same local network, grant the requested Android local-network permission and enter the code shown on the intended PC. Use **연결 확인** after pairing and keep **대화 엔진 → 오픈소스 · PC** for the default local path. Follow the model screen's preparation notices for the selected feature; selecting a phone model does not install or select the PC's text weights.
4. Away from that local network, the configured Tailscale connection must be available on both devices. It retains the original pairing identity and certificate pin and grants no extra data access. A direct authenticated phone health check has passed; changing between Wi-Fi and cellular during work remains unverified.
5. Grant only the Android permissions and project/file/account access needed for the intended task. Notification access, accessibility, usage access and update installation use Android's own consent surfaces. Check a clock request and a disposable exact-note save/readback before introducing team material.

See the [Korean user guide](docs/user-guide.ko.md) for the first-use steps, feature preparation and result boundaries.

### Historical phone-only measurements

The measurements below belong to earlier phone-only builds and model versions. They are not benchmarks of the current paired-PC default or promises of current latency.

- **Gemma 4 E2B, CPU smoke:** load 3,891 ms; TTFT 1,376 ms; generation 12.13 tokens/s; PSS 1,367,901 KiB. This was a short smoke run, not an endurance result.
- **Gemma 4 E4B, CPU smoke:** load 6,151 ms; TTFT 3,070 ms; generation 4.58 tokens/s.
- **Gemma 4 E4B, 10-minute run:** 609.4 seconds, 67 generated responses, 4.19 tokens/s, peak PSS 2,916,447 KiB, battery 68%→64%, temperature 37.3→40.8 °C, maximum thermal status 2, no failures.
- E2B's 10-minute run was stopped during user chat use. This was a deliberate stop, not a spontaneous crash. That E2B build passed the device SYSTEM-role test and identified itself as ZipHap.Agent; a synthetic Kakao summary correctly extracted a report draft by the morning and a meeting at 3 p.m. Identity TTFT was 5.89 seconds and summary TTFT 3.43 seconds.
- The earlier official `llama.cpp` v0.8.0 GGUF CPU runtime used four threads. Qwen3.5 4B Q4_K_M passed CPU smoke plus synthetic Unicode/tokenization/cancellation tests, with smoke decode at 5.38 tokens/sec. E2B was the fast default at that checkpoint; this does not describe the current PC text model. Only one large inference model is loaded at a time, with bounded context.

These measurements are device-specific and do not establish performance on other phones.

## Current capabilities

- Conversation through the paired PC's local model, model management, encrypted phone notes and approved memories, reminders, and typed tools with user approval for consequential actions.
- Basic text, CSV, PDF, and email-draft workflows; document functions are limited to the implemented screens and operations.
- User-selected Kakao or other conversation export import, encrypted storage, source catalog, scoped analysis, and explicit deletion.
- Separate, explicit Android permission flows for SMS import and call-log metadata summaries. Call-log access does not expose call audio.
- User-selected audio intake and transcription paths require their own prepared models and access. Decoding, successful transcription and recognition accuracy are separate evidence; current real-workflow coverage is tracked in the device record.
- Optional notification and accessibility integrations remain bounded by Android grants, user actions, and the data each API exposes. They do not provide all historical messages or all app-private data.
- SMS analysis (100,000 rows / 50 MiB maximum), Korean appointment extraction, encrypted editable candidates and reviewed Calendar Provider insertion with real ID readback and deduplication.
- A separate image workspace supports an explicitly selected phone image model or a prepared paired-PC image engine. PC selection stops on connection failure without automatic phone regeneration. Photo understanding and Korean-to-English prompt preparation use a separately selected Gemma helper; that helper does not generate the final image. Earlier phone LCM timing does not establish current SDXL/Qwen output quality or speed.
- Paged assistant conversation history, search, deletion and owner-selected JSON/text export. Developer exports remain local and excluded from Git.
- Typed goal/evidence harness, duplicate-action guards and write readback. Preparing an external app screen is distinguished from completing its action.

See the [Korean user guide](docs/user-guide.ko.md) for current screens and limits, and [imports](docs/imports.md) for import behavior and storage bounds.

### Not established or not implemented

General DOCX/XLSX editing, reliable OCR, new-scene video generation and autonomous operation of every app/private history remain unverified or unimplemented beyond the individual implemented operations. Model-quality and latency claims require matching model/source/workflow evidence. Preparing an Intent is not external saving, sending or navigation success. Model weights remain fixed; approved corrections, local memory, tools and tested source updates improve behavior.

## Build

- JDK 21, Android SDK 37.0, Build Tools 37.0.0, Gradle 9.5, AGP 9.3.3.
- `./gradlew :core:test :app:assembleDebug`
- Models are not bundled in the APK. A model must be separately downloaded or selected by the owner.
- A release claim additionally requires core security checks, app unit tests, release lint, installable release/test assembly, signature verification and the applicable actual-device workflows. A successful development compile alone is not release validation.

## Privacy and release

Private notes and imported content are stored in the phone's encrypted repository. Permitted request text, tool results and selected media needed for local inference may travel to the authorized paired PC. Separately enabled Codex briefing, external text engines and encrypted image recovery have their own data/retention conditions; they are not required for ordinary PC-local inference. Review those settings before using company material. The app only reads phone-provider data within the corresponding Android grants and request scope; a SAF file requires owner selection. Do not commit private records, recordings, model weights, signing material, pairing data or device logs.

The GitHub repository is `yonsei2019k/galaxy-local-agent`. Earlier CI signing and trusted OTA were verified; a newly prepared internal build needs its own delivery evidence. The current updater follows the signed stable channel, and main-branch CI publishes a release after its build checks. Internal rollout, version ordering and recovery must therefore be coordinated with that channel. New Android permissions or capability changes receive separate review. App backup is disabled; copying the encrypted database alone does not provide a tested restore path. Preserve data and the Keystore identity during updates, and use a validated recovery procedure rather than uninstalling to repair an installation. Never put secrets into source, logs or prompts.

## Upstream and licensing

The project references public Google AI Edge Gallery and LiteRT-LM APIs and design material; it does not claim feature parity with Gallery. Application code is Apache-2.0. External model and dependency licenses remain separate and must be checked for each distribution.

- https://github.com/google-ai-edge/gallery
- https://github.com/google-ai-edge/LiteRT-LM
