# Orchestration v1: offline contract prototype

ARCH-P1-01, 2026-11-15. This is a small, synthetic contract subset, **not a released wire protocol, service, authority issuer, or connected broker**. It follows the [integrated architecture decisions](../../../docs/architecture/README.ko.md).

## Files and scope

| File | Meaning |
|---|---|
| `task-request.schema.json` | Untrusted submission intent. Three proposed operations: inspect workspace, propose artifact, write artifact. Opaque scope, target, provider, input digest and requested capabilities are proposals, not grants. |
| `control.schema.json` | Owner-control shape: approve/start/cancel/retry, bound to request digest, scope, epoch and expected job revision. Parsing does not authenticate an owner. |
| `host-observation.schema.json` | Host observation shape: verified/unknown/not-applied effect or cancellation acknowledgement. Parsing does not authenticate an observer or prove an effect. |
| `fixtures/messages.json` | 27 synthetic wire cases: 9 shape-accepted,18 rejected. Raw strings preserve duplicate keys and invalid number lexemes. |
| `fixtures/state-cases.json` | 7 synthetic state scripts with31 steps. This is a **test DSL**, not wire JSON. The harness derives the request digest from the parsed task; controls/observations use the explicit expected revision in each step. |

An accepted `external_agent` intent is only structurally valid. The separate host-authority input defaults to local-only and denies it unless the trusted caller explicitly supplies external permission. No existing Android legacy payload is migrated or reinterpreted by these schemas. Missing v1 fields are rejected. New provider authority is not borrowed from `allowCloudText`.

## Decoder rules beyond JSON Schema

The [stdlib prototype](../../../pc-worker/orchestration/contracts.py) additionally enforces UTF-8 bytes≤16KiB, structural depth≤8, nodes≤512, no duplicate keys at any depth, no NaN/Infinity, no unpaired surrogate and integer lexemes only. Booleans are not integers; integers are limited to ±(2^53−1), with nonnegative epoch/revision fields. Decimal/exponent tokens, including `1.0` and `1e0`, are rejected even where a general JSON Schema validator may consider them integral.

IDs are1–64 ASCII opaque characters, not paths. Digests are64 lowercase hexadecimal characters. Capability arrays have1–3 distinct known entries. Objects reject unknown fields; `source`, `grant`, arbitrary `VERIFIED`, credentials and additional authority fields cannot be smuggled into the three shapes. Error text contains only a fixed error code.

The schemas use a small local subset of draft2020-12: type/const/enum, properties/required/additionalProperties, length/pattern, integer bounds, bounded arrays/uniqueItems, oneOf and conditional allOf. The Python checker implements only this subset; it is not a general JSON Schema implementation and performs no remote schema resolution. Tests compare the published schema documents with the implementation's exported shapes. Independent general-purpose metaschema validation and Kotlin parity have not been run.

Canonical task hashing uses parsed v1 fields, sorted object keys, compact ASCII JSON and sorted capability entries. Whitespace, key order and capability order do not change the digest; operation/input/scope/provider/request identity changes do. `input_digest` is a synthetic intent field here: actual input bytes, resource versions and full-read proof are not observed by this prototype.

## Trust and state boundaries

`TaskRequest` is separate from `HostAuthority`/`ExecutionBinding` in the [state prototype](../../../pc-worker/orchestration/state.py). Those Python objects are trusted **test inputs**, not cryptographic credentials or an implementation of authentication. The caller supplies `Source.OWNER` or `Source.HOST` out of band. A wire field cannot select that enum, but a malicious in-process Python caller is not isolated by these types. A real endpoint must authenticate callers, reconstruct authority from its ledger and verify current state before invoking this library.

The prototype captures owner/project/branch/scope/target/authority epoch/revision and limits requested capabilities to the current host subset. Authority revision and job CAS revision are separate counters. Each control and observation rechecks the binding and expected job revision. Read/proposal operations start logically ready; writes require a request-bound, single-use approval. `start` changes an in-memory state only; it does not execute a tool.

Same owner+idempotency key+canonical task returns the same snapshot. A different payload conflicts; a changed epoch/authority cannot resurrect the old job. Unknown effects pause and cannot retry automatically. A trusted synthetic not-applied observation permits a later explicit owner retry; writes then require a new approval. Attempts default to2 (configurable1–3), and the in-memory job capacity is64 with no hidden eviction.

Cancellation request is not acknowledgement. Running cancellation preserves possible/known effects; host acknowledgement may leave the effect unknown. A later host-attested effect after an unknown cancellation is retained without changing the cancelled job into successful completion. A stale/revoked authority cannot submit a cleanup observation through this small interface; production reconciliation after authority revocation needs a separate restricted receipt/status path.

## Reproduce

Use a compatible CPython3.10+ interpreter from the repository root; no packages or paid keys are required:

```text
python -I -B -m unittest discover -s pc-worker/orchestration -p "test_*.py" -v
```

2026-11-15 offline result: **38 unittest methods passed,0 failures/errors/skips**. Golden cases and31 script steps run inside those methods and are not added to38 as independent test methods. This is T1 synthetic evidence only. No Android/core build, APK installation, network, provider, agent process or model invocation was performed.

Not implemented: real authentication/authorization issuance, signature/proof validation, durable ledger/encryption/TTL, resource or file readback, execution adapter, HTTP/MCP endpoint, Android/Kotlin parser, event cursor/ACK, process cleanup, parent-child graph, actual provider/model/phone/external effects. `Effect.VERIFIED` means a synthetic host assertion passed this state machine; it is not an actual storage/send/navigation verification.
