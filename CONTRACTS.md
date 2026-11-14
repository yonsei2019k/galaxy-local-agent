# Galaxy Local Agent implementation contracts

Package: `com.user.localagent`. Language: Kotlin. Time zone default: Asia/Seoul.

The `core` JVM module has no Android dependencies. App code uses `kotlinx.serialization.json`.

## Core contracts (core owner)
- `ToolCall(id: String, name: String, arguments: JsonObject)` and `ToolResult(id: String, success: Boolean, content: String)`.
- `Risk` enum LOW, MEDIUM, HIGH; tool metadata and strict argument validation in `ToolRegistry`.
- `ToolRegistry.parseAndValidate(raw: String): ToolCall` throws on any malformed/unknown/extra argument.
- `CapabilityGrant(extensionId: String, codeHash: String, toolName: String, scope: String)` and policy that only scoped note/reminder create/update can be preapproved; deletion/installation/permission changes/private data export always need confirmation.
- `AgentLoop` max 6 model rounds, externally executed tools, cooperative cancellation. No runtime automatic execution.
- Update and extension parsing/verification utilities; canonical signed payload is base64 JSON bytes signed with Ed25519, verified before JSON decode. Do not trust hashes without pinned signer.

## App shared contracts (root owner)
`AppContainer(context)` owns singleton `LocalRepository`, `ModelManager`, `LocalInferenceEngine`, `ConversationImport`, `PhoneRecords`, `ToolExecutor`, `ReminderScheduler`.

## Request action and host execution

- Internal baseline1060 registers102 tools. `inspect_project_storage`/`find_project_duplicates` are read-only, bounded selected-workspace observations, never deletion/movement/photo classification or overwrite read proof. They retain workspace binding checks and are unavailable in public-only research. `list_reminders` reads only the repository's captured data scope; its optional scope argument filters a reminder group and never grants access. `get_agent_capabilities` is metadata-only: registered/allowed/configured/verified execution are separate, and it performs no transport/model preparation or permission acquisition.
- Request JSON preserves `capturedPcRevision` and `allowCloudText`; missing legacy cloud authority means false. New ordinary requests capture these atomically from owner settings. Steer/goal/continuation preserves existing authority. A global preference change cannot move an old local request to a cloud engine; media/image preparation stays local. This does not certify external provider availability.
- Reminder source scope uses existing SourceScopeRecord, with owner legacy handling and privileged host-only delivery/restore APIs. Note/reminder mutation CAS uses `expectedUpdatedAt`, monotonically increasing revisions and approval previews from actual scoped records. Reminder DB+queue mutations share delivery/restore serialization. Deleting a project removes its reminders; deleting a thread removes thread-private reminders while project reminders can outlive their creator thread. Missing records return null for readback; an absent target is not reported as an executed deletion.
- `effectNotApplied` is host-only failure metadata, default false on ToolResult/StepEvidence. It is never accepted from model calls or arbitrary tool-result prose. Typed pre-write precondition rejection can be repaired only within the captured goal/tools; completion requires later VERIFIED evidence for the same tool and stable update target. Unknown writes, creations, deletions and external effects retain conservative blocking. The original failed step remains recorded, and unchanged retries still require a fresh same-file read and bounded repeat budget.

- Product direction2026-10-31: normal work starts in one conversation. Planning, explicit durable goals, skills, tools and result verification are in-chat capabilities, not mandatory work-category tabs. Read `docs/conversation-workflow.ko.md`. New ordinary submissions must not inherit a hidden stale composer mode, while already persisted queued/continuation mode and allowTools restrictions remain intact. Exact optional slash controls apply only to the submitted request; they are not natural-language intent rules. Goal creation still requires an explicit owner goal request, and model-written status/evidence cannot establish completion. Actual data scope, permission, approval, cancellation and outcome checks remain independent of presentation.

