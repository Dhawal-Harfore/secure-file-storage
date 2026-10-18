import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { UploadQueue } from "../js/upload/uploader.js";
import { inlineHasher } from "../js/lib/sha256.js";

let checks = 0;
const check = async (name, fn) => {
  await fn();
  checks += 1;
};

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function fakeApi({ chunkSize = 4, failures = {} } = {}) {
  const calls = [];
  const uploads = new Map();
  let ids = 0;
  const api = {
    calls,
    uploads,
    files: {
      async initiate({ size }) {
        calls.push("initiate");
        const uploadId = `up_${++ids}`;
        uploads.set(uploadId, { size, chunks: new Map() });
        return { uploadId, chunkSize, received: [] };
      },
      async status(uploadId) {
        calls.push(`status:${uploadId}`);
        const u = uploads.get(uploadId);
        if (!u) throw new HttpError(404, "not_found", "That upload no longer exists.");
        return { uploadId, chunkSize, received: [...u.chunks.keys()].sort((a, b) => a - b) };
      },
      async putChunk(uploadId, index, bytes, onProgress) {
        calls.push(`put:${index}`);
        const queue = failures[index];
        if (queue?.length) throw queue.shift();
        onProgress?.(Math.floor(bytes.byteLength / 2));
        uploads.get(uploadId).chunks.set(index, new Uint8Array(bytes.slice(0)));
        onProgress?.(bytes.byteLength);
        return { received: [...uploads.get(uploadId).chunks.keys()] };
      },
      async complete(uploadId, sha256) {
        calls.push("complete");
        const u = uploads.get(uploadId);
        const all = Buffer.concat([...u.chunks.entries()].sort((a, b) => a[0] - b[0]).map(([, c]) => c));
        if (createHash("sha256").update(all).digest("hex") !== sha256) {
          uploads.delete(uploadId);
          throw new HttpError(400, "checksum_mismatch", "The uploaded bytes don't match.");
        }
        uploads.delete(uploadId);
        return { id: "f_0000000000000001", size: all.length };
      },
      async abort(uploadId) {
        calls.push(`abort:${uploadId}`);
        uploads.delete(uploadId);
      },
    },
  };
  return api;
}

function store() {
  const map = new Map();
  return { map, get: (k) => map.get(k) ?? null, set: (k, v) => map.set(k, v), remove: (k) => map.delete(k) };
}

const file = (text, name = "a.bin") => new File([text], name, { type: "application/octet-stream", lastModified: 7 });

async function settle(queue) {
  while (queue.running || queue.items.some((i) => ["queued", "uploading", "finishing"].includes(i.state))) {
    await new Promise((r) => setTimeout(r, 1));
  }
}

await check("uploads every chunk and completes with the right checksum", async () => {
  const api = fakeApi();
  const s = store();
  const queue = new UploadQueue({ api, createHasher: inlineHasher, store: s });
  const sent = [];
  queue.addEventListener("change", (e) => e.detail && sent.push(e.detail.sent));
  const [item] = queue.add([file("hello world")]);
  await settle(queue);
  assert.equal(item.state, "done", item.error);
  assert.deepEqual(api.calls, ["initiate", "put:0", "put:1", "put:2", "complete"]);
  assert.equal(item.result.size, 11);
  assert.equal(s.map.size, 0);
  assert.deepEqual(sent, [...sent].sort((a, b) => a - b));
  assert.equal(sent.at(-1), 11);
});

await check("resumes a saved upload and only sends the missing chunks", async () => {
  const api = fakeApi();
  const s = store();
  const data = "0123456789AB";
  const { uploadId } = await api.files.initiate({ size: data.length });
  await api.files.putChunk(uploadId, 0, new TextEncoder().encode("0123").buffer);
  await api.files.putChunk(uploadId, 2, new TextEncoder().encode("89AB").buffer);
  api.calls.length = 0;
  const f = file(data);
  s.set(`vault.upload:${f.name}|${f.size}|7`, { uploadId });

  const queue = new UploadQueue({ api, createHasher: inlineHasher, store: s });
  const [item] = queue.add([f]);
  await settle(queue);
  assert.equal(item.state, "done", item.error);
  assert.deepEqual(api.calls, [`status:${uploadId}`, "put:1", "complete"]);
});

