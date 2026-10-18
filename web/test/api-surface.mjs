import assert from "node:assert/strict";

globalThis.sessionStorage = { getItem: () => null, setItem: () => {} };

const { mockApi } = await import("../js/api/mock.js");
const { liveApi } = await import("../js/api/client.js");

const shape = (api) => Object.fromEntries(Object.entries(api).map(([group, fns]) => [group, Object.keys(fns).sort()]));

assert.deepEqual(shape(mockApi), shape(liveApi));
assert.equal(liveApi.files.contentUrl("f_0123456789abcdef", "reduced"), "/api/files/f_0123456789abcdef/content?tier=reduced");
assert.equal(liveApi.files.thumbUrl("f_0123456789abcdef"), "/api/files/f_0123456789abcdef/thumb");
assert.equal(liveApi.shares.publicContentUrl("ab/c", "lowest"), "/api/s/ab%2Fc/content?tier=lowest");

console.log("4 checks passed");
