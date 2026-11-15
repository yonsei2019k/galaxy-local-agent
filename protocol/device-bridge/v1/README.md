# Device bridge v1 (experimental, RAM-only)

This is the actual authenticated broker wire, separate from the existing offline
`protocol/orchestration/v1` prototype. Source implementation is not installed or
device/cloud execution evidence. Root alone integrates the HTTPS Handler, runs
tests, deploys, and validates actual PC/phone/provider calls.

## Trust and integration

`AgentBridge.dispatch_authenticated(method,path,owner,request=None,capability=None)`
returns `(http_status, JSON_object)`. **The existing HTTPS Handler must first
authenticate the paired bearer, validate framing/duplicate headers/body keys,
and obtain the single `X-ZipHap-Compute-Owner` parent-run canonical UUID.** The
module does not read or replace the server/security/runtime configuration.

Session operations also require a role-specific capability. The current normal
request Handler uses `X-ZipHap-Phone-Capability` for phone operations and
`X-ZipHap-Agent-Capability` for MCP agent operations, rejects ambiguous headers,
and supplies the selected value as `capability` to this module. The historical
1086 Handler used the Agent-named header for both roles; its actual evidence
does not qualify the new header integration. Phone registration
returns a fresh phone capability only, held in private Android RAM. Agent capability
is a distinct secret kept by the broker. Neither is a tool argument/result/log.
The model-facing MCP exposes only fixed-session tool catalog/call/status.

`bootstrap_agent(owner,session_id,local_authenticated=True)` is a trusted host
function returning exactly `{sessionId,parentRunId,agentCapability,revision}`.
It requires a live grant and a first phone-capability poll. Root may connect it
only to strictly loopback + existing paired authentication control IPC, matching
the existing private control trust boundary; never a public `/v1/agent` or MCP
route. The bootstrap value is private launcher RAM/environment, not a token file
or model-visible state. Registration is never repeated to recover a capability:
the same parent run is already registered and returns409. A lost create response
does not authorize effects; no agent may start without the confirmed phone.

## Optional model plan and host evidence

Only a true registration adds `requiresPlan:true,plan:null` to the common
snapshot. The agent may propose one ephemeral plan, never a persistent goal.
The plan snapshot has exactly `state,planDigest,requestDigest,goal,requiredTools,
constraints`; state is AWAITING_HOST, ACCEPTED or REJECTED. Goal is 1..1000
UTF-16 code units; constraints are 0..6 strings of 1..240 UTF-16 code units.
`requiredTools` contains 0..12 unique names from the actual session catalog.
Empty means an answer-only interpretation. The virtual gateway `plan_task`
is not a domain tool or catalog member and cannot be selected as a requirement.

`planDigest` is lowercase SHA256 of strict UTF-8 canonical JSON of:
`{version:1,parentRunId,projectBinding,authorityRevision,requestDigest,goal,
requiredTools,constraints}`. Object keys sort lexicographically, compact
separators, non-ASCII retained. Both arrays retain original order; no trimming
or Unicode normalization. Plan state is not a digest input. Python reference:
`hashlib.sha256(canonical(bound)).hexdigest()`.

Only the phone capability can ACK the exact digest with a strict boolean.
Root must bind the current owner/project/permissions, persist TaskGoal,
readback requirements and encrypted run context before ACK acceptance. The
broker does not interpret the goal text as authority. Every domain call must
wait for ACCEPTED and belong to the selected subset of the captured grant.
Each new proposal or ACK increments revision once, never action sequence.
Identical retries return current state without increment; replacement plans,
conflicting ACKs, edits after effects, expired grants or model ACKs reject.
Rejection cannot be changed to acceptance. Legacy false behavior is unchanged.

Host-only `authenticate_phone(owner,session_id,capability,owner_request=None)`
returns the safe common snapshot, never secrets. With owner text it checks a
live confirmed session and SHA256 of its exact unnormalized UTF-8 against the
captured requestDigest. Without text it checks phone role/owner only, including
inactive status/cancellation. Neither form replaces paired HTTPS authentication
or Root's current parent/permission checks. It does not launch anything.

Host-only `task_evidence(owner,session_id,phone_capability)` returns:

```text
sessionId,parentRunId,revision,state,projectBinding,authorityRevision,requestDigest,
acceptedPlan,requiredTools,verifiedPcTools,pcCalls,phoneCalls,
phoneToolsRequiringLocalEvidence,unresolvedCalls,preparedCalls,
phoneEvidenceImported:false,restartPolicy
```

