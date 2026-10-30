# ZipHap.Agent project state

Updated 2026-10-30. Independent of Roblox and nurse-scheduler.

## Verified

- Native Android app installed on Galaxy S26 base, Android17/API37, about12GB RAM/256GB storage. Package com.user.localagent and signing identity remain stable.
- Current installed release0.1.2/version1002 APK SHA256:3cc3a19064688231feedc649575c92e2099cb3f18407d4b457f6a52f12092707. GitHub CI94b3b8d passed; the app applied the signed update itself without a separate install-button action. Post-install certificate, version and four carried scoped grants passed.
- Core79/79 tests and release lint passed; subsequent draft-race fix assembled app/test APK successfully. Synthetic file-import/phone-record/audio-decoder/document tests19/19, encrypted memory/unload2/2, and isolated encrypted-draft3/3 passed on the phone.
- Gemma4 E2B initial CPU4K smoke: load3891ms, TTFT1376ms, decode12.13tokens/sec, PSS1,367,901KiB. Gemma4 E4B smoke: load6151ms, TTFT3070ms, decode4.58tokens/sec.
- E4B ten-minute endurance:609.4sec/67generations, average4.19tokens/sec, peakPSS2,916,447KiB, battery68→64%, temperature37.3→40.8°C, maxthermal2, no failure. E2B endurance was deliberately stopped for user chat; no spontaneous failure is inferred.
- Native SYSTEM-role E2B test passed: identifies ZipHap.Agent; synthetic Kakao dialogue produced report preparation by the morning and a3PM meeting. Identity TTFT5.89sec; summary TTFT3.43sec. These are synthetic behavior observations, not whole-history analysis.
- Qwen3.5 4B Q4_K_M (2,740,937,888bytes) installed and SHA256 verified. Official pinned llama.cpp v0.8.0 CPU4thread runtime. Smoke:load2682ms, TTFT4147ms, decode5.38tokens/sec, peakPSS3,025,903KiB. Short smoke is not endurance or answer-quality proof.
- Qwen synthetic native tests passed: Korean/Unicode streaming, actual token counts, cancellation81ms, singleton runtime and facade reload. Korean test prefill6.6–7.1tokens/sec and first-text14–20sec show that the current portable CPU runtime is slow for long prompts; retain E2B fast default. Qwen is manually selectable for comparison.
- Keyboard/time-rule check passed. The whole UI suite remains pending: phone switching to ChatGPT interrupted later model-picker/resident checks. A stale QA-draft race was fixed and its isolated regression test passed. No real work message was sent.

## Implemented, with remaining workflow checks

- Compose chat, same-row IME-safe composer, actual model selection, encrypted draft persistence, native system/user/history roles, bounded six-round typed tools, valid-JSON context compaction and request-scoped authority.
- Encrypted Room/Keystore memories/notes, explicit memory approval/deletion, correction storage, reminders with AlarmManager/WorkManager recovery.
- Conversation-file import/catalog/search/deletion; SMS read/import and call-log aggregates through granted Android providers; scoped notification-session queries. Provider tests use synthetic injected cursors, not personal records.
- Owner-selected audio decoding into30sec mono16k WAV chunks and local Gemma transcription pipeline. Decoder tests pass; actual audio-model end-to-end smoke remains pending.
- Native TXT/Markdown, CSV, PDF and email draft utilities. Email testing verifies correct encoded URI and extras rather than Android MailTo's double-decoding parser.
- Optional local usage/correction analysis, foreground event coordinator, charging-only reflection proposals. Weights stay fixed; proposals need memory approval.
- Per-app notifications, scoped fixed-template replies, deterministic user-driven screen inspector; actual Meditong/Kakao/Telegram workflows are not validated.
- Pinned model/artifact provenance, declarative extension scanner, signed GitHub update verifier and capability/permission review.

## Remaining

- Finish foreground UI validation when the phone is available; test actual user-selected audio, restart/reminder recovery and app-specific adapters.
- Offline-only APK test PASSED: the APK lacked INTERNET permission while cached E2B inference and encrypted note save/search/delete succeeded. Normal online release was restored afterward; phone Wi-Fi/ChatGPT connectivity was preserved.
- Public source, remote CI/signing/release and phone OTA are verified. Additional offline/update validation tests and this status update are being committed; they do not change release inference behavior.
- No general DOCX/XLSX editor, OCR, image/video generation engine, executable MCP installer, unrestricted app-private database access or autonomous all-app operation.

Private signing material, reports/screenshots, device identity, personal content and model weights stay outside Git. Previously authorized pairing and setup must not be requested again.
