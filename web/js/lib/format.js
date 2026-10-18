const TIER_ORDER = ["metadata", "lowest", "reduced", "original"];

export function readableSize(bytes) {
  const units = ["B", "KB", "MB", "GB"];
  let n = bytes;
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i += 1;
  }
  return `${n < 10 && i > 0 ? n.toFixed(1) : Math.round(n)} ${units[i]}`;
}

const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

export function ago(iso, now = Date.now()) {
  const seconds = (new Date(iso).getTime() - now) / 1000;
  const steps = [
    [60, "second"],
    [60, "minute"],
    [24, "hour"],
    [7, "day"],
    [4.35, "week"],
    [12, "month"],
    [Infinity, "year"],
  ];
  let value = seconds;
  for (const [size, unit] of steps) {
    if (Math.abs(value) < size) return relative.format(Math.round(value), unit);
    value /= size;
  }
  return "";
}

export function servedTier(file) {
  const ceiling = file.permit?.qualityTier ?? "metadata";
  const have = new Set(file.renditions ?? []);
  for (let i = TIER_ORDER.indexOf(ceiling); i > 0; i -= 1) {
    if (have.has(TIER_ORDER[i])) return TIER_ORDER[i];
  }
  return "metadata";
}

export function shareState(share, now = Date.now()) {
  if (share.revokedAt) return "revoked";
  if (share.expiresAt && new Date(share.expiresAt).getTime() <= now) return "expired";
  if (share.maxViews != null && share.viewsUsed >= share.maxViews) return "used up";
  return "live";
}
