# Permit API

Interface between the two halves of the project. Both sides build against this file.
Nothing gets added to a request or response without changing this document first.

Dhawal Harfore (26MCA0163), control plane:
Spring Boot app and REST layer, Spring Security and request filtering, policy engine,
AES-256-GCM encrypt/decrypt, per-file key generation and release, MySQL schema,
audit logging pipeline and the audit log viewing interface.

Nandishwar Singh (26MCA0137), data plane:
magic-link auth (token issue, verification, expiry) and Resend email delivery,
storage service with chunked upload and download, quality rendition pipeline,
share-link lifecycle, browser UI, deployment on Caddy with TLS.

Token handling splits at `/auth/verify`. I validate the token, Spring Security takes over
and owns the session from there.

The browser does not decide permissions. Every screen renders from a `Permit` the policy
engine returned. If the permit does not list an action, the UI does not offer it, and the
server rejects it anyway.

## Values

```
AccessLevel  owner | editor | viewer | guest
Action       view | download | edit | share | revoke | delete
QualityTier  original | reduced | lowest | metadata
FileKind     image | video | pdf | other
```

### Access levels

| Level  | Operations                                  | Quality served      |
|--------|---------------------------------------------|---------------------|
| Owner  | view, edit, download, share, revoke, delete | Original            |
| Editor | view, edit, download                        | Original            |
| Viewer | view, download if permitted                 | Reduced             |
| Guest  | preview only                                | Lowest / first page |

A policy can control view, download, edit, share, expiry, max views and quality tier.

Viewer download is the one opt-in case. It stays off unless a policy sets it, which is
what "download if permitted" means in the table above.

### Quality tiers

Tiers only apply to images, video and PDF. Everything else has no middle rendition, so the
tier collapses to allow or deny on download:

- owner, editor, viewer: `original` when download is permitted, otherwise `metadata`
- guest: always `metadata`

`lowest` means a downscaled still for image and video, and first page only for PDF.
`metadata` means name, size, type and timestamps. No bytes. A permit at the `metadata`
tier always has `keyHandle: null`.

A policy can lower a tier. It cannot raise one above what the access level allows.

## Types

### Permit

```json
{
  "fileId": "f_9c1e0001",
  "subjectId": "u_41a2c8d1",
  "accessLevel": "viewer",
  "actions": ["view", "download"],
  "qualityTier": "reduced",
  "keyHandle": "kh_9c1e0001_reduced",
  "expiresAt": "2026-09-03T18:30:00Z",
  "maxViews": 10,
  "viewsUsed": 3,
  "reason": "policy:viewer"
}
```

`subjectId` is `share:<token>` for an unauthenticated guest.
`actions` is the complete allowed set. The client does not derive actions from
`accessLevel`; that field is for display.
`keyHandle` is opaque to the browser, and null when no bytes may be served.
`expiresAt` and `maxViews` are null when unlimited.
`reason` is shown to the user when something is denied.

### FileMeta

```json
{
  "id": "f_9c1e0001",
  "name": "Q3-architecture-review.pdf",
  "mime": "application/pdf",
  "size": 2481920,
  "kind": "pdf",
  "ownerId": "u_41a2c8d1",
  "ownerEmail": "nandishwar@vit.ac.in",
  "createdAt": "2026-08-20T09:12:00Z",
  "updatedAt": "2026-08-20T09:12:00Z",
  "renditions": ["original", "reduced", "lowest"],
  "permit": {}
}
```

`permit` is embedded on list and detail responses so the file list does not need one
request per row. `renditions` lists what actually exists on disk.

### Share

```json
{
  "id": "s_22b80001",
  "fileId": "f_9c1e0001",
  "token": "3f9a1c7e5b2d",
  "accessLevel": "guest",
  "actions": ["view"],
  "expiresAt": "2026-09-01T00:00:00Z",
  "maxViews": 5,
  "viewsUsed": 2,
  "revokedAt": null,
  "createdAt": "2026-08-25T10:00:00Z",
  "createdBy": "u_41a2c8d1"
}
```

