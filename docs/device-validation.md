# Device validation

## Latest validated development build

The current evidence is summarized in PROJECT_STATE.md. Version1003 development APK SHA256:97f22713d56f1cda22adf962a6be198b52587171df60f002c550003e5dbc07f7.

Core167/167 and release lint/build passed. Device checks passed injected SMS/calendar contracts, actual granted SMS traversal, note/memory/reminder postconditions, Korean local image creation/storage/model release, a real Google Calendar synthetic insert/readback/deduplication/cleanup, and keyboard-open send. No personal SMS bodies or account names entered Git. Initial errors described below are historical; remaining product evidence is tracked in PROJECT_STATE.md.

## Earlier development record

Galaxy S26 base SM-S942N, Android17/API37, Samsung s5e9965, approximately12GB RAM/256GB storage.

## Observed

- A signed release APK was installed and launched via paired wireless ADB.
- Initial UI rendered; no startup crash was observed in the limited crash-log sample.
- The first encrypted-memory test exposed SQLCipher's ESCAPE expression error. Queries now use a single `!` escape character; device rerun is pending.
- The first E2B attempt ended after approximately198seconds with JobCancellationException. A diagnostic rerun confirmed that E2B was cached and its SHA-256 passed. The benchmark then cancelled in the pre-load unload step (8ms, zero iterations): unqualified `cancel()` inside a `withContext` scope resolved to coroutine-scope cancellation. It is now explicitly `this@LiteRtInferenceEngine.cancel()`; successful inference is still awaiting rerun.
- Diagnostic instrumentation now exports stage checkpoints before model resolution/download/native loading, and reports failures in JSON. Benchmark reports use native token metrics rather than treating characters as tokens.
- Wireless ADB connection ports can change; the private local helper rediscovers the already-paired device without pairing again.

## Remaining gates

1. Rerun encrypted note/memory correction, literal search and erasure tests.
2. Confirm completed, hash-verified model artifact; run CPU short-prompt smoke and cancellation.
3. Compare E2B/E4B only if memory and thermal headroom allow; record configuration, load/TTFT/native prefill/decode rates, PSS, battery and temperature.
4. Ten-minute endurance, offline inference, persisted memory and reminder restoration.
5. Explicit Android notification/usage/screen-access settings, then actual app adapter validation. No live work message is sent during development tests.
6. GitHub CI release and phone OTA upgrade, including changed-capability approval and Android pending-user-action fallback.

Private reports/screenshots are kept outside Git. No pass is inferred from source presence alone.

## Successful E2B CPU smoke

Canonical `gemma-4-E2B-it.litertlm` (2,588,147,712 bytes), SHA256 verified. CPU/4096 context/thinking off/128 output cap, one short public Korean prompt:

| Metric | Measured |
| --- | ---: |
| Load | 3891ms |
| First visible text | 1376ms |
| Native prefill | 39.13 tokens/sec |
| Native decode | 12.13 tokens/sec |
| Decode tokens | 35 |
| Peak PSS | 1,367,901KiB |
| Thermal status | 0 |
| Battery sensor temperature | 35.3°C |

This is a short smoke, not a ten-minute or model-quality result. Encrypted data round-trip and literal search also passed on the phone. E4B download/comparison is in progress. The earlier wrong GPU artifact was preserved while the CPU-compatible canonical file was verified.

## E4B measurements

Canonical E4B file3,659,530,240bytes, SHA256 verified. Initial CPU/4K/thinking-off smoke: load6151ms, first text3070ms, decode4.58tokens/sec, peakPSS3,096,194KiB.

Ten-minute CPU test completed67generations in609.4seconds without failure/crash. Average native decode4.19tokens/sec; peakPSS2,916,447KiB (about2848MiB); battery68→64%; battery-temperature sensor37.3→40.8°C; maximum Android thermal status2(MODERATE), below the benchmark stop threshold3(SEVERE).

Policy: E2B is the fast default; E4B is a quality-purpose candidate used only for explicit deep/complex requests with successful endurance evidence and memory/thermal headroom. Size and speed measurements do not establish answer correctness; the separate four-task dry-run evaluation is pending.
