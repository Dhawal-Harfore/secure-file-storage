const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

export class Sha256 {
  constructor() {
    this.state = new Uint32Array([
      0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ]);
    this.pending = new Uint8Array(64);
    this.pendingLength = 0;
    this.length = 0;
    this.words = new Uint32Array(64);
  }

  update(data) {
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    this.length += bytes.length;
    let offset = 0;

    if (this.pendingLength > 0) {
      const take = Math.min(64 - this.pendingLength, bytes.length);
      this.pending.set(bytes.subarray(0, take), this.pendingLength);
      this.pendingLength += take;
      offset = take;
      if (this.pendingLength < 64) return this;
      this.compress(this.pending, 0);
      this.pendingLength = 0;
    }

    for (; offset + 64 <= bytes.length; offset += 64) {
      this.compress(bytes, offset);
    }

    if (offset < bytes.length) {
      this.pending.set(bytes.subarray(offset), 0);
      this.pendingLength = bytes.length - offset;
    }
    return this;
  }

  digest() {
    const bits = this.length * 8;
    const tail = new Uint8Array(this.pendingLength < 56 ? 64 : 128);
    tail.set(this.pending.subarray(0, this.pendingLength));
    tail[this.pendingLength] = 0x80;
    const view = new DataView(tail.buffer);
    view.setUint32(tail.length - 8, Math.floor(bits / 0x100000000));
    view.setUint32(tail.length - 4, bits >>> 0);
    for (let offset = 0; offset < tail.length; offset += 64) {
      this.compress(tail, offset);
    }

    let hex = "";
    for (const word of this.state) {
      hex += word.toString(16).padStart(8, "0");
    }
    return hex;
  }

  compress(bytes, offset) {
    const w = this.words;
    for (let i = 0; i < 16; i++) {
      const j = offset + i * 4;
      w[i] = (bytes[j] << 24) | (bytes[j + 1] << 16) | (bytes[j + 2] << 8) | bytes[j + 3];
    }
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15];
      const b = w[i - 2];
      const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
      const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }

    const s = this.state;
    let a = s[0], b = s[1], c = s[2], d = s[3], e = s[4], f = s[5], g = s[6], h = s[7];

    for (let i = 0; i < 64; i++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + K[i] + w[i]) | 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) | 0;
      h = g;
      g = f;
      f = e;
      e = (d + t1) | 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) | 0;
    }

    s[0] = (s[0] + a) | 0;
    s[1] = (s[1] + b) | 0;
    s[2] = (s[2] + c) | 0;
    s[3] = (s[3] + d) | 0;
    s[4] = (s[4] + e) | 0;
    s[5] = (s[5] + f) | 0;
    s[6] = (s[6] + g) | 0;
    s[7] = (s[7] + h) | 0;
  }
}

export function inlineHasher() {
  const hash = new Sha256();
  return {
    update(buffer) {
      hash.update(buffer);
    },
    async digest() {
      return hash.digest();
    },
    dispose() {},
  };
}

export function workerHasher() {
  const worker = new Worker(new URL("./hash-worker.js", import.meta.url), { type: "module" });
  return {
    update(buffer) {
      worker.postMessage({ type: "update", buffer }, [buffer]);
    },
    digest() {
      return new Promise((resolve, reject) => {
        worker.onmessage = ({ data }) => resolve(data.hex);
        worker.onerror = (event) => reject(new Error(event.message || "Hashing failed."));
        worker.postMessage({ type: "digest" });
      });
    },
    dispose() {
      worker.terminate();
    },
  };
}
