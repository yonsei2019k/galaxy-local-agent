# ZipHap PC App Server adapter

This crate is an owned stdio client, not an open app-server RPC proxy. The local operator pins the Codex executable with SHA-256 and selects an existing runtime directory. The adapter uses that CLI's existing ChatGPT login; it strips API-key environment variables and exposes no account email, workspace name or token.

```
ziphap-pc --config <absolute-private-config.json> --health
ziphap-pc --config <absolute-private-config.json> --models
ziphap-pc --config <absolute-private-config.json> --stdio
```

The private config has exactly `executable`, `executableSha256`, and `runtimeDirectory`. Do not put it, credentials, conversations, screenshots or runtime logs in Git. Health and model catalog operations never start an inference turn. `isolationQualified` describes the fixed effective-configuration checks; model availability in a catalog does not prove entitlement or inference success.

The stdio adapter accepts only typed `health`, `models`, `start`, `tool_result`, `refresh_phone_authority`, and `cancel` operations. It rejects duplicate JSON keys, unknown fields, arbitrary RPC, executable paths, commands, configuration updates and overlong input before allocation grows beyond the fixed line bound. It creates ephemeral threads. An in-memory thread is reused only when owner/project/thread/authority/profile, model/effort and the complete fresh tool descriptor digest match. There is no disk resume/replay path. Cancel destroys the owned process tree and every cached thread.

`start.request` includes `requestId`, `binding`, `model`, optional `effort`, `ownerRequest`, fresh `tools`, explicit `externalAllowed`, optional `phoneAuthorityExpiresAtEpochMs` and `timeoutMs` (at most 600000). Each tool carries a name, description, strict object schema and `location` (`pc` or `phone`). Returned tool calls are proposals that the independent phone/PC broker must authenticate, validate and approve. The adapter does not execute them. Results require the exact active request and one pending call ID and are consumed once. A `reported_done` model report never constructs VERIFIED receipts or completes a host goal.

PC-only work is bounded by its original run deadline and does not require an open phone UI. Phone proposals, queued delivery and result disclosure additionally recheck a fresh, at-most-30-second phone lease. Only the trusted local broker adapter can refresh that lease, keeping the exact captured authority revision and tool set. A timestamp in this IPC protocol is not a replacement for the phone broker's authenticated foreground keepalive/approval.

Events expose allowlisted progress stages and top-level answer text deltas, never raw reasoning or generic server notifications. Every content event must match the exact active thread and turn. Unknown tools, built-in tool items, server approval requests, stale content, malformed data, expired authority and cancellation fail closed. The process has no visible console on Windows. A kill-on-close Windows Job Object or an owned POSIX process group includes descendants; no existing user process is enumerated or controlled. The browser foundation always builds a headless command with a new owned profile and CDP pipe; it is not a complete web-reading implementation.

Before `turn/start`, effective local configuration must show the fixed disabled built-in features, no configured MCP servers, disabled web search, the expected provider and no ambient model instruction file. If the pinned CLI cannot expose those checks, inference is refused. These checks and offline fixtures are **not full qualification of every built-in capability in an arbitrary Codex release**. Keep the production route disabled until the exact pinned CLI's effective tool inventory/sandbox is independently qualified. The Python provider is a separate existing runtime path; this crate does not silently replace or deploy it.

Protocol reference: [official OpenAI App Server documentation](https://developers.openai.com/codex/app-server/). Schemas were generated locally from the installed CLI without starting a model. Tests compile a small offline stdio fixture executable and exercise pins, catalog/account redaction, exact thread reuse, strict IPC, pending-call/result binding, stale/unknown tool rejection, deadlines, cancellation and actual owned descendant cleanup. Windows was tested here; POSIX cleanup is implemented but needs execution on that platform.