await check("retries transient failures with back-off", async () => {
  const api = fakeApi({ failures: { 1: [new HttpError(503, "unavailable", "busy"), Object.assign(new Error("network"), { status: 0 })] } });
  const delays = [];
  const queue = new UploadQueue({ api, createHasher: inlineHasher, store: store(), sleep: async (ms) => delays.push(ms) });
  const [item] = queue.add([file("abcdefghij")]);
  await settle(queue);
  assert.equal(item.state, "done", item.error);
  assert.deepEqual(delays, [1000, 3000]);
  assert.deepEqual(api.calls.filter((c) => c === "put:1"), ["put:1", "put:1", "put:1"]);
});

await check("does not retry a client error and keeps the upload resumable", async () => {
  const api = fakeApi({ failures: { 0: [new HttpError(400, "invalid_chunk", "Chunk 0 is wrong.")] } });
  const s = store();
  const queue = new UploadQueue({ api, createHasher: inlineHasher, store: s, sleep: async () => {} });
  const [item] = queue.add([file("abcdefgh")]);
  await settle(queue);
  assert.equal(item.state, "failed");
  assert.equal(item.error, "Chunk 0 is wrong.");
  assert.deepEqual(api.calls, ["initiate", "put:0"]);
  assert.equal(s.map.size, 1);

  queue.retry(item);
  await settle(queue);
  assert.equal(item.state, "done", item.error);
  assert.equal(api.calls.filter((c) => c.startsWith("status")).length, 1);
  assert.equal(s.map.size, 0);
});

await check("starts over when the server forgot the upload", async () => {
  const api = fakeApi();
  const s = store();
  const f = file("abcdef");
  s.set(`vault.upload:${f.name}|${f.size}|7`, { uploadId: "up_gone" });
  const queue = new UploadQueue({ api, createHasher: inlineHasher, store: s });
  const [item] = queue.add([f]);
  await settle(queue);
  assert.equal(item.state, "done", item.error);
  assert.deepEqual(api.calls.slice(0, 2), ["status:up_gone", "initiate"]);
});

await check("cancelling stops after the current chunk and aborts on the server", async () => {
  const api = fakeApi();
  const s = store();
  const queue = new UploadQueue({ api, createHasher: inlineHasher, store: s });
  let item;
  queue.addEventListener("change", (e) => {
    if (e.detail === item && e.detail.sent === 4) queue.cancel(item);
  });
  [item] = queue.add([file("abcdefghijkl")]);
  await settle(queue);
  assert.equal(item.state, "cancelled");
  assert.ok(api.calls.includes("abort:up_1"));
  assert.ok(!api.calls.includes("complete"));
  assert.equal(s.map.size, 0);
  assert.equal(api.uploads.size, 0);
});

await check("empty and oversized files fail without calling the server", async () => {
  const api = fakeApi();
  const queue = new UploadQueue({ api, createHasher: inlineHasher, store: store(), maxBytes: 5 });
  const [empty, big] = queue.add([file(""), file("abcdefgh")]);
  await settle(queue);
  assert.equal(empty.state, "failed");
  assert.equal(big.state, "failed");
  assert.match(big.error, /limit/);
  assert.equal(api.calls.length, 0);
});

await check("uploads queue up and run one at a time", async () => {
  const api = fakeApi();
  const queue = new UploadQueue({ api, createHasher: inlineHasher, store: store() });
  const items = queue.add([file("first file", "1.bin"), file("second", "2.bin")]);
  await settle(queue);
  assert.deepEqual(items.map((i) => i.state), ["done", "done"]);
  const firstComplete = api.calls.indexOf("complete");
  const secondInitiate = api.calls.lastIndexOf("initiate");
  assert.ok(firstComplete < secondInitiate);
});

console.log(`${checks} checks passed`);