A share is live when `revokedAt` is null, `expiresAt` is null or in the future, and
`viewsUsed` is below `maxViews`. Anything else returns 410.

### AuditEvent

```json
{
  "id": "a_5511a0c2",
  "at": "2026-08-26T14:03:22Z",
  "actorId": "u_41a2c8d1",
  "actorEmail": "nandishwar@vit.ac.in",
  "fileId": "f_9c1e0001",
  "fileName": "Q3-architecture-review.pdf",
  "action": "download",
  "result": "permit",
  "qualityTier": "reduced",
  "shareId": null,
  "ip": "103.21.244.11",
  "userAgent": "Mozilla/5.0"
}
```

## Endpoints

Everything under `/api`, same origin. Caddy proxies it to Spring Boot, so there is no CORS
setup. Session is an HttpOnly, Secure, SameSite=Lax cookie.

### Auth

| Method | Path               | Body      | Returns |
|--------|--------------------|-----------|---------|
| POST   | /auth/magic-link   | `{email}` | 202 `{ok:true}` |
| POST   | /auth/verify       | `{token}` | 200 `{user}` and sets the session cookie |
| GET    | /auth/session      |           | 200 `{user}` or 401 |
| POST   | /auth/logout       |           | 204 |

Tokens are single use, time limited, and stored hashed. `/auth/magic-link` returns 202 for
any address. It must not leak whether an account exists, through the status, the body or
the response time.

`/auth/verify` returns 400 for a bad token and 410 for one already used or expired.

### Files

| Method | Path                        | Notes |
|--------|-----------------------------|-------|
| GET    | /files                      | `FileMeta[]` with permits embedded |
| GET    | /files/{id}                 | |
| GET    | /files/{id}/permit          | re-evaluated live |
| GET    | /files/{id}/content?tier=   | bytes at the permitted tier |
| GET    | /files/{id}/thumb           | 404 when kind is `other` |
| PATCH  | /files/{id}                 | `{name}`, needs `edit`, returns `FileMeta` |
| DELETE | /files/{id}                 | needs `delete`, 204 |

Rename cleans the name the same way upload does. Delete hides the file at once, revokes its
share links, destroys every key the file used and removes its encrypted bytes. The row stays
in `stored_files` with `deleted_at` set, so the audit trail still points at a real file. A file
you can't see answers 404, not an empty 200.

### People

| Method | Path                                  | Notes |
|--------|---------------------------------------|-------|
| GET    | /files/{id}/people                    | needs `share`, returns `[{id,email,accessLevel,actions}]` |
| POST   | /files/{id}/people                    | `{email,accessLevel,download}`, needs `share` |
| DELETE | /files/{id}/people/{permissionId}     | needs `revoke`, 204 |

`accessLevel` is `editor` (view, edit, download), `viewer` (view, plus download when
`download` is true) or `guest` (view). Giving access to the same email again changes it.
The person doesn't need an account yet; they see the file the first time they sign in.

Asking for a tier above the permit is not an error. The server clamps it down to the
permitted tier and records what it actually served. Returning 403 there would let a caller
map out the policy by watching which requests fail.

### Upload

| Method | Path                             | Body        | Returns |
|--------|----------------------------------|-------------|---------|
| POST   | /files/initiate                  | `{name,mime,size}` | `{uploadId,chunkSize,received}` |
| GET    | /files/{uploadId}                |             | `{uploadId,chunkSize,received}` |
| PUT    | /files/{uploadId}/chunk/{index}  | raw bytes   | `{received}` |
| POST   | /files/{uploadId}/complete       | `{sha256}`  | `FileMeta` |
| DELETE | /files/{uploadId}                |             | 204 |

`received` holds the chunk indices already stored, so an interrupted upload can resume.
`chunkSize` is chosen by the server so it can be tuned without a client release.
Renditions are generated at upload time and stored encrypted.