`acceptedPlan` is the exact accepted object or null. Required names retain plan
order; verified PC names are sorted. Every call metadata row has exactly
`callId,sequence,tool,state`. PC rows additionally have
`evidenceSource:"BROKER_PC",verifiedComplete`; actual results add detached
`arguments,result,resultDigest` together. Completeness requires the actual
PC_WORKSPACE source, COMPLETED/VERIFIED successful result, original result
digest, expected path and read-only/unchanged/nonpartial typed content. A file
read also requires complete UTF-8 byte count/SHA; an inspect result requires
complete one-level enumeration. Partial listings do not satisfy coverage.

Phone rows export metadata only, never server phone content/verification as
Android effect evidence. Root must join its actual local Android ledger and
readback proof. Unresolved rows include QUEUED/CLAIMED/RUNNING/UNKNOWN_EFFECT;
PREPARED rows remain explicit blockers. Coverage is operation-level evidence,
not proof of all goal constraints or whole-request completion. The result is
bounded to 262144 wire bytes; over-bound fails instead of silently clipping.
Collect before close erases private result bodies. Status may be inactive;
Root still validates live parent and grant. No AgentReport prose upgrades this
evidence. All state remains RAM-only; restart revokes instead of repairing or
re-registering an old unknown request.

## Routes

| Method/path relative to `/v1/agent/sessions` | Role | Input / output |
| --- | --- | --- |
| `POST` base path (no trailing slash) | paired phone | register below /201 snapshot + `phoneCapability` |
| `GET /<session>` | phone or agent | common snapshot; only phone refreshes lease |
| `POST /<session>/call` | agent | `{callId,tool,arguments}` /202 queued phone call or200 actual PC result |
| `GET /<session>/calls/<call>` | agent | exact own call view, never other sessions |
| `POST /<session>/claim` | phone | `{callId,sequence,revision}` / snapshot with claimed pending call |
| `POST /<session>/result` | phone | `{callId,sequence,revision,result}` / snapshot + `resultAck` |
| `POST /<session>/cancel` | phone | `{revision}` / monotonic revocation snapshot |
| `POST /<session>/close` | phone | `{revision}` / closed snapshot, only after no unresolved call |
| `POST /<session>/plan` | agent | optional requirePlan session only; `{goal,requiredTools,constraints}` / common snapshot |
| `POST /<session>/plan-ack` | phone | `{planDigest,accepted}` / common snapshot |

Registration has required `version:1,parentRunId,projectBinding,authorityRevision,
allowExternalAgent:true,expiresAfterMs,phoneTools,pcWorkspaceProfile,requestDigest`.
Optional `requirePlan` is a strict boolean, default false. Omit it on the legacy
wire. False/omitted snapshots contain neither `requiresPlan` nor `plan` and
retain their existing call/revision behavior. `phone-only` is a reserved
`pcWorkspaceProfile`: no PC tools are advertised or executable and no host
workspace configuration may override that name. Null retains its old no-PC
meaning; other names require an explicitly configured host workspace.
`allowExternalAgent` missing/false denies; it is not `allowCloudText`. Binding and
revision are captured phone authority, never fields selected by the agent.
`requestDigest` is SHA256 of the phone-bound request, not permission or plaintext.
`projectBinding` is an opaque nonblank string<=256, not a model path.

`phoneTools` is a fixed array<=32 of `{name,description,inputSchema}`. Names use
`[a-z][a-z0-9_]{0,63}` and cannot use the reserved `pc_` namespace. Schemas use the
strict bounded subset in the module; unsupported keywords decline registration,
not silently accept. Catalog canonical UTF8<=65536. The phone validates its actual
registry/permission/current scope and owns any approval before every effect.

`pcWorkspaceProfile` is null (no PC tools) or an exact host-configured profile name.
Only `AgentBridge(workspace_profiles={profile:root})` resolves roots; no request
or model controls a physical root, command, URL, shell or ADB operation.

## Common snapshot and revision

Required: `sessionId,revision,state,expiresAtEpochMs,lastSequence,
allowExternalAgent,tools,pendingCall,restartPolicy`. `pendingCall` is null or the
current phone proposal. `resultAck` is optional metadata and may remain in later
snapshots. `phoneCapability` occurs only in the successful registration response.
`agentCapability` never occurs in these HTTP responses.

Session state is `ACTIVE|CANCELLING|REVOKED|CLOSED|EXPIRED`.
`restartPolicy` is exactly `RAM_ONLY_RESTART_REVOKES`. TTL1..900seconds is enforced
using monotonic time. The epoch expiry is immutable display/wire metadata. Phone
poll heartbeat must occur within30seconds; agent polling does not renew it. A
stale/expired/revoked/missing/wrong-owner session cannot propose or claim work.
Phone heartbeat does not renew TTL or authority, and cannot revive a dead session.

