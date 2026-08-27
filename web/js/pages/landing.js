const MOTE_COUNT = 28;
const CENTRE_X = 32;
const CENTRE_Y = 17;
const SPREAD_X = 27;
const SPREAD_Y = 19;

const FILES = [
  { name: "Q3-architecture-review.pdf", time: "9:24", meta: "Owner · Original", fresh: true },
  { name: "vault-demo-walkthrough.mp4", time: "9:02", meta: "Owner · Original", fresh: true },
  { name: "policy-engine-whiteboard.png", time: "8:41", meta: "Editor · Original", fresh: false },
  { name: "threat-model.docx", time: "8:30", meta: "Viewer · Download allowed", fresh: false },
  { name: "encrypted-backups.zip", time: "Tue", meta: "Viewer · Metadata only", fresh: false },
];

const LOCK = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none"><rect x="5" y="10.5" width="14" height="9.5" rx="2" stroke="currentColor" stroke-width="1.8"/><path d="M8.5 10.5V8a3.5 3.5 0 1 1 7 0v2.5" stroke="currentColor" stroke-width="1.8"/></svg>';

const COMMANDS = [
  { key: "share", label: "Share with expiry", keys: ["⌘", "S"], icon: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none"><path d="M9 13.5 15 10m-6 .5L15 7M7 12a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0m15-6a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0m0 12a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>' },
  { key: "link", label: "Copy share link", keys: ["⌘", "L"], icon: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none"><path d="M10 13.5a3.5 3.5 0 0 0 5 0l3-3a3.54 3.54 0 0 0-5-5l-1.5 1.5M14 10.5a3.5 3.5 0 0 0-5 0l-3 3a3.54 3.54 0 0 0 5 5l1.5-1.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>' },
  { key: "download", label: "Download original", keys: ["⌘", "D"], icon: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none"><path d="M12 4v11m0 0 4-4m-4 4-4-4M5 19h14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>' },
  { key: "revoke", label: "Revoke every link", keys: ["⌘", "R"], icon: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.6"/><path d="m6.5 6.5 11 11" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>' },
];

function seeded(seed) {
  return () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function fillMotes(field) {
  const random = seeded(7138);
  const fragment = document.createDocumentFragment();

  for (let i = 0; i < MOTE_COUNT; i++) {
    const angle = random() * Math.PI * 2;
    const radius = Math.pow(random(), 0.6);
    const size = (2 + random() * 3.5).toFixed(1);
    const mote = document.createElement("span");

    mote.className = "pv-mote";
    mote.style.left = `${Math.max(2, Math.min(70, CENTRE_X + Math.cos(angle) * radius * SPREAD_X)).toFixed(2)}%`;
    mote.style.top = `${Math.max(1, Math.min(46, CENTRE_Y + Math.sin(angle) * radius * SPREAD_Y)).toFixed(2)}%`;
    mote.style.width = `${size}px`;
    mote.style.height = `${size}px`;
    mote.style.setProperty("--wander", `pv-mote-${i % 5}`);
    mote.style.setProperty("--o", (0.25 + random() * 0.4).toFixed(2));
    mote.style.animationDuration = `${(6 + random() * 7).toFixed(1)}s, ${(2.5 + random() * 3.5).toFixed(1)}s`;
    mote.style.animationDelay = `${(-(random() * 12)).toFixed(1)}s, ${(-(random() * 12)).toFixed(1)}s`;
    fragment.append(mote);
  }
  field.append(fragment);
}

function buildRows(list) {
  list.innerHTML = FILES.map(
    (f) => `<span class="pv-row" data-unread="${f.fresh}">
      <span class="pv-row-dot"></span>
      <span class="pv-row-main">
        <span class="pv-row-top">
          <span class="pv-row-from">${f.name}</span>
          <span class="pv-row-time tnum">${f.time}</span>
        </span>
        <span class="pv-row-sub">${LOCK}${f.meta}</span>
      </span>
    </span>`,
  ).join("");
  return [...list.children];
}

function buildCommands(list) {
  list.innerHTML = COMMANDS.map(
    (c) => `<span class="pv-pal-item" data-key="${c.key}">
      <span class="pv-pal-ic">${c.icon}</span>${c.label}
      <span class="pv-pal-keys">${c.keys.map((k) => `<span class="hos-kbd">${k}</span>`).join("")}</span>
    </span>`,
  ).join("");
  return [...list.children];
}

function cycleRows(rows) {
  let index = 0;
  const step = () => {
    rows.forEach((row, i) => row.setAttribute("data-active", String(i === index)));
    index = (index + 1) % rows.length;
  };
  step();
  setInterval(step, 1500);
}

function typeCommand(typed, placeholder, items) {
  const word = "sha";
  let i = 0;
  let forward = true;

  const paint = () => {
    typed.textContent = word.slice(0, i);
    const active = i > 0;
    placeholder.style.display = active ? "none" : "";
    items.forEach((item, index) => {
      item.setAttribute("data-on", String(active ? item.dataset.key === "share" : index === 0));
    });
  };

  const tick = () => {
    paint();
    if (forward) {
      if (i < word.length) {
        i += 1;
        setTimeout(tick, 220);
      } else {
        forward = false;
        setTimeout(tick, 2200);
      }
    } else if (i > 0) {
      i -= 1;
      setTimeout(tick, 110);
    } else {
      forward = true;
      setTimeout(tick, 900);
    }
  };

  paint();
  setTimeout(tick, 1100);
}

function parallax(hero, win) {
  const update = () => {
    const travel = Math.max(hero.offsetHeight - window.innerHeight, 1);
    const progress = Math.min(Math.max(window.scrollY / travel, 0), 1);
    win.style.transform = `translateY(${(-90 * progress).toFixed(1)}px)`;
  };
  update();
  addEventListener("scroll", update, { passive: true });
}

function trackScroll(nav) {
  const update = () => {
    nav.dataset.scrolled = window.scrollY > 16 ? "true" : "false";
  };
  update();
  addEventListener("scroll", update, { passive: true });
}

const hero = document.querySelector(".pv-hero");

fillMotes(document.querySelector(".pv-motes"));
trackScroll(document.querySelector(".pv-nav"));
cycleRows(buildRows(document.querySelector(".pv-win-list")));
typeCommand(
  document.querySelector(".pv-pal-typed"),
  document.querySelector(".pv-pal-ph"),
  buildCommands(document.querySelector(".pv-pal-list")),
);
parallax(hero, document.querySelector(".pv-win"));
