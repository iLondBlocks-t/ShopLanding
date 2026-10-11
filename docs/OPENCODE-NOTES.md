# OpenCode API verification notes

## Sources actually inspected

- Official server documentation: https://opencode.ai/docs/server/ (retrieved during implementation, including the second chunk).
- Real classic OpenAPI 3.1 document: [`packages/sdk/openapi.json` at v1.18.35](https://github.com/anomalyco/opencode/blob/53d1eabb61e21162157817bf677da0a4ad3332e3/packages/sdk/openapi.json).
- Pinned upstream commit: `53d1eabb61e21162157817bf677da0a4ad3332e3`, release **v1.18.35**. OpenAPI `info.version = 1.0.0` is the schema version, **not** the CLI release version.
- Pinned server session route handlers/groups, session/status.ts, and packages/schema/src/identifier.ts were read to verify pagination, idle-map semantics and ascending identifiers.
- The development branch's v2 OpenAPI was examined and rejected: its shapes are incompatible with this classic client. This is not a claim of compatibility with arbitrary future v2 servers.

The shared gateway contract is in [CONTRACT.md](CONTRACT.md). Nothing here adds gateway endpoints or sends a working directory from the phone. All OpenCode paths below are prefixed with `/oc` when used through the gateway. Auth and `X-Project-Id` are attached only as prescribed; providers are a global call without a project header.

## Confirmed endpoints and shapes

| Operation | Real upstream route | Confirmed shape |
|---|---|---|
| List/create session | GET/POST `/session` | Session[] / Session; creation body `{}` |
| Session metadata | GET `/session/{sessionID}` | Session (`id`, `title`, `time.created/updated`, etc.). **No status field** |
| Status recovery | GET `/session/status` | Session-ID → `{type:"busy"}`, `{type:"idle"}` or `{type:"retry",attempt,message,next}` |
| Messages | GET `/session/{sessionID}/message?limit=400&before=…` | `[{info:Message,parts:Part[]}]`; oldest-to-newest within a page |
| Async prompt | POST `/session/{sessionID}/prompt_async` | `{parts:[{type:"text",text}],model?:{providerID,modelID},messageID?:string}`; **204, empty body** |
| Sync compatibility fallback | POST `/session/{sessionID}/message` | Same text/model/messageID inputs; `{info:AssistantMessage,parts:Part[]}`. Used only when async is 404/405 |
| Abort | POST `/session/{sessionID}/abort` | boolean acknowledgement; cancellation is not fabricated when false |
| Model choices | GET `/config/providers` | `{providers:Provider[],default:Record<string,string>}`; each provider has a **models object**, not an array |
| Pending permissions | GET `/permission` | PermissionRequest[]; scoped by each request's sessionID |
| Modern permission reply | POST `/permission/{requestID}/reply` | `{reply:"once"|"always"|"reject"}` → boolean |
| Deprecated reply fallback | POST `/session/{sessionID}/permissions/{permissionID}` | `{response:"once"|"always"|"reject"}` → boolean; only on 404/405 of modern route |
| File diff | GET `/session/{sessionID}/diff` | SnapshotFileDiff[]: additions/deletions, optional file/patch/status. **Not before/after text** |
| Stream | GET `/event` | SSE `data:` contains Event JSON, not the gateway's global-event wrapper |

### Pagination is a header, not a guessed ID

The real handler decodes an **opaque** `before` cursor with `MessageV2.cursor.decode`. It emits **X-Next-Cursor** and a Link continuation header. OC Pilot forwards that exact cursor unchanged (URL-encoded). It never sends a message ID as a cursor. `before` requires `limit`; the client always supplies 400. If the gateway strips both continuation headers, the client does not invent pagination: an older-server page cannot be requested, although local earlier windows remain available.

### Idle is absence from the status map

The pinned SessionStatus list contains tracked busy/retry sessions. Setting idle removes a map entry. Consequently, a successful status-map response with the current ID absent confirms idle. An unsuccessful request does **not** mean idle. Session metadata alone is insufficient for status.

### Stable prompt identity and task binding

`messageID` is explicitly optional in the real prompt schema. The client creates one persistent `msg_` identifier per local task, using the verified ascending suffix algorithm: 12 hex timestamp/counter characters plus 14 random alphanumeric characters. Assistant info's `parentID` binds tool parts to the corresponding user/task. Requests with uncertain transport results are resynchronized before being treated as unacknowledged; the client does not blindly retransmit them on reconnection. Retry is an explicit user action and creates a new task.

## Confirmed events used by the reducer

- `message.updated`: `properties.info` contains the actual user/assistant info, with `sessionID`.
- `message.part.updated`: `properties.part` is a full part; `properties.time` is its event revision. A full text snapshot **replaces**, rather than appends to, the existing part.
- `message.part.delta`: `sessionID,messageID,partID,field,delta`; only text/reasoning `field:"text"` is appended. Missing initial parts trigger recovery rather than guessing a type.
- `message.removed` / `message.part.removed`: remove actual IDs.
- `session.status`, `session.idle`, `session.error`: real busy/retry/idle/error signals. MessageAbortedError means cancelled, not failed.
- `permission.asked` / `permission.replied`: request IDs, patterns, metadata and reply values. Requests remain visible until real acknowledgement/reply. No automatic decision is made.
- `session.diff`: actual SnapshotFileDiff[]; PatchPart.files is used for changed-file links.
- `file.edited` has **no sessionID** in this spec and is intentionally ignored, rather than contaminating another session.
- `server.connected` and unknown/unrelated events do not alter session state. Nested wrong-session info/parts are also rejected.

ToolPart state is the real discriminated union: pending (`input,raw`), running (`input,time.start`, optional title/metadata), completed (`input,output,title,metadata,time.start/end`), or error (`input,error,time.start/end`). Build/test/read labels are derived from tool names and actual shell commands. No planning/building/testing percentages are invented.

## Fixtures and verification boundary

`app/src/test/resources/fixtures/` contains **40 schema-validated OpenCode JSON examples**, gateway-contract examples, a deliberately zero-byte 204 body, and a source/schema manifest. Values are concrete **schema-derived examples**, not captures from a running gateway and not mock endpoints. JSON schema validation was executed against the real pinned OpenAPI document. The six pure-Java JUnit suites exercise these examples, replacement/delta behavior, replay, wrong-session rejection, permissions, abort, stale terminal tools, growing reconnect snapshots and SHA-256.

No live gateway, Android device or emulator was supplied. Therefore real HTTP/SSE recovery, server capability compatibility and Android lifecycle behavior remain **NOT VERIFIED end-to-end**. See [VERIFICATION.md](VERIFICATION.md).