## Data plane details (proposed, needs Dhawal's ok)

Everything in this section is built and tested on my side. Dhawal, flag anything you want
changed before you build against it.

### Uploads

- `uploadId` is `up_` plus 20 base32 characters. `fileId` is `f_` plus 16 hex characters,
  assigned at complete. Upload routes only match `up_…` and file routes only match `f_…`, so
  `DELETE /files/{uploadId}` and `DELETE /files/{id}` don't collide.
- `chunkSize` is 8 MiB. Every chunk except the last must be exactly that size. Chunks can
  arrive in any order and can be sent again. `GET /files/{uploadId}` lets a reloaded page
  find out what it already sent.
- Uploads are capped at 2 GiB (413). A user can have 4 unfinished uploads at once (429).
  An upload with no new chunk for 24 hours is dropped.
- Staged chunks are encrypted under `kh_<upload>_staging`, and that key is destroyed on
  complete, abort or expiry. Nothing is written to disk in plaintext. The one exception is a
  rendition job's working copy, which sits in a 0700 directory only while the job runs.
- `complete` checks the SHA-256 of the whole file. On a mismatch the upload is discarded and
  the client starts over. Otherwise the original is encrypted under `kh_<file>_original`,
  added to the catalog, and `FileMeta` comes back with `renditions: ["original"]`.

### Renditions

Made in the background after complete, one file at a time, at low CPU priority, so a big
video never holds up a request. `renditions` grows as each one is stored. `lowest` comes
first because it takes about a second.

| Kind  | reduced | lowest |
|-------|---------|--------|
| video | WebM, VP9 CRF 32 and Opus 96k, fits 720x540, metadata stripped | JPEG frame at 1s, 480 wide |
| image | JPEG, fits 1280x1280, transparency flattened onto white | JPEG, fits 480x480 |
| pdf   | image-only PDF at 72 dpi, no selectable text | JPEG of page 1 at 50 dpi |
| other | none | none |

Each is encrypted under `kh_<file>_<tier>`. A rendition that fails is skipped. A job that
keeps crashing gives up after 3 attempts across restarts. On the VPS, VP9 at these settings
took 30 s of CPU for 15 s of 1080p HEVC.

### Content

- `GET /files/{id}/content?tier=` serves the best rendition that exists at or below the
  lower of `tier` and `permit.qualityTier`, and names it in `X-Quality-Tier`. 404 when none
  exists yet. 403 `denied` when the permit is `metadata` or has no key handle.
- One `Range: bytes=` range per request is supported (206, 416), so video can seek.
- Originals a browser could run (HTML, SVG, anything that isn't image, video, audio, PDF or
  plain text) go out as `application/octet-stream` attachments. Every content response has
  `nosniff` and `Cache-Control: private, no-store`, and everything except PDF gets a sandbox CSP.
- A `view` audit event is recorded for each request that starts at byte 0, not for every
  range request, so seeking through a video doesn't flood the log.
- `GET /files/{id}/thumb` serves `lowest` whenever the permit allows any bytes.

### What the data plane needs from the control plane

Four Spring beans in `com.dhawal.secure_file_storage.controlplane`. Until all four exist the
file routes return 503 `unavailable`.

| Interface | Methods | Notes |
|-----------|---------|-------|
| `FileCipher` | `encrypt(keyHandle, out)`, `decrypt(keyHandle, channel, plaintextOffset)`, `destroy(keyHandle)` | AES-256-GCM. A handle's key is created on its first encrypt, and every encrypt call is an independent ciphertext. `decrypt` has to start at any plaintext offset without reading from the beginning, so the GCM needs to be in segments (say 64 KiB, each with its own nonce and tag). `destroy` shreds the key. |
| `PermitService` | `evaluate(fileId, subjectId)` | Returns the `Permit` above. Empty `actions` means the file is hidden (404). |
| `FileCatalog` | `add`, `find`, `addRendition` | Stores `StoredFile`, including each rendition's mime, plaintext size and key handle. |
| `AuditLog` | `record(AuditEvent)` | Feeds the audit pipeline. |

