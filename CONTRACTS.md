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
`AppContainer(context)` owns singleton `LocalRepository`, `ModelManager`, `LiteRtInferenceEngine`, `ToolExecutor`, `ReminderScheduler`.

## Inference/models owner
Package `com.user.localagent.inference`:
`data class InferenceOptions(val contextSize:Int=4096,val thinking:Boolean=false,val backend:String="CPU")`
`interface InferenceEngine { suspend fun load(path:String, options:InferenceOptions); fun generate(prompt:String): Flow<String>; suspend fun unload(); fun cancel() }`
`class LiteRtInferenceEngine(context: Context) : InferenceEngine` using LiteRT-LM 0.18.1; never automatically executes tools.
Package `com.user.localagent.models`: `ModelManager(context)`, `ModelDescriptor(id,title,url,sha256,sizeBytes,runtime)`, model import/download/verify/delete/scan. Public APIs coordinate with root through messages before changing.
Package `com.user.localagent.benchmark`: benchmark runner with JSON/CSV export, actual measured values only; unsupported metrics null and recorded as unsupported.

## Data/tools/scheduling owner
`LocalRepository(context)` in `com.user.localagent.data`: encrypted Room, conversations/notes/memories/reminders/tool_runs/grants, public suspend methods and Flow observers; publish signatures before integration.
`ToolExecutor(context, repository, scheduler)` in `.tools`: `suspend fun execute(call: ToolCall): ToolResult`, `fun prepareIntent(call): Intent?`; Activity launch is root UI responsibility.
`ReminderScheduler(context)` in `.scheduling`: schedule/cancel/restore; `AlarmManager` exact where allowed, fallback status explicit; persistent WorkManager for inexact work.

## Ownership
Root: Gradle/config/manifest/UI/container/GitHub workflow/docs, extension/updater initially.
Core worker: `core/src/**` only.
Inference worker: app `inference/**`, `models/**`, `benchmark/**` only.
Data/tools worker: app `data/**`, `tools/**`, `scheduling/**` only.
Workers do not change Gradle, manifest, shared documents, branches, or commit until root integration permits. No user project edits. No private data/network requests beyond required official documentation.
