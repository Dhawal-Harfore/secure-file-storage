export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props ?? {})) {
    if (value == null || value === false) continue;
    if (key === "class") el.className = value;
    else if (key === "text") el.textContent = value;
    else if (key === "dataset") Object.assign(el.dataset, value);
    else if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2), value);
    else if (value === true) el.setAttribute(key, "");
    else el.setAttribute(key, String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : String(child));
  }
  return el;
}

const ICONS = {
  pdf: '<path d="M6 2.5h7l5 5v14H6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M13 2.5v5h5" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>',
  image: '<rect x="3.5" y="4.5" width="17" height="15" rx="2" stroke="currentColor" stroke-width="1.5"/><circle cx="9" cy="10" r="1.6" stroke="currentColor" stroke-width="1.5"/><path d="m5 17 4.5-4.5L14 17l3-3 2 2" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>',
  video: '<rect x="3" y="5" width="13" height="14" rx="2" stroke="currentColor" stroke-width="1.5"/><path d="m16 12 5-3v10l-5-3z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>',
  other: '<path d="M6 2.5h7l5 5v14H6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M13 2.5v5h5" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>',
  upload: '<path d="M12 16V4m0 0-4.5 4.5M12 4l4.5 4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/><path d="M4.5 15v3.5a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V15" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>',
  more: '<circle cx="5.5" cy="12" r="1.4" fill="currentColor"/><circle cx="12" cy="12" r="1.4" fill="currentColor"/><circle cx="18.5" cy="12" r="1.4" fill="currentColor"/>',
  close: '<path d="m6 6 12 12M18 6 6 18" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>',
  link: '<path d="M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><path d="M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>',
  download: '<path d="M12 4v12m0 0-4.5-4.5M12 16l4.5-4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/><path d="M4.5 19.5h15" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>',
};

export function icon(name, size = 18) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("width", size);
  svg.setAttribute("height", size);
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("aria-hidden", "true");
  svg.innerHTML = ICONS[name] ?? ICONS.other;
  return svg;
}

export function button(label, { variant = "secondary", size = "sm", iconName, onclick, ...rest } = {}) {
  return h(
    "button",
    { class: "hos-button", type: "button", "data-variant": variant, "data-size": size, "data-hos-focus": true, onclick, ...rest },
    iconName ? h("span", { class: "hos-button__icon" }, icon(iconName, 16)) : null,
    h("span", { class: "hos-button__label", text: label }),
  );
}

export function iconButton(name, label, { onclick, ...rest } = {}) {
  return h(
    "button",
    { class: "hos-icon-button", type: "button", "aria-label": label, title: label, "data-variant": "ghost", "data-size": "sm", "data-hos-focus": true, onclick, ...rest },
    icon(name, 16),
  );
}

export function busy(btn, on, label) {
  btn.disabled = on;
  if (on) btn.dataset.loading = "true";
  else delete btn.dataset.loading;
  if (label) btn.querySelector(".hos-button__label").textContent = label;
}