Sessions live in MySQL through Spring Session, so a restart or deploy doesn't sign anyone
out. The cookie is `vault_session`, HttpOnly, Secure, SameSite=Lax, and lasts 12 hours.

Per-file keys are wrapped with AES-GCM under a master key from `VAULT_MASTER_KEY`, which
sits in `/etc/vault/vault.env` and not in the file store or the backups. Losing it means
losing every file, so it has to be kept somewhere safe off the server too.

CSRF uses a cookie the page can read. Every response sets `XSRF-TOKEN`, and every
non-GET request outside `/api/auth/*` has to send the same value back in `X-XSRF-TOKEN`.

### Shares

| Method | Path                  | Notes |
|--------|-----------------------|-------|
| GET    | /files/{id}/shares    | needs `share` |
| POST   | /files/{id}/shares    | `{accessLevel,actions,expiresAt,maxViews}` |
| POST   | /shares/{id}/revoke   | needs `revoke` |
| GET    | /s/{token}            | public, returns `{file,permit}` |
| GET    | /s/{token}/content    | public, increments `viewsUsed` |

- Links are `viewer` or `guest`. `actions` is `view`, plus `download` for viewers only.
  `expiresAt` can be at most a year away, `maxViews` is 1 to 100000.
- Only the SHA-256 of a token is stored, so `token` appears once, in the create response.
  Listing returns `token: null`, and the UI only offers to copy a link right after it's made.
- The public `file` has `ownerId` and `ownerEmail` left out.
- A link's permit follows the access table: a viewer gets `reduced` and a guest `lowest` for
  images, video and PDF. For other files a guest gets `metadata`, and a viewer gets `original`
  only when the link allows download.
- A view is counted when a content request starts at byte 0, with one conditional update, so
  two requests can't both take the last view. Range requests further in don't count, so a
  video can seek after its opening request used the last view.
- An ended link answers 410 `gone` and says whether it was revoked, expired or used up.

### Audit

`GET /audit` with `fileId, action, result, from, to, page, pageSize`.
Returns `{events, total, page, pageSize}`.

You see events on files you own and events you caused, newest first, `pageSize` at most
100. `from` and `to` are ISO instants. Both planes emit events: upload, view, rename, delete,
share and revoke, for links and for people. The Activity page in the UI shows them.

## Errors

```json
{ "error": { "code": "denied", "message": "...", "reason": "policy:viewer" } }
```

| Status | Code            | Meaning |
|--------|-----------------|---------|
| 400    | invalid_upload, invalid_chunk, incomplete_upload, checksum_mismatch, invalid_tier, invalid_request, invalid_share, invalid_person | bad input, `message` says what |
| 401    | unauthenticated | no or invalid session |
| 403    | denied          | policy engine refused, `reason` says why |
| 404    | not_found       | unknown, or hidden from this subject |
| 410    | gone            | share expired, revoked, or out of views |
| 413    | too_large       | over the upload cap |
| 429    | rate_limited    | magic-link throttle, or too many unfinished uploads |
| 503    | email_unavailable | sign-in link could not be submitted to the email provider |
| 503    | unavailable     | a control-plane piece isn't wired in yet |
| 507    | insufficient_storage | the server has no room for the file |

403 and 410 stay separate. One means the user lacks access, the other means the link is
finished. They read as different problems to whoever hit them.

## Open

1. The audit screen. Slide 9 gives the log viewing interface to Dhawal, slide 7 marks all
   browser UI as mine. Needs one decision.
2. `chunkSize`. 8 MiB in the data plane proposal above, needs Dhawal's ok.
3. Maximum upload size. 2 GiB in the data plane proposal above, needs Dhawal's ok.
4. Whether `edit` covers rename only or replacing contents too. UI does rename.