- Current1009 production integration uses `LocalSemanticPlanner.plan(currentOwnerRequest, recentConversation, chosen, availableTools, localContext)`. The selected on-device model proposes a `SemanticRequestPlan`; schema/type/termination/capability validation is mandatory in that implementation. Exactly one repair is allowed. Invalid plans do not fall back to lexical routing or the former source-status template. This implementation is not a permanent architectural requirement: the user's 2026-10-31 direction and `docs/agent-harness-research-2026-10-31.ko.md` call for comparing a minimal next-action loop without mandatory global classification. Such an experiment keeps actual tool validation and must report its own evidence before replacing production.
- `SemanticRequestPlanning` converts the validated meaning plan to `TaskGoal`, `IntentPlan` and offered tools without reclassifying natural-language words. The current request and full constraints remain in model context. `RequestExecutionPlanner`, `WebTaskPlanner` and other lexical planners remain legacy compatibility helpers and test references; they do not select production chat actions ahead of semantic inference.
- The stored canonical plan has ten fields, including semantic `targetType`. Live model output uses `ModelDecisionContract`: action/goal/targetType are common required fields, with other fields required by the selected action. A live decision must not silently default an omitted target type to GENERAL; legacy stored-plan parsing keeps its compatibility default. The host derives actual private-data use from tools/sources and safely drops unused annotations for non-executing actions, then runs existing canonical validation. Contradictory execution requests are rejected with bounded fixed validation hints. This is typed normalization, not natural-language rerouting. `search_shopping` is valid only for `PHYSICAL_PRODUCT`; service quotes/bookings/fares cannot use a retail catalog. Public service information does not establish live offers or availability.
- GGUF structured requests pass the schema to the pinned llama.cpp chat session and consume its grammar through `common_sampler`; setting `supportsStructuredOutput=true` requires this actual generation path. Ordinary chat and media retain their existing sampler. Schema enforcement guarantees allowed output shapes, not task interpretation or factual accuracy. Host validation and real-model scenario evaluation remain required. Native generation checks the actual structured prompt against its context budget; the existing plain token-count API alone does not certify schema-expanded prompt size.
- Tool prerequisites are typed capability closure: `search_conversation_imports` may first use `list_imported_conversations` to select an actual content hash. A disabled prerequisite rejects the plan; this does not grant additional content sources, and catalog counts never satisfy the required content read. A successful image does not finish a compound request until every required tool has verified evidence.
- Connection status, connection execution and method explanations must not silently become message analysis. Unresolved actions remain in the model path; schema checks, existing tool authority and scope checks still apply.
- Method corrections may inherit only a bounded public app/topic from recent direct requests. Never turn previous private messages, quoted text or imported instructions into public search terms. Current corrections and cancellations override inherited work.
- Research completion requires evidence for the selected research tool. Counting notifications or imported files cannot complete a request to investigate a connection method. Failed/partial public reads stay visible; researching a method is not a claim that a connection was established.
- General public-research output contains sources, excerpts, fetch time and actual collection limits. Shopping-only price/seller/inventory copy and price-absence checks apply only to shopping.
- `LocalResearchWorkflow.gather` consumes only a public non-private semantic plan. It gathers observed public evidence using local-model next-action decisions and a bundled research skill; `ChatRunCoordinator` records actual steps and uses the local model to synthesize a source-backed answer. It cannot execute private tools, permissions, booking or arbitrary observed-page instructions. Shopping-only plans use the existing retail quote collector and the same meaning/evidence-aware answer stage.
- The bundled `app/src/main/assets/skills/research/SKILL.md` is loaded by both research decisions and synthesis. A markdown file in a desktop skill folder or a registered plugin name alone is not an Android implementation. Model weights are unchanged; executable MCP hosting and live service-quotation connectors are not claimed installed.
- Regression and installation evidence are recorded in `docs/agent-change-log.md`; all active project agents must consult it before modifying these contracts.

## Inference/models owner
Package `com.user.localagent.inference`:
`InferenceOptions(contextSize=4096, thinking=false, backend="CPU", maxOutputTokens=512, audioEnabled=false, visionEnabled=false)`
`InferenceEngine` exposes load/generate/generateChat/transcribeAudio/unload/cancel plus state, residentModelPath and actual native lastMetrics. `generateChat(userText,systemInstruction,history)` preserves model roles.
`LocalInferenceEngine` serializes full requests and runtime replacement, releasing old weights before loading another model. LiteRT-LM handles `.litertlm`; the pinned llama.cpp JNI handles CPU `.gguf` text. No native tool execution.
`class LiteRtInferenceEngine(context: Context) : InferenceEngine` using LiteRT-LM 0.18.1; never automatically executes tools.
Package `com.user.localagent.models`: `ModelManager(context)`, `ModelDescriptor(id,title,url,sha256,sizeBytes,runtime)`, model import/download/verify/delete/scan. Public APIs coordinate with root through messages before changing.
Package `com.user.localagent.benchmark`: benchmark runner with JSON/CSV export, actual measured values only; unsupported metrics null and recorded as unsupported.

## Data/tools/scheduling owner
`LocalRepository(context)` in `com.user.localagent.data`: encrypted Room, conversations/notes/memories/reminders/tool_runs/grants, public suspend methods and Flow observers; publish signatures before integration.
`ToolExecutor(context, repository, scheduler, conversationImports, phoneRecords)` in `.tools`: `suspend fun execute(call: ToolCall): ToolResult`, `fun prepareIntent(call): Intent?`; Activity launch is root UI responsibility. Read results carry source/connection/partial scope. `compactJsonData` preserves valid JSON under prompt limits.
`ReminderScheduler(context)` in `.scheduling`: schedule/cancel/restore; `AlarmManager` exact where allowed, fallback status explicit; persistent WorkManager for inexact work.

## Ownership
Root: Gradle/config/manifest/UI/container/GitHub workflow/docs, extension/updater initially.
Core worker: `core/src/**` only.
Inference worker: app `inference/**`, `models/**`, `benchmark/**` only.
Data/tools worker: app `data/**`, `tools/**`, `scheduling/**` only.
Workers do not change Gradle, manifest, shared documents, branches, or commit until root integration permits. No user project edits. No private data/network requests beyond required official documentation.
