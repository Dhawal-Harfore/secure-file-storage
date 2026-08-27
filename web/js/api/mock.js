import { ApiError } from "./errors.js";
import { evaluate, kindOf } from "./mock-policy.js";

const STORE_KEY = "vault.mock.v1";
const CHUNK_SIZE = 8 * 1024 * 1024;
const TIER_ORDER = ["metadata", "lowest", "reduced", "original"];

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

    async content(id, tier) {
      await latency();
      requireSession();
      const meta = project(find(id));
      demand(meta, "view");
      const asked = TIER_ORDER.indexOf(tier ?? meta.permit.qualityTier);
      const served = TIER_ORDER[Math.min(asked, TIER_ORDER.indexOf(meta.permit.qualityTier))];
      log("view", meta, served);
      if (served === "metadata") {
        throw new ApiError(403, "denied", "Only metadata is available to you for this file.", meta.permit.reason);
      }
      return { tier: served, keyHandle: meta.permit.keyHandle, url: null };
    },

    async initiate({ name, mime, size }) {
      await latency();
      requireSession();
      const uploadId = rid("up");
      db.uploads[uploadId] = { name, mime, size, received: [] };
      save();
      return { uploadId, chunkSize: CHUNK_SIZE, received: [] };
    },

    async putChunk(uploadId, index) {
      const up = db.uploads[uploadId];
      if (!up) throw new ApiError(404, "not_found", "This upload expired. Start again.");
      await wait(90 + Math.random() * 120);
      if (!up.received.includes(index)) up.received.push(index);
      save();
      return { received: [...up.received] };
    },

    async complete(uploadId) {
      const up = db.uploads[uploadId];
      if (!up) throw new ApiError(404, "not_found", "This upload expired. Start again.");
      await latency();
      const user = requireSession();
      const f = { id: rid("f"), name: up.name, mime: up.mime, size: up.size, ownerId: user.id, ownerEmail: user.email, createdAt: nowIso(), updatedAt: nowIso(), level: "owner", policy: {} };
      db.files.unshift(f);
      delete db.uploads[uploadId];
      save();
      const meta = project(f);
      log("edit", meta, "original");
      return meta;
    },

    async abort(uploadId) {
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
        policy: f.policy,
        share: s,
      });
      if (permit.actions.length === 0) {
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
