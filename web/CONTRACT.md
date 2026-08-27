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
share-link lifecycle, browser UI, deployment on Nginx with TLS.

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

Everything under `/api`, same origin. Nginx proxies it to Spring Boot, so there is no CORS
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
| PATCH  | /files/{id}                 | `{name}`, needs `edit` |
| DELETE | /files/{id}                 | needs `delete` |

Asking for a tier above the permit is not an error. The server clamps it down to the
permitted tier and records what it actually served. Returning 403 there would let a caller
map out the policy by watching which requests fail.

### Upload

| Method | Path                             | Body        | Returns |
|--------|----------------------------------|-------------|---------|
| POST   | /files/initiate                  | `{name,mime,size}` | `{uploadId,chunkSize,received}` |
| PUT    | /files/{uploadId}/chunk/{index}  | raw bytes   | `{received}` |
| POST   | /files/{uploadId}/complete       | `{sha256}`  | `FileMeta` |
| DELETE | /files/{uploadId}                |             | 204 |

`received` holds the chunk indices already stored, so an interrupted upload can resume.
`chunkSize` is chosen by the server so it can be tuned without a client release.
Renditions are generated at upload time and stored encrypted.

### Shares

| Method | Path                  | Notes |
|--------|-----------------------|-------|
| GET    | /files/{id}/shares    | needs `share` |
| POST   | /files/{id}/shares    | `{accessLevel,actions,expiresAt,maxViews}` |
| POST   | /shares/{id}/revoke   | needs `revoke` |
| GET    | /s/{token}            | public, returns `{file,permit}` |
| GET    | /s/{token}/content    | public, increments `viewsUsed` |

### Audit

`GET /audit` with `fileId, actorId, action, result, from, to, page, pageSize`.
Returns `{events, total, page, pageSize}`.

Read by Dhawal's audit viewer. The data plane emits the events that feed it (upload,
rendition served, share created, share revoked, view counted) but does not render them.

## Errors

```json
{ "error": { "code": "denied", "message": "...", "reason": "policy:viewer" } }
```

| Status | Code            | Meaning |
|--------|-----------------|---------|
| 401    | unauthenticated | no or invalid session |
| 403    | denied          | policy engine refused, `reason` says why |
| 404    | not_found       | unknown, or hidden from this subject |
| 410    | gone            | share expired, revoked, or out of views |
| 413    | too_large       | over the upload cap |
| 429    | rate_limited    | magic-link throttle |

403 and 410 stay separate. One means the user lacks access, the other means the link is
finished. They read as different problems to whoever hit them.

## Open

1. The audit screen. Slide 9 gives the log viewing interface to Dhawal, slide 7 marks all
   browser UI as mine. Needs one decision.
2. `chunkSize`. Proposing 8 MiB.
3. Maximum upload size. Undecided. The UI assumes 2 GiB for now.
4. Whether `edit` covers rename only or replacing contents too. UI does rename.
