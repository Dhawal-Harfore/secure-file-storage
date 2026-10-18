import { h, iconButton } from "./dom.js";

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), video[controls], [tabindex]:not([tabindex="-1"])';
let counter = 0;
let open = 0;

export function openDialog({ title, description, size = "md", body = [], footer = [], onClose } = {}) {
  const titleId = `pv-dialog-${++counter}`;
  const returnFocus = document.activeElement;

  const titleEl = h("h2", { class: "hos-dialog__title", id: titleId, text: title });
  const descriptionEl = description ? h("p", { class: "hos-dialog__description", text: description }) : null;
  const bodyEl = h("div", { class: "hos-dialog__body" }, body);
  const footerEl = footer.length ? h("div", { class: "hos-dialog__footer" }, footer) : null;
  const closeButton = iconButton("close", "Close", { class: "hos-icon-button hos-dialog__close", onclick: () => close() });

  const panel = h(
    "div",
    { class: "hos-dialog", role: "dialog", "aria-modal": "true", "aria-labelledby": titleId, tabindex: "-1", "data-state": "open", "data-size": size },
    h("div", { class: "hos-dialog__header" }, h("div", { class: "hos-dialog__heading" }, titleEl, descriptionEl), closeButton),
    bodyEl,
    footerEl,
  );
  const backdrop = h("div", { class: "hos-dialog__backdrop", "data-state": "open", onclick: () => close() });
  const layer = h("div", { class: "hos-dialog__layer" }, backdrop, panel);

  let closed = false;
  let resolveDone;
  const done = new Promise((resolve) => (resolveDone = resolve));

  function onKey(event) {
    if (event.key === "Escape") {
      event.stopPropagation();
      close();
      return;
    }
    if (event.key !== "Tab") return;
    const items = [...panel.querySelectorAll(FOCUSABLE)].filter((el) => el.offsetParent !== null);
    if (items.length === 0) {
      event.preventDefault();
      panel.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function close(result) {
    if (closed) return;
    closed = true;
    panel.dataset.state = "closed";
    backdrop.dataset.state = "closed";
    layer.removeEventListener("keydown", onKey);
    const finish = () => {
      layer.remove();
      open -= 1;
      if (open === 0) document.documentElement.style.overflow = "";
      if (returnFocus?.isConnected) returnFocus.focus();
      onClose?.(result);
      resolveDone(result);
    };
    const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduced) finish();
    else setTimeout(finish, 140);
  }

  layer.addEventListener("keydown", onKey);
  document.body.append(layer);
  open += 1;
  document.documentElement.style.overflow = "hidden";

  const firstField = bodyEl.querySelector("input:not([type=radio]):not([type=checkbox]), textarea");
  (firstField ?? panel).focus();

  return { panel, body: bodyEl, footer: footerEl, close, done, setTitle: (text) => (titleEl.textContent = text) };
}
