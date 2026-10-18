import { Sha256 } from "./sha256.js";

let hash = new Sha256();

self.onmessage = ({ data }) => {
  if (data.type === "update") {
    hash.update(new Uint8Array(data.buffer));
  } else if (data.type === "digest") {
    self.postMessage({ hex: hash.digest() });
    hash = new Sha256();
  }
};
