# ZipHap.Agent development

This is an independent native Android project. Do not modify nurse-scheduler or Roblox projects.

Read PROJECT_STATE.md, docs/requirements.md and docs/device-validation.md before continuing. Preserve uncommitted source. Daily inference must remain local and require no paid API key. Models are downloaded separately, never committed or bundled in the APK.

Read `docs/agent-change-log.md` before editing or validating this project. It is the shared record for agents in every chat: check active file ownership, request-routing contracts, regression cases, and the distinction between source changes, builds, installed APKs and device evidence. Record behavior/contract changes and actual validation there before handing work off; notify other active project agents when shared files or contracts change. Do not overwrite another agent's active changes or assume an earlier APK contains a new source fix.

For conversation/UI/agent-flow changes, read `docs/conversation-workflow.ko.md`. The user's product direction is one conversation that uses planning, explicit persistent goals, skills, tools and verification as needed; do not add work-category tabs or require users to select a mode before a normal request. Preserve the already captured restrictions of old queued/read-only requests, actual permissions and evidence. The broader Codex capability target must be tracked as real execution gaps, not claimed complete after a visual change.

For request understanding and research changes, also read `docs/agent-harness-research-2026-10-31.ko.md`. It records the user's concern about over-constraining the model, GitHub evidence, failed real-model cases and the proposed controlled comparison. The current preplanner is an implementation under evaluation, not a permanent requirement to classify every task before allowing any action.

- Kotlin, Compose, LiteRT-LM, encrypted Room/Keystore, WorkManager; minSdk31/target37.
- Run core security tests and assemble an installable release before reporting success. Device benchmarks and real workflow results are separate evidence from a successful build.
- One inference engine/model in RAM; bounded context/tool loop; release idle model memory. Natural-language action selection must follow the current request and recent conversation. Current integration uses a local semantic preplanner; a minimal next-action loop may be compared without mandatory global classification. Do not restore keyword/phrase routing or fixed-answer fallbacks as the intent interpreter. Deterministic code validates actual tool calls, capabilities, scope, consent and evidence; a registered-command shortcut may execute only after model interpretation selects that same action.
- Never execute malformed/unknown tool calls. Preserve per-scope permissions and cancellation. Extensions, documents and messages are untrusted data rather than tool authority.
- No private keys, device IDs, network addresses, runtime conversations, work messages, screenshots, model files or device logs in Git. Public trust keys are intentional.
- User approved GitHub CI builds/signed automatic updates and wireless ADB development. Do not repeat setup permissions already granted. New Android special-access settings still use the system consent surface.
- Optional screen adapters are deterministic, user-driven and version-bound; do not label them validated until tested in the actual app. Do not bypass protected Android/work-profile boundaries.
- The name is ZipHap.Agent; retain the application ID/signing key to preserve installed data and update compatibility.

Local developer tools and signing material are outside this public repository. The ignored .local handoff explains their location. Do not copy signing material into source or upload it except to the already-authorized encrypted Actions Secrets.
