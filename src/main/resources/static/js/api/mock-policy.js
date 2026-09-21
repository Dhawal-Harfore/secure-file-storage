export const LEVEL_ACTIONS = {
  owner: ["view", "edit", "download", "share", "revoke", "delete"],
  editor: ["view", "edit", "download"],
  viewer: ["view", "download"],
  guest: ["view"],
};

export const LEVEL_TIER = {
  owner: "original",
  editor: "original",
  viewer: "reduced",
  guest: "lowest",
};

const POLICY_GATED = ["view", "download", "edit", "share"];
const LEVELS_WHERE_DOWNLOAD_IS_OPT_IN = new Set(["viewer"]);
const KINDS_WITH_RENDITIONS = new Set(["image", "video", "pdf"]);
const TIER_RANK = { metadata: 0, lowest: 1, reduced: 2, original: 3 };

const lowerTier = (a, b) => (TIER_RANK[a] <= TIER_RANK[b] ? a : b);

export function kindOf(mime) {
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime === "application/pdf") return "pdf";
  return "other";
}

export function resolveTier(kind, level, actions, cap) {
  if (!actions.includes("view")) return "metadata";

  const tier = cap ? lowerTier(LEVEL_TIER[level], cap) : LEVEL_TIER[level];
  if (KINDS_WITH_RENDITIONS.has(kind)) return tier;

  if (level === "guest") return "metadata";
  return actions.includes("download") ? lowerTier("original", cap ?? "original") : "metadata";
}

function applyPolicy(level, policy) {
  let actions = [...LEVEL_ACTIONS[level]];

  if (LEVELS_WHERE_DOWNLOAD_IS_OPT_IN.has(level) && policy.download !== true) {
    actions = actions.filter((a) => a !== "download");
  }
  for (const gate of POLICY_GATED) {
    if (policy[gate] === false) actions = actions.filter((a) => a !== gate);
  }
  if (!actions.includes("view")) {
    actions = actions.filter((a) => a !== "download" && a !== "edit");
  }
  return actions;
}

export function evaluate({ file, subjectId, level, policy = {}, share, now = new Date() }) {
  const actions = applyPolicy(level, policy);
  const expiresAt = share?.expiresAt ?? policy.expiresAt ?? null;
  const maxViews = share?.maxViews ?? policy.maxViews ?? null;
  const viewsUsed = share?.viewsUsed ?? 0;
  const base = { fileId: file.id, subjectId, accessLevel: level, expiresAt, maxViews, viewsUsed };

  const dead =
    (share?.revokedAt && "revoked") ||
    (expiresAt && new Date(expiresAt) <= now && "expired") ||
    (maxViews != null && viewsUsed >= maxViews && "view_limit");

  if (dead) {
    return { ...base, actions: [], qualityTier: "metadata", keyHandle: null, reason: dead };
  }

  const qualityTier = resolveTier(file.kind, level, actions, policy.qualityTier);
  return {
    ...base,
    actions,
    qualityTier,
    keyHandle: qualityTier === "metadata" ? null : `kh_${file.id.slice(2, 10)}_${qualityTier}`,
    reason: `policy:${level}`,
  };
}

export const can = (permit, action) => !!permit?.actions.includes(action);
