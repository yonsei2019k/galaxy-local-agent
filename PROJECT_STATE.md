# ZipHap.Agent project state

Updated 2026-10-30. Canonical project: this independent Android repository. Do not modify Roblox or nurse-scheduler.

## Verified

- Signed development APK version1003 installed on Galaxy S26 base, Android17/API37, about12GB RAM/256GB storage. SHA256:97f22713d56f1cda22adf962a6be198b52587171df60f002c550003e5dbc07f7. Application/signing identity and existing data are preserved.
- Core167/167 tests, release lint, release APK and instrumentation APK build passed. Regression coverage includes goal/evidence checks, request/follow-up routing, schema validation, calendar ambiguity and duplicate-action prevention.
- Phone injected SMS/calendar tests13/13 passed; the actual SMS test is separately gated. Real granted-provider traversal completed without truncation and stored encrypted schedule candidates. Personal counts/content remain outside Git. No personal calendar events were inserted by this scan.
- Phone note/memory save/update/delete readback, reminder persisted-queue verification and invalid-tool rejection3/3 passed.
- Paged conversation diagnostics and model-format checks7/7 passed earlier. Developer export successfully read this assistant's stored conversations locally; no contents entered Git.
- Local image engine512x512/LCM4steps/CPU4threads:124.4seconds, peakPSS2,133,691KiB, maxthermal1. Separate Korean-prompt controller workflow passed in147.5seconds including local translation, actual PNG/dimension checks, encrypted metadata, LLM unload and compute-lease release. The synthetic fox sample was visually inspected. One-step output was blurry and is not the quality default.
- After the shared native runtime update, Qwen Unicode/tokenization/cancellation/facade tests and E2B system-role/synthetic-summary tests passed again.
- Earlier E2B CPU smoke:load3891ms, TTFT1376ms, decode12.13tokens/sec. E4B10minute endurance:609.4sec/67generations,4.19tokens/sec, peakPSS2,916,447KiB,37.3→40.8°C, no crash. Qwen4B portable smoke5.38tokens/sec; long Korean prefill remains slow. Keep E2B fast default and E4B/Qwen selectable.
- GitHub CI/signing/release and automatic phone OTA were already verified through release0.1.3/version1003. This change preserves the update chain and adds reviewed calendar-write capability.
- Actual Google Calendar Provider test passed insert, ID/date readback and repeated-save deduplication. Its single synthetic event and encrypted link were removed and absence verified. An initial cleanup used an unsupported selection on an item URI; the collection-URI fix and recovery cleanup both passed.
- All3 UI tests passed on the installed APK after unlock: keyboard-open send/input occlusion and IME submission, manual/automatic model selection, and actual E4B residency after its response. Earlier locked-device failures were not treated as passes.

## Implemented

- Native Kotlin/Compose chat, IME-safe composer, encrypted drafts, manual/automatic model selection and native system/user/history roles.
- Intent-to-goal routing, at most6tool steps, strict typed validation, request-scoped authority, duplicate-action guards and VERIFIED/PREPARED/UNVERIFIED/FAILED evidence. A natural-language completion without required tool evidence is rejected and given one repair attempt.
- Whole-provider bounded SMS classification/appointment extraction, encrypted candidate management, explicit date/title editing, calendar destination selection and batch review/cancel. Calendar IDs are read back; encrypted links and provider markers deduplicate inserts even after external edits.
- Local DreamShaper8LCM diffusion through pinned stable-diffusion.cpp and shared pinned ggml ABI, verified separate model download/import, CPU feature/memory/thermal guards, progress/cancel, private PNGs, explicit Gallery export and an image foreground service. Image and LLM weights never stay loaded together.
- Local conversations, notes, approved/corrected memories, reminders/restoration, imports, scoped phone records, recording decode/transcription pipeline, basic TXT/CSV/PDF and email drafts.
- Owner-selected usage/correction reflection, notification-session integrations, signed updates and declarative extension security checks. Weights remain fixed; no claim of autonomous on-phone model retraining.

## Remaining product evidence

- Actual Meditong/Kakao/Telegram complete workflows, selected real audio transcription, full UI/restart recovery and broad comparative reasoning evaluation need further validation.
- Notification access exposes received session notifications, not all historical app-private messages. Import preserves user-selected history. Protected work profiles/private app databases are not bypassed.
- General DOCX/XLSX editing, robust OCR, video generation, executable MCP hosting and autonomous source-code self-modification are not established. Development/source upgrades currently run through this PC and signed GitHub CI; routine inference runs on the phone.

Private keys, personal messages/exports, raw device logs, identities, model weights and generated user images remain outside Git. Do not ask for pairing/login/project setup again. See docs/assistant-harness.md and docs/user-guide.ko.md.