`pendingCall` contains exactly `callId,sequence,state,tool,arguments,requestDigest,
projectBinding,authorityRevision` plus optional `claimRevision`. Its state is
`QUEUED|CLAIMED|UNKNOWN_EFFECT`. PC work is never a phone pending call. Sequences
count both devices and may have gaps from the phone's view. A single call may be
active; at most18 calls per immutable parent grant. No same-parent budget reset.

Claim requires current session revision exact CAS. It increments revision and
sets `claimRevision` to the new value. Result body's `revision` must equal this
immutable claim revision, even if cancellation later increments session revision.
Cancel accepts1<=revision<=current revision, so a concurrent proposal cannot block
owner revocation. It never revives authority. Close requires exact current CAS.

Call view is `{callId,sequence,state,result?,resultSource?}`. States are
`QUEUED|CLAIMED|RUNNING|COMPLETED|CANCELLED|UNKNOWN_EFFECT`. A present result has
host-created `resultSource:PHONE_EXECUTOR|PC_WORKSPACE`; the model never submits
this field. `COMPLETED` is transport/result availability, not task/goal completion.
`PREPARED` is not verified external delivery. Only actual successful VERIFIED
observations can support an execution claim; even those do not prove the entire
owner request or a persistent goal complete.

## Result integrity, cancellation and restart

Result has exactly `{success:boolean,content:string,verification,
effectNotApplied:boolean}`; verification is one of
`VERIFIED|PREPARED|UNVERIFIED|FAILED`. Result canonical UTF8<=131072; no clipping is
reported as complete. VERIFIED/PREPARED require success; effectNotApplied requires
failed/FAILED. This is the phone's actual executor report, not model prose.

`resultAck:{callId,sequence,resultDigest}` hashes sorted-key compact JSON with
unescaped Unicode, strict UTF8, no NaN. Same callId+same proposal returns existing
state without dispatch; different payload409. Lost result ACK permits retransmit
of the identical actual result only; different result409. Claim receipt replay
does not authorize the phone controller to execute twice; its own local claim
ledger must prevent that. The broker never automatically retries an execution.

Queued cancellation becomes CANCELLED with no claim. Claimed cancellation becomes
UNKNOWN_EFFECT and blocks close/new calls. The first original actual phone result
may reconcile it, without a new execution or grant. An already submitted unknown
result is sticky in v1; replacing it with a later different result is not supported.
No uncertain write is replayed. Model output cannot provide a phone result.

Personal arguments/results and capabilities are RAM-only, bounded by64 sessions
and18 calls/session. Close drops argument/result bodies. No recovery/journal or
metadata-only persistence is presented as completed execution. Restart loses all
sessions and returns410 `SESSION_MISSING_OR_RESTART_REVOKED`; it never reconstructs
an old session or automatically replays work. Parent tombstones/budgets are only
process-local, not a durable history. The phone/Root must use a fresh parent UUID
for a newly authorized task and must never automatically re-register an old
unknown task after410. Unknown effects require phone-owned reconciliation; the
broker has no persistent historical-parent or repair mechanism yet.

## Initial PC scope and verification limits

`pc_inspect_workspace({path?:relative,limit?:1..100})` enumerates one level, with
truthful partial/enumerationComplete flags. `pc_read_file({path,maxBytes?:1..32768})`
returns a complete small UTF8 file and its actual SHA only; larger/binary files
fail instead of emitting an abbreviated full-file claim. Paths are relative,
no dot traversal/backslash/drive/ADS/device names. Root/profile identity changes,
symlinks/reparse points, special files and hardlinked file reads are denied.

POSIX uses retained directory handles with NOFOLLOW. Windows uses read-only
OPEN_REPARSE_POINT component handles with no write/delete sharing and enumerates
the held directory handle. Native Windows runtime/junction qualification remains
Root's test; source implementation or mocked tests do not prove device behavior.
References for the handle APIs: [CreateFileW](https://learn.microsoft.com/windows/win32/api/fileapi/nf-fileapi-createfilew),
[directory handle information](https://learn.microsoft.com/windows/win32/api/winbase/ns-winbase-file_id_both_dir_info).

Source-only tests use injected clocks/executors and synthetic temporary workspaces.
No server/provider/worker/model/device tests were run by this module's author.
The first actual vertical slice remains Root's integration validation:
cloud MCP→PC inspection + phone clock, then fixture-approved note/save/readback.
