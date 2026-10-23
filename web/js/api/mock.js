import { ApiError } from "./errors.js";
import { evaluate, kindOf } from "./mock-policy.js";

const STORE_KEY = "vault.mock.v1";
const CHUNK_SIZE = 8 * 1024 * 1024;
const TIER_ORDER = ["metadata", "lowest", "reduced", "original"];
const MAX_UPLOAD = 2 * 1024 ** 3;
const KEEP_BYTES_UP_TO = 64 * 1024 * 1024;
const blobs = new Map();
const pendingBytes = new Map();

const nowIso = () => new Date().toISOString();
const rid = (p) => `${p}_${Math.random().toString(16).slice(2, 10)}`;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const latency = () => wait(120 + Math.random() * 220);

const ME = { id: "u_41a2c8d1", email: "nandishwar@vit.ac.in", displayName: "Nandishwar Singh" };
const PARTNER = { id: "u_9f31b706", email: "dhawal@vit.ac.in", displayName: "Dhawal Harfore" };

function seed() {
  const t = (h) => new Date(Date.now() - h * 3600e3).toISOString();
  return {
    user: null,
    tokens: {},
    uploads: {},
    audit: [],
    files: [
      { id: "f_9c1e0001", name: "Q3-architecture-review.pdf", mime: "application/pdf", size: 2481920, ownerId: ME.id, ownerEmail: ME.email, createdAt: t(30), updatedAt: t(30), level: "owner", policy: {} },
      { id: "f_9c1e0002", name: "vault-demo-walkthrough.mp4", mime: "video/mp4", size: 68312064, ownerId: ME.id, ownerEmail: ME.email, createdAt: t(26), updatedAt: t(26), level: "owner", policy: {} },
      { id: "f_9c1e0003", name: "policy-engine-whiteboard.png", mime: "image/png", size: 1204224, ownerId: PARTNER.id, ownerEmail: PARTNER.email, createdAt: t(20), updatedAt: t(20), level: "editor", policy: {} },
      { id: "f_9c1e0004", name: "threat-model.docx", mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", size: 184320, ownerId: PARTNER.id, ownerEmail: PARTNER.email, createdAt: t(14), updatedAt: t(14), level: "viewer", policy: { download: true } },
      { id: "f_9c1e0005", name: "key-rotation-notes.pdf", mime: "application/pdf", size: 512000, ownerId: PARTNER.id, ownerEmail: PARTNER.email, createdAt: t(9), updatedAt: t(9), level: "viewer", policy: {} },
      { id: "f_9c1e0006", name: "encrypted-backups.zip", mime: "application/zip", size: 421400576, ownerId: PARTNER.id, ownerEmail: PARTNER.email, createdAt: t(5), updatedAt: t(5), level: "viewer", policy: { download: false } },
    ],
    shares: [
      { id: "s_22b80001", fileId: "f_9c1e0001", token: "3f9a1c7e5b2d", accessLevel: "guest", actions: ["view"], expiresAt: new Date(Date.now() + 5 * 864e5).toISOString(), maxViews: 5, viewsUsed: 2, revokedAt: null, createdAt: t(12), createdBy: ME.id },
      { id: "s_22b80002", fileId: "f_9c1e0002", token: "aa41d0c93e77", accessLevel: "viewer", actions: ["view", "download"], expiresAt: null, maxViews: null, viewsUsed: 17, revokedAt: null, createdAt: t(8), createdBy: ME.id },
      { id: "s_22b80003", fileId: "f_9c1e0001", token: "deadbeef1234", accessLevel: "guest", actions: ["view"], expiresAt: new Date(Date.now() - 864e5).toISOString(), maxViews: null, viewsUsed: 3, revokedAt: null, createdAt: t(72), createdBy: ME.id },
    ],
  };
}

let db;
try {
  db = JSON.parse(sessionStorage.getItem(STORE_KEY)) ?? seed();
} catch {
  db = seed();
}

function save() {
  try {
    sessionStorage.setItem(STORE_KEY, JSON.stringify(db));
  } catch {}
}

function log(action, meta, tier, shareId = null) {
  db.audit.unshift({
    id: rid("a"),
    at: nowIso(),
    actorId: db.user?.id ?? "anonymous",
    actorEmail: db.user?.email ?? "-",
    fileId: meta.id,
    fileName: meta.name,
    action,
    result: "permit",
    qualityTier: tier,
    shareId,
    ip: "103.21.244.11",
    userAgent: navigator.userAgent,
  });
  db.audit.length = Math.min(db.audit.length, 500);
  save();
}

function requireSession() {
  if (!db.user) throw new ApiError(401, "unauthenticated", "Your session has ended. Sign in again.");
  return db.user;
}

function find(id) {
  const f = db.files.find((x) => x.id === id);
  if (!f) throw new ApiError(404, "not_found", "That file no longer exists.");
  return f;
}

function project(f, subject = db.user) {
  const kind = kindOf(f.mime);
  const level = f.ownerId === subject?.id ? "owner" : f.level;
  return {
    id: f.id,
    name: f.name,
    mime: f.mime,
    size: f.size,
    kind,
    ownerId: f.ownerId,
    ownerEmail: f.ownerEmail,
    createdAt: f.createdAt,
    updatedAt: f.updatedAt,
    renditions: kind === "other" ? ["original"] : ["original", "reduced", "lowest"],
    permit: evaluate({ file: { id: f.id, kind }, subjectId: subject?.id ?? "anonymous", level, policy: f.policy }),
  };
}

function servedFor(meta, tier) {
  const ceiling = meta.permit.qualityTier;
  const asked = tier && TIER_ORDER.includes(tier) ? tier : ceiling;
  return TIER_ORDER[Math.min(TIER_ORDER.indexOf(asked), TIER_ORDER.indexOf(ceiling))];
}

function placeholder(meta, tier) {
  if (typeof document === "undefined") return null;
  if (blobs.has(meta.id)) return blobs.get(meta.id);
  if (meta.kind === "video" || meta.kind === "other") return null;
  const canvas = document.createElement("canvas");
  const wide = meta.kind === "image";
  canvas.width = wide ? 960 : 620;
  canvas.height = wide ? 600 : 820;
  const g = canvas.getContext("2d");
  g.fillStyle = meta.kind === "pdf" ? "#fbfbf9" : "#222c36";
  g.fillRect(0, 0, canvas.width, canvas.height);
  g.fillStyle = meta.kind === "pdf" ? "#17170f" : "#f1efe7";
  g.font = "600 28px system-ui, sans-serif";
  g.fillText(meta.name, 40, 80, canvas.width - 80);
  g.font = "20px system-ui, sans-serif";
  g.fillStyle = meta.kind === "pdf" ? "#54544c" : "#b0ada2";
  g.fillText(`Sample ${tier} copy from the mock server`, 40, 120, canvas.width - 80);
  return canvas.toDataURL("image/png");
}

function demand(meta, action) {
  if (!meta.permit.actions.includes(action)) {
    throw new ApiError(403, "denied", `You don't have permission to ${action} this file.`, meta.permit.reason);
  }
}

export const mockApi = {
  auth: {
    async requestMagicLink(email) {
      await latency();
      const token = rid("t").slice(2);
      db.tokens[token] = { email, exp: Date.now() + 15 * 60e3, used: false };
      save();
      console.info(`magic link: /unlock.html?token=${token}`);
      return { ok: true, devToken: token };
    },

    async verify(token) {
      await latency();
      const rec = db.tokens[token];
      if (!rec) throw new ApiError(400, "invalid_token", "That sign-in link isn't valid.");
      if (rec.used) throw new ApiError(410, "gone", "That sign-in link has already been used.");
      if (Date.now() > rec.exp) throw new ApiError(410, "gone", "That sign-in link has expired.");
      rec.used = true;
      db.user = rec.email === PARTNER.email ? PARTNER : { ...ME, email: rec.email };
      save();
      return { user: db.user };
    },

    async session() {
      await wait(60);
      return { user: requireSession() };
    },

    async logout() {
      db.user = null;
      save();
    },
  },

  files: {
    async list() {
      await latency();
      requireSession();
      return db.files.map((f) => project(f)).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    },

    async get(id) {
      await latency();
      requireSession();
      return project(find(id));
    },

    async permit(id) {
      await wait(80);
      requireSession();
      return project(find(id)).permit;
    },

    contentUrl(id, tier) {
      const f = db.files.find((x) => x.id === id);
      if (!f || !db.user) return null;
      const meta = project(f);
      const served = servedFor(meta, tier);
      return served === "metadata" ? null : placeholder(meta, served);
    },

    thumbUrl(id) {
      const f = db.files.find((x) => x.id === id);
      if (!f || !db.user) return null;
      const meta = project(f);
      return meta.permit.qualityTier === "metadata" ? null : placeholder(meta, "lowest");
    },

    async initiate({ name, mime, size }) {
      await latency();
      requireSession();
      if (size > MAX_UPLOAD) throw new ApiError(413, "too_large", "Files can be up to 2 GB.");
      const uploadId = rid("up");
      db.uploads[uploadId] = { name, mime, size, received: [] };
      save();
      return { uploadId, chunkSize: CHUNK_SIZE, received: [] };
    },

    async status(uploadId) {
      await wait(60);
      requireSession();
      const up = db.uploads[uploadId];
      if (!up) throw new ApiError(404, "not_found", "That upload no longer exists.");
      return { uploadId, chunkSize: CHUNK_SIZE, received: [...up.received].sort((a, b) => a - b) };
    },

    async putChunk(uploadId, index, bytes, onProgress) {
      const up = db.uploads[uploadId];
      if (!up) throw new ApiError(404, "not_found", "This upload expired. Start again.");
      const steps = 4;
      for (let i = 1; i <= steps; i++) {
        await wait(40 + Math.random() * 60);
        onProgress?.(Math.round(((bytes?.byteLength ?? 0) * i) / steps));
      }
      if (up.size <= KEEP_BYTES_UP_TO && bytes) {
        if (!pendingBytes.has(uploadId)) pendingBytes.set(uploadId, []);
        pendingBytes.get(uploadId)[index] = bytes.slice(0);
      }
      if (!up.received.includes(index)) up.received.push(index);
      save();
      return { received: [...up.received] };
    },

    async complete(uploadId, sha256) {
      const up = db.uploads[uploadId];
      if (!up) throw new ApiError(404, "not_found", "This upload expired. Start again.");
      if (!/^[0-9a-f]{64}$/.test(sha256 ?? "")) throw new ApiError(400, "invalid_upload", "Send the file's SHA-256.");
      await latency();
      const user = requireSession();
      const f = { id: rid("f"), name: up.name, mime: up.mime, size: up.size, ownerId: user.id, ownerEmail: user.email, createdAt: nowIso(), updatedAt: nowIso(), level: "owner", policy: {} };
      const parts = pendingBytes.get(uploadId);
      if (parts && typeof URL !== "undefined" && URL.createObjectURL) {
        blobs.set(f.id, URL.createObjectURL(new Blob(parts, { type: up.mime })));
      }
      pendingBytes.delete(uploadId);
      db.files.unshift(f);
      delete db.uploads[uploadId];
      save();
      const meta = project(f);
      log("upload", meta, "original");
      return meta;
    },

    async abort(uploadId) {
      pendingBytes.delete(uploadId);
      delete db.uploads[uploadId];
      save();
    },

    async remove(id) {
      await latency();
      requireSession();
      const meta = project(find(id));
      demand(meta, "delete");
      db.files = db.files.filter((x) => x.id !== id);
      db.shares = db.shares.filter((s) => s.fileId !== id);
      save();
      log("delete", meta, "original");
    },

    async rename(id, name) {
      await latency();
      requireSession();
      const meta = project(find(id));
      demand(meta, "edit");
      const f = find(id);
      f.name = name;
      f.updatedAt = nowIso();
      save();
      log("edit", meta, meta.permit.qualityTier);
      return project(f);
    },
  },

  shares: {
    async list(fileId) {
      await latency();
      requireSession();
      demand(project(find(fileId)), "share");
      return db.shares.filter((s) => s.fileId === fileId).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    },

    async create(fileId, { accessLevel, actions, expiresAt, maxViews }) {
      await latency();
      const user = requireSession();
      const meta = project(find(fileId));
      demand(meta, "share");
      const share = {
        id: rid("s"),
        fileId,
        token: rid("t").slice(2) + rid("t").slice(2),
        accessLevel,
        actions,
        expiresAt: expiresAt ?? null,
        maxViews: maxViews ?? null,
        viewsUsed: 0,
        revokedAt: null,
        createdAt: nowIso(),
        createdBy: user.id,
      };
      db.shares.unshift(share);
      save();
      log("share", meta, meta.permit.qualityTier, share.id);
      return share;
    },

    async revoke(id) {
      await latency();
      requireSession();
      const s = db.shares.find((x) => x.id === id);
      if (!s) throw new ApiError(404, "not_found", "That link no longer exists.");
      const meta = project(find(s.fileId));
      demand(meta, "revoke");
      s.revokedAt = nowIso();
      save();
      log("revoke", meta, meta.permit.qualityTier, s.id);
      return s;
    },

    async publicGet(token) {
      await latency();
      const s = db.shares.find((x) => x.token === token);
      if (!s) throw new ApiError(404, "not_found", "This link doesn't exist.");
      const f = find(s.fileId);
      const permit = evaluate({
        file: { id: f.id, kind: kindOf(f.mime) },
        subjectId: `share:${token}`,
        level: s.accessLevel,
        policy: { ...f.policy, download: s.actions.includes("download") },
        share: s,
      });
      if (permit.actions.length === 0) {
        if (!permit.reason || permit.reason.startsWith("policy:")) {
          throw new ApiError(404, "not_found", "This link doesn't exist.");
        }
        const message = {
          revoked: "This link was revoked by its owner.",
          expired: "This link has expired.",
          view_limit: "This link has reached its view limit.",
        }[permit.reason];
        throw new ApiError(410, "gone", message, permit.reason);
      }
      s.viewsUsed += 1;
      save();
      const meta = { ...project(f, { id: `share:${token}` }), permit };
      log("view", meta, permit.qualityTier, s.id);
      return { file: meta, permit, share: s };
    },

    publicContentUrl(token, tier) {
      const s = db.shares.find((x) => x.token === token);
      if (!s) return null;
      const f = db.files.find((x) => x.id === s.fileId);
      if (!f) return null;
      const meta = project(f, { id: `share:${token}` });
      const permit = evaluate({ file: { id: f.id, kind: meta.kind }, subjectId: `share:${token}`, level: s.accessLevel, policy: { ...f.policy, download: s.actions.includes("download") }, share: s });
      const served = servedFor({ ...meta, permit }, tier);
      return served === "metadata" ? null : placeholder(meta, served);
    },
  },

  people: {
    async list(fileId) {
      await latency();
      requireSession();
      demand(project(find(fileId)), "share");
      return (db.people ?? []).filter((p) => p.fileId === fileId).map(({ fileId: _, ...p }) => p);
    },

    async grant(fileId, { email, accessLevel, download }) {
      await latency();
      requireSession();
      const meta = project(find(fileId));
      demand(meta, "share");
      const address = (email ?? "").trim().toLowerCase();
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(address)) throw new ApiError(400, "invalid_person", "Enter the email address of the person.");
      if (address === meta.ownerEmail) throw new ApiError(400, "invalid_person", "The owner already has full access.");
      if (!["editor", "viewer", "guest"].includes(accessLevel)) throw new ApiError(400, "invalid_person", "Pick editor, viewer or guest.");
      const actions = accessLevel === "editor" ? ["download", "edit", "view"] : accessLevel === "viewer" && download ? ["download", "view"] : ["view"];
      db.people = db.people ?? [];
      let person = db.people.find((p) => p.fileId === fileId && p.email === address);
      if (!person) {
        person = { id: rid("p"), fileId, email: address };
        db.people.push(person);
      }
      Object.assign(person, { accessLevel, actions });
      save();
      log("share", meta, meta.permit.qualityTier);
      const { fileId: _, ...view } = person;
      return view;
    },

    async remove(fileId, permissionId) {
      await latency();
      requireSession();
      const meta = project(find(fileId));
      demand(meta, "revoke");
      db.people = (db.people ?? []).filter((p) => p.id !== permissionId);
      save();
      log("revoke", meta, meta.permit.qualityTier);
    },
  },

  audit: {
    async list({ fileId, action, result, page = 1, pageSize = 25 } = {}) {
      await latency();
      requireSession();
      let rows = db.audit;
      if (fileId) rows = rows.filter((e) => e.fileId === fileId);
      if (action) rows = rows.filter((e) => e.action === action);
      if (result) rows = rows.filter((e) => e.result === result);
      const start = (page - 1) * pageSize;
      return { events: rows.slice(start, start + pageSize), total: rows.length, page, pageSize };
    },
  },
};
