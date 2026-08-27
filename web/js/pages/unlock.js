import { api, ApiError } from "../api/index.js";

const VEIL_SCRIPT = "vendor/veil/veil-cloth.js";
const VEIL_RUNTIME = "vendor/veil/runtime/";
const STALL_MS = 25000;
const FALL_MS = 850;
const NEXT = "vault.html";

const veil = document.querySelector("#veil");
const panel = document.querySelector("#panel");
const title = document.querySelector("#veil-title");
const sub = document.querySelector("#veil-sub");
const hand = document.querySelector("#hand");
const handOpen = document.querySelector("#hand-open");
const handGrab = document.querySelector("#hand-grab");
const fallback = document.querySelector("#fallback");
const fallbackTitle = document.querySelector("#fallback-title");
const fallbackText = document.querySelector("#fallback-text");

const originalTitle = document.title;
let element = null;
let verified = false;
let clothReady = false;
let done = false;

function enter() {
  if (done) return;
  done = true;
  document.title = originalTitle;
  location.replace(NEXT);
}

function refuse(message) {
  if (done) return;
  done = true;
  document.title = originalTitle;
  try {
    element?.destroy?.();
  } catch {}
  element?.remove();
  veil.remove();
  fallbackTitle.textContent = "This link didn't work";
  fallbackText.textContent = message;
  fallback.hidden = false;
}

function arm() {
  if (!verified || !clothReady || done) return;
  if (veil.dataset.phase !== "verifying") return;

  veil.dataset.phase = "armed";
  panel.querySelector(".hos-spinner")?.remove();
  title.textContent = "Tear to enter";
  title.classList.add("veil-title-armed");
  sub.textContent = "Drag across the cloth to rip it away.";

  element?.setAttribute("interaction", "both");
  element?.setAttribute("tearable", "true");
  element?.style.setProperty("--veil-cursor", "none");
  hand.hidden = false;
  trackHand();
}

function trackHand() {
  const move = (e) => {
    hand.style.transform = `translate(${e.clientX}px, ${e.clientY}px)`;
  };
  const grab = (down) => {
    handOpen.hidden = down;
    handGrab.hidden = !down;
  };
  addEventListener("pointermove", move);
  addEventListener("pointerdown", () => grab(true));
  addEventListener("pointerup", () => grab(false));
}

function loadCloth() {
  return new Promise((resolve, reject) => {
    if (customElements.get("veil-cloth")) return resolve();
    const script = document.createElement("script");
    script.type = "module";
    script.src = VEIL_SCRIPT;
    script.onload = () => customElements.whenDefined("veil-cloth").then(resolve);
    script.onerror = () => reject(new Error("veil engine failed to load"));
    document.head.appendChild(script);
  });
}

function mountCloth() {
  loadCloth()
    .then(() => {
      if (done) return;
      element = document.createElement("veil-cloth");
      element.className = "veil-host";
      element.setAttribute("src", VEIL_RUNTIME);
      element.setAttribute("color", "#212c36, #0d0c0a");
      element.setAttribute("reveal-threshold", "0.2");
      element.setAttribute("breeze", "10");
      element.setAttribute("interaction", "none");
      element.setAttribute("tearable", "false");
      element.style.setProperty("--veil-cursor", "progress");

      element.addEventListener("veil-ready", () => {
        clothReady = true;
        arm();
      });
      element.addEventListener("veil-revealed", () => {
        veil.dataset.phase = "revealed";
        setTimeout(enter, FALL_MS);
      });
      element.addEventListener("veil-error", () => {
        if (verified) enter();
      });

      veil.prepend(element);
    })
    .catch(() => {
      if (verified) enter();
    });
}

async function verify() {
  const token = new URLSearchParams(location.search).get("token");
  if (!token) {
    refuse("The sign-in link is missing its token. Request a fresh one.");
    return;
  }
  try {
    await api.auth.verify(token);
    verified = true;
    arm();
  } catch (err) {
    refuse(
      err instanceof ApiError
        ? err.message
        : "We could not verify that link. Request a fresh one.",
    );
  }
}

setTimeout(() => {
  if (verified && !done) enter();
}, STALL_MS);

mountCloth();
verify();
