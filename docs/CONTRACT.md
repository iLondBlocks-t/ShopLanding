## 8. SHARED API CONTRACT v1 (identical in both prompts — do not change)

Base URL: `http(s)://&lt;host&gt;:&lt;port&gt;` with no trailing slash. All bodies are JSON UTF-8. Errors: the right HTTP status plus `{"error":"&lt;snake_case_code&gt;","message":"&lt;human readable&gt;"}`.

Auth: every endpoint except `GET /health` and `POST /pair/redeem` requires `Authorization: Bearer &lt;deviceToken&gt;`, otherwise `401 {"error":"unauthorized"}`.

PUBLIC

- `GET /health` → `200 {"ok":true,"gateway":"1.0.0","opencode":{"running":bool,"version":string|null},"uptimeSec":int}`
- `POST /pair/redeem` body `{"code":"ABCD-EFGH","deviceName":"Pixel 6"}` → `200 {"deviceId":"…","deviceToken":"dt_…"}` | `401 invalid_code` | `429 rate_limited` (with `Retry-After`) | `409 too_many_devices`

GATEWAY (auth required)

- `GET /gateway/status` → `{"gateway":{"version","uptimeSec","startedAt"},"opencode":{"running","version","restarts","lastError":string|null},"github":{"connected","login":string|null,"scopes":[string]},"system":{"freeDiskMB","memTotalMB","memFreeMB"},"tools":{"git":bool,"node":"v20.x","java":bool,"gradle":bool,"androidSdk":bool},"device":{"id","name"}}`
- `GET /gateway/devices` → `[{"id","name","createdAt","lastSeenAt","current":bool}]`
- `DELETE /gateway/devices/{id}` → `204` (revoking the current device is allowed)
- `POST /gateway/devices/rotate` → `200 {"deviceToken":"dt_…"}` (old token dies immediately)
- `GET /gateway/github` → `{"connected":bool,"login":string|null,"scopes":[string]}`
- `PUT /gateway/github` body `{"token":"…"}` → validates against the GitHub API; `200 {"connected":true,"login","scopes"}` | `400 invalid_github_token`
- `DELETE /gateway/github` → `204`
- `GET /gateway/github/repos?page=1` → `[{"fullName","private","defaultBranch","description","updatedAt"}]` (30 per page, sorted by `updated`)
- `POST /gateway/github/repos` body `{"name","private":true,"description":""}` → `201 {"fullName","defaultBranch","cloneUrl"}` | `403 github_permission_denied`
- `GET /gateway/projects` → `[{"id","name","repoFullName":string|null,"branch":string|null,"archived":bool,"createdAt","lastActivityAt"}]`
- `POST /gateway/projects` body `{"name","repoFullName"?:"owner/repo","createRepo"?:{"private":true}}` → `201 &lt;project&gt;`
- `PATCH /gateway/projects/{id}` body `{"name"?,"archived"?}` → `200 &lt;project&gt;`
- `DELETE /gateway/projects/{id}?confirm=true` → `204`; without `confirm=true` → `400 confirmation_required`
- `GET /gateway/projects/{id}/git` → `{"branch","dirty":bool,"ahead":int,"lastCommits":[{"sha","subject","date"}]}`
- `GET /gateway/projects/{id}/artifacts` → `[{"path","size","sha256","modifiedAt","kind":"apk-debug"|"apk-release"|"apk"|"zip"|"log"|"other"}]`
- `GET /gateway/projects/{id}/download?path=&lt;relative path&gt;` → raw file stream with `Content-Length`, `Accept-Ranges: bytes`, `Content-Disposition`; `404 not_found`; `403 forbidden_path`; supports `Range`.

Other error codes the server may return: `github_required`, `git_unavailable` (501), `insufficient_storage` (507), `project_exists` (409), `project_not_found` (404), `opencode_unavailable` (503), `payload_too_large` (413), `invalid_json` (400), `github_token_invalid`, `github_rate_limited`, `github_unreachable`, `internal_error` (500). Unknown codes must still produce a readable message.

OPENCODE PASSTHROUGH (auth required)

- `ANY /oc/&lt;path&gt;` is forwarded by the gateway to the private OpenCode server at `/&lt;path&gt;` (method, query string, body preserved). Header `X-Project-Id: &lt;id&gt;` selects the project (the gateway injects the working directory itself — the app never sends a directory). Without the header, no directory is injected (use for global calls like providers). SSE (`/oc/event`) streams unbuffered with `: ping` comments when idle.
