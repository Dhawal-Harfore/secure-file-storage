import { evaluate, can } from "../js/api/mock-policy.js";

const ev = (kind, level, policy = {}, share) =>
  evaluate({ file: { id: "f_9c1e0001", kind }, subjectId: "u_1", level, policy, share });

const past = new Date(Date.now() - 864e5).toISOString();
const failed = [];
let total = 0;

function check(name, ok) {
  total += 1;
  if (!ok) failed.push(name);
}

check("owner gets original on media", ev("pdf", "owner").qualityTier === "original");
check("editor gets original on media", ev("pdf", "editor").qualityTier === "original");
check("viewer gets reduced on media", ev("pdf", "viewer").qualityTier === "reduced");
check("guest gets lowest on media", ev("pdf", "guest").qualityTier === "lowest");
check("guest is preview only", ev("pdf", "guest").actions.join() === "view");
check("owner can revoke and delete", can(ev("pdf", "owner"), "revoke") && can(ev("pdf", "owner"), "delete"));
check("editor cannot share", !can(ev("pdf", "editor"), "share"));

check("viewer download is off by default", !can(ev("pdf", "viewer"), "download"));
check("viewer download can be granted", can(ev("pdf", "viewer", { download: true }), "download"));

check("non-media allows when download granted", ev("other", "viewer", { download: true }).qualityTier === "original");
check("non-media denies when download withheld", ev("other", "owner", { download: false }).qualityTier === "metadata");
check("guest never gets bytes of non-media", ev("other", "guest").qualityTier === "metadata");
check("metadata tier carries no key", ev("other", "guest").keyHandle === null);

check("policy can lower a tier", ev("pdf", "owner", { qualityTier: "lowest" }).qualityTier === "lowest");
check("policy cannot raise a tier", ev("pdf", "guest", { qualityTier: "original" }).qualityTier === "lowest");

check("expired grants nothing", ev("pdf", "owner", {}, { expiresAt: past }).actions.length === 0);
check("revoked grants nothing", ev("pdf", "owner", {}, { revokedAt: past }).actions.length === 0);
check("view limit grants nothing", ev("pdf", "owner", {}, { maxViews: 5, viewsUsed: 5 }).actions.length === 0);
check("expiry reports why", ev("pdf", "owner", {}, { expiresAt: past }).reason === "expired");

if (failed.length) {
  for (const name of failed) console.error(`FAIL  ${name}`);
  process.exit(1);
}
console.log(`${total} checks passed`);
