const RETRY_DELAYS = [1000, 3000];
const KEY_PREFIX = "vault.upload:";

let nextId = 1;

function memoryStore() {
  const map = new Map();
  return {
    get: (k) => (map.has(k) ? JSON.parse(map.get(k)) : null),
    set: (k, v) => map.set(k, JSON.stringify(v)),
    remove: (k) => map.delete(k),
  };
}

export function browserStore() {
  try {
    const probe = "vault.upload.probe";
    localStorage.setItem(probe, "1");
    localStorage.removeItem(probe);
    return {
      get: (k) => {
        try {
          return JSON.parse(localStorage.getItem(k));
        } catch {
          return null;
        }
      },
      set: (k, v) => {
        try {
          localStorage.setItem(k, JSON.stringify(v));
        } catch {}
      },
      remove: (k) => {
        try {
          localStorage.removeItem(k);
        } catch {}
      },
    };
  } catch {
    return memoryStore();
  }
}

class Cancelled extends Error {}

export class UploadQueue extends EventTarget {
  constructor({ api, createHasher, store = memoryStore(), maxBytes = Infinity, sleep }) {
    super();
    this.api = api;
    this.createHasher = createHasher;
    this.store = store;
    this.maxBytes = maxBytes;
    this.sleep = sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    this.items = [];
    this.running = false;
  }

  add(files) {
    const added = [];
    for (const file of files) {
      const item = {
        id: nextId++,
        file,
        name: file.name,
        size: file.size,
        state: "queued",
        sent: 0,
        error: "",
        result: null,
        cancelled: false,
      };
      if (file.size === 0) {
        item.state = "failed";
        item.error = "This file is empty.";
      } else if (file.size > this.maxBytes) {
        item.state = "failed";
        item.error = "This file is over the upload limit.";
      }
      this.items.push(item);
      added.push(item);
      this.emit(item);
    }
    this.pump();
    return added;
  }

  retry(item) {
    if (item.state !== "failed") return;
    item.state = "queued";
    item.error = "";
    item.cancelled = false;
    this.emit(item);
    this.pump();
  }

  cancel(item) {
    if (item.state === "done" || item.state === "cancelled") return;
    item.cancelled = true;
    if (item.state === "queued" || item.state === "failed") {
      item.state = "cancelled";
      this.forget(item, true);
      this.emit(item);
    }
  }

  clearFinished() {
    this.items = this.items.filter((i) => !["done", "cancelled"].includes(i.state));
    this.dispatchEvent(new Event("change"));
  }

  emit(item) {
    this.dispatchEvent(new CustomEvent("change", { detail: item }));
  }

  key(item) {
    return `${KEY_PREFIX}${item.name}|${item.size}|${item.file.lastModified ?? 0}`;
  }

  async forget(item, abortOnServer) {
    const saved = this.store.get(this.key(item));
    this.store.remove(this.key(item));
    if (abortOnServer && saved?.uploadId) {
      try {
        await this.api.files.abort(saved.uploadId);
      } catch {}
    }
  }

  async pump() {
    if (this.running) return;
    this.running = true;
    try {
      let item;
      while ((item = this.items.find((i) => i.state === "queued"))) {
        await this.upload(item);
      }
    } finally {
      this.running = false;
    }
  }

  async withRetry(item, run) {
    for (let attempt = 0; ; attempt += 1) {
      try {
        return await run();
      } catch (err) {
        const status = err?.status ?? 0;
        const transient = status === 0 || status >= 500;
        if (!transient || attempt >= RETRY_DELAYS.length || item.cancelled) throw err;
        await this.sleep(RETRY_DELAYS[attempt]);
      }
    }
  }

  async upload(item) {
    const { file } = item;
    const key = this.key(item);
    let hasher = null;

    try {
      item.state = "uploading";
      item.sent = 0;
      this.emit(item);

      let status = null;
      const saved = this.store.get(key);
      if (saved?.uploadId) {
        try {
          status = await this.api.files.status(saved.uploadId);
        } catch (err) {
          if (err?.status !== 404) throw err;
          this.store.remove(key);
        }
      }
      if (!status) {
        status = await this.withRetry(item, () =>
          this.api.files.initiate({ name: file.name, mime: file.type || "application/octet-stream", size: file.size }),
        );
        this.store.set(key, { uploadId: status.uploadId });
      }

      const { uploadId, chunkSize } = status;
      const received = new Set(status.received ?? []);
      const count = Math.ceil(file.size / chunkSize);
      hasher = this.createHasher();

      for (let index = 0; index < count; index += 1) {
        if (item.cancelled) throw new Cancelled();
        const start = index * chunkSize;
        const end = Math.min(file.size, start + chunkSize);
        const bytes = await file.slice(start, end).arrayBuffer();

        if (!received.has(index)) {
          await this.withRetry(item, () =>
            this.api.files.putChunk(uploadId, index, bytes, (loaded) => {
              item.sent = start + Math.min(loaded, end - start);
              this.emit(item);
            }),
          );
        }

        item.sent = end;
        this.emit(item);
        hasher.update(bytes);
      }

      if (item.cancelled) throw new Cancelled();
      item.state = "finishing";
      this.emit(item);

      const sha256 = await hasher.digest();
      item.result = await this.withRetry(item, () => this.api.files.complete(uploadId, sha256));
      this.store.remove(key);
      item.state = "done";
      this.emit(item);
    } catch (err) {
      if (err instanceof Cancelled || item.cancelled) {
        item.state = "cancelled";
        await this.forget(item, true);
      } else {
        item.state = "failed";
        item.error = err?.message || "The upload failed.";
        if (err?.status === 404 || err?.code === "checksum_mismatch") this.store.remove(key);
      }
      this.emit(item);
    } finally {
      hasher?.dispose();
    }
  }
}
