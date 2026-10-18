import { h } from "./dom.js";

let host;
let timer;

function ensureHost() {
  if (!host) {
    host = document.getElementById("toast") ?? h("div", { class: "pv-toast", id: "toast", role: "status", "aria-live": "polite", hidden: true });
    if (!host.isConnected) document.body.append(host);
  }
  return host;
}

export function toast(message, { tone = "info", action, duration = 4500 } = {}) {
  const el = ensureHost();
  clearTimeout(timer);
  el.replaceChildren(h("span", { class: "pv-toast-text", text: message }));
  el.dataset.tone = tone;

  if (action) {
    el.append(
      h("button", {
        class: "pv-toast-action",
        type: "button",
        "data-hos-focus": true,
        text: action.label,
        onclick: () => {
          hide();
          action.run();
        },
      }),
    );
  }

  el.hidden = false;
  timer = setTimeout(hide, duration);
}

export function hide() {
  clearTimeout(timer);
  if (host) host.hidden = true;
}

export function withUndo(message, { commit, undo, delay = 6000 }) {
  let settled = false;
  const pending = { flush };

  function flush() {
    if (settled) return;
    settled = true;
    clearTimeout(handle);
    removeEventListener("pagehide", flush);
    commit({ keepalive: true });
  }

  const handle = setTimeout(() => {
    if (settled) return;
    settled = true;
    removeEventListener("pagehide", flush);
    commit({ keepalive: false });
  }, delay);

  addEventListener("pagehide", flush);

  toast(message, {
    duration: delay,
    action: {
      label: "Undo",
      run: () => {
        if (settled) return;
        settled = true;
        clearTimeout(handle);
        removeEventListener("pagehide", flush);
        undo();
      },
    },
  });

  return pending;
}
