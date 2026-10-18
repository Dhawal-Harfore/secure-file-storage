import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { Sha256 } from "../js/lib/sha256.js";

let checks = 0;
const expect = (data, splits = []) => {
  const ours = new Sha256();
  let last = 0;
  for (const at of [...splits, data.length]) {
    ours.update(data.subarray(last, at));
    last = at;
  }
  assert.equal(ours.digest(), createHash("sha256").update(data).digest("hex"));
  checks += 1;
};

expect(new Uint8Array(0));
expect(new TextEncoder().encode("abc"));
expect(new TextEncoder().encode("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"));

for (const size of [1, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 1000, 4096, 100003]) {
  expect(new Uint8Array(randomBytes(size)));
}

const big = new Uint8Array(randomBytes(3 * 1024 * 1024 + 17));
expect(big, [1, 63, 64, 65, 1048576, 2097151, 3145728]);

for (let i = 0; i < 50; i++) {
  const data = new Uint8Array(randomBytes(1 + Math.floor(Math.random() * 5000)));
  const splits = [...new Set(Array.from({ length: 4 }, () => Math.floor(Math.random() * data.length)))].sort((a, b) => a - b);
  expect(data, splits);
}

console.log(`${checks} checks passed`);
