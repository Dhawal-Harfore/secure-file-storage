import { api, ApiError, can } from "../api/index.js";
import { config } from "../config.js";
import { h, icon, button, iconButton, busy } from "../lib/dom.js";
import { openDialog } from "../lib/dialog.js";
import { toast, withUndo } from "../lib/toast.js";
import { readableSize, ago, servedTier, shareState } from "../lib/format.js";
import { UploadQueue, browserStore } from "../upload/uploader.js";
import { workerHasher } from "../lib/sha256.js";

const who = document.querySelector("#who");
const state = document.querySelector("#state");
const list = document.querySelector("#list");
const count = document.querySelector("#count");
const uploadsEl = document.querySelector("#uploads");
const picker = document.querySelector("#picker");
const drop = document.querySelector("#drop");

let files = [];
let me = null;
const hiddenIds = new Set();
const uploadRows = new Map();

const message = (err, fallback) => (err instanceof ApiError && err.message ? err.message : fallback);
const tierLabel = (tier) => config.tierLabels[tier] ?? tier;

function render() {
  const visible = files.filter((f) => !hiddenIds.has(f.id));
  count.textContent = `${visible.length} file${visible.length === 1 ? "" : "s"}`;

  if (visible.length === 0) {
    list.hidden = true;
    state.hidden = false;
    state.replaceChildren(
      h(
        "div",
        { class: "hos-empty-state" },
        h("div", { class: "hos-empty-state__icon" }, icon("upload", 24)),
        h(
          "div",
          { class: "hos-empty-state__text" },
          h("p", { class: "hos-empty-state__title", text: "No files yet" }),
          h("p", { class: "hos-empty-state__description", text: "Upload a file or drop it anywhere on this page. It's encrypted before it's stored." }),
        ),
        h("div", { class: "hos-empty-state__actions" }, button("Upload a file", { variant: "primary", onclick: () => picker.click() })),
      ),
    );
    return;
  }

  list.replaceChildren(...visible.map(row));
  state.hidden = true;
  list.hidden = false;
}

function thumb(file) {
  const served = servedTier(file);
  const url = file.kind !== "other" && served !== "metadata" ? api.files.thumbUrl(file.id) : null;
  const holder = h("span", { class: "pv-file-icon", "data-kind": file.kind }, icon(file.kind, 22));
  if (!url) return holder;
  const img = h("img", { class: "pv-thumb", src: url, alt: "", loading: "lazy", decoding: "async" });
  img.addEventListener("error", () => img.replaceWith(holder), { once: true });
  return img;
}

function row(file) {
  const { permit } = file;
  const served = servedTier(file);
  const mine = file.ownerId === me?.id;

  const meta = h(
    "span",
    { class: "pv-file-meta" },
    h("span", { text: config.levelLabels[permit.accessLevel] ?? permit.accessLevel }),
    h("span", { "aria-hidden": "true", text: "·" }),
    h("span", { class: "tnum", text: readableSize(file.size) }),
    h("span", { "aria-hidden": "true", text: "·" }),
    h("span", { title: new Date(file.updatedAt).toLocaleString(), text: ago(file.updatedAt) }),
    mine ? null : h("span", { class: "pv-owner", text: `from ${file.ownerEmail}` }),
    h("span", { class: "pv-tier", "data-tier": served, text: tierLabel(served) }),
  );

  const actions = h("span", { class: "pv-file-actions" });
  if (can(permit, "share")) {
    actions.append(button("Share", { variant: "secondary", iconName: "link", onclick: () => shareDialog(file) }));
  }
  if (can(permit, "download") && served !== "metadata") {
    const url = api.files.contentUrl(file.id, served);
    if (url) {
      actions.append(
        h("a", { class: "hos-icon-button", href: url, download: file.name, "aria-label": `Download ${file.name}`, title: "Download", "data-hos-focus": true }, icon("download", 16)),
      );
    }
  }
  const menuItems = [];
  if (can(permit, "edit")) menuItems.push({ label: "Rename", run: () => renameDialog(file) });
  if (can(permit, "delete")) menuItems.push({ label: "Delete", destructive: true, run: () => remove(file) });
  if (menuItems.length) {
    const more = iconButton("more", `More actions for ${file.name}`, { "aria-haspopup": "menu", "aria-expanded": "false" });
    more.addEventListener("click", () => openMenu(more, menuItems));
    actions.append(more);
  }

  return h(
    "div",
    { class: "pv-file", "data-id": file.id },
    thumb(file),
    h(
      "span",
      { class: "pv-file-main" },
      h("button", { class: "pv-file-name", type: "button", "data-hos-focus": true, title: file.name, text: file.name, onclick: () => previewDialog(file) }),
      meta,
    ),
    actions,
  );
}

function openMenu(anchor, items) {
  const menu = h(
    "div",
    { class: "hos-menu", role: "menu", "data-state": "open" },
    items.map((item) =>
      h(
        "button",
        {
          class: "hos-menu__item",
          type: "button",
          role: "menuitem",
          "data-destructive": item.destructive ? true : null,
          onclick: () => {
            close();
            item.run();
          },
        },
        h("span", { class: "hos-menu__item-label", text: item.label }),
      ),
    ),
  );

  const rect = anchor.getBoundingClientRect();
  menu.style.top = `${Math.round(rect.bottom + 4)}px`;
  menu.style.right = `${Math.max(16, Math.round(innerWidth - rect.right))}px`;
  menu.style.zIndex = "var(--hos-z-popover)";
  document.body.append(menu);
  anchor.setAttribute("aria-expanded", "true");

  const buttons = [...menu.querySelectorAll("button")];
  buttons[0].focus();

  function onKey(event) {
    const i = buttons.indexOf(document.activeElement);
    if (event.key === "Escape") {
      close();
      anchor.focus();
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      buttons[(i + 1) % buttons.length].focus();
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      buttons[(i - 1 + buttons.length) % buttons.length].focus();
    } else if (event.key === "Tab") {
      close();
    }
  }
  function onOutside(event) {
    if (!menu.contains(event.target) && event.target !== anchor) close();
  }
  function close() {
    menu.remove();
    anchor.setAttribute("aria-expanded", "false");
    document.removeEventListener("keydown", onKey, true);
    document.removeEventListener("pointerdown", onOutside, true);
    removeEventListener("scroll", close, true);
  }

  document.addEventListener("keydown", onKey, true);
  document.addEventListener("pointerdown", onOutside, true);
  addEventListener("scroll", close, true);
}

function detailsList(file) {
  const rows = [
    ["Type", file.mime],
    ["Size", readableSize(file.size)],
    ["Owner", file.ownerEmail],
    ["Added", new Date(file.createdAt).toLocaleString()],
    ["Your access", config.levelLabels[file.permit.accessLevel] ?? file.permit.accessLevel],
  ];
  return h("dl", { class: "pv-details" }, rows.map(([k, v]) => [h("dt", { text: k }), h("dd", { text: v })]));
}

function previewDialog(file) {
  const served = servedTier(file);
  const footer = [];
  const body = [];

  if (served === "metadata") {
    body.push(h("p", { class: "pv-note", text: "You can see this file's details but not open it." }), detailsList(file));
  } else {
    const url = api.files.contentUrl(file.id, served);
    const isPdfDocument = file.kind === "pdf" && served !== "lowest";

    if (!url) {
      body.push(h("p", { class: "pv-note", text: "There's no preview of this file here yet." }));
    } else if (file.kind === "image" || (file.kind === "pdf" && served === "lowest")) {
      body.push(h("img", { class: "pv-preview-media", src: url, alt: file.name }));
    } else if (file.kind === "video") {
      const poster = api.files.thumbUrl(file.id);
      body.push(h("video", { class: "pv-preview-media", src: url, controls: true, preload: "metadata", playsinline: true, poster: poster ?? null }));
    } else if (isPdfDocument) {
      const firstPage = api.files.thumbUrl(file.id);
      if (firstPage) body.push(h("img", { class: "pv-preview-media", src: firstPage, alt: `First page of ${file.name}` }));
      footer.push(h("a", { class: "hos-button", href: url, target: "_blank", rel: "noopener", "data-variant": "secondary", "data-size": "sm", "data-hos-focus": true }, h("span", { class: "hos-button__label", text: "Open the PDF" })));
    }
    if (file.kind === "other") body.push(detailsList(file));

    if (can(file.permit, "download") && url) {
      footer.push(h("a", { class: "hos-button", href: url, download: file.name, "data-variant": "primary", "data-size": "sm", "data-hos-focus": true }, h("span", { class: "hos-button__label", text: "Download" })));
    }
  }

  openDialog({
    title: file.name,
    description:
      served === "metadata"
        ? "Details only"
        : served === "original"
          ? `Original · ${readableSize(file.size)}`
          : `${tierLabel(served)} copy · the original is ${readableSize(file.size)}`,
    size: file.kind === "other" || served === "metadata" ? "md" : "xl",
    body,
    footer,
  });
}

function renameDialog(file) {
  const input = h("input", { class: "hos-input", id: "rename-input", value: file.name, maxlength: "255", autocomplete: "off", "data-hos-focus": true });
  const error = h("p", { class: "hos-field__error", role: "alert", hidden: true });
  const save = button("Save", { variant: "primary", type: "submit", form: "rename-form" });
  const form = h(
    "form",
    { id: "rename-form", class: "hos-field", novalidate: true },
    h("label", { class: "hos-label", for: "rename-input", text: "Name" }),
    input,
    error,
  );

  const dialog = openDialog({ title: "Rename", size: "sm", body: [form], footer: [button("Cancel", { variant: "ghost", onclick: () => dialog.close() }), save] });
  input.select();

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const name = input.value.trim();
    if (!name) {
      error.textContent = "The name can't be empty.";
      error.hidden = false;
      return;
    }
    if (name === file.name) {
      dialog.close();
      return;
    }
    busy(save, true, "Saving…");
    try {
      const updated = await api.files.rename(file.id, name);
      files = files.map((f) => (f.id === file.id ? { ...f, ...updated } : f));
      render();
      dialog.close();
      toast(`Renamed to ${name}.`);
    } catch (err) {
      error.textContent = message(err, "Couldn't rename the file.");
      error.hidden = false;
      busy(save, false, "Save");
    }
  });
}

function remove(file) {
  hiddenIds.add(file.id);
  render();
  withUndo(`Deleted ${file.name}.`, {
    undo: () => {
      hiddenIds.delete(file.id);
      render();
    },
    commit: async ({ keepalive }) => {
      try {
        await api.files.remove(file.id, { keepalive });
        files = files.filter((f) => f.id !== file.id);
        hiddenIds.delete(file.id);
        render();
      } catch (err) {
        hiddenIds.delete(file.id);
        render();
        toast(message(err, `Couldn't delete ${file.name}.`), { tone: "danger" });
      }
    },
  });
}

function radio(name, value, label, description, checked) {
  return h(
    "label",
    { class: "hos-radio" },
    h("span", { class: "hos-radio__control" }, h("input", { class: "hos-radio__input", type: "radio", name, value, checked: checked || null }), h("span", { class: "hos-radio__dot" })),
    h("span", { class: "hos-radio__text" }, h("span", { class: "hos-radio__label", text: label }), description ? h("span", { class: "hos-radio__description", text: description }) : null),
  );
}

function shareLink(token) {
  return new URL(`share.html#${token}`, location.href).href;
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

function expiryText(share) {
  if (!share.expiresAt) return "No expiry";
  const at = new Date(share.expiresAt);
  return at.getTime() > Date.now() ? `Expires ${ago(share.expiresAt)}` : `Expired ${ago(share.expiresAt)}`;
}

function shareRow(share, file, refresh) {
  const status = shareState(share);
  const views = share.maxViews != null ? `${share.viewsUsed} of ${share.maxViews} views` : `${share.viewsUsed} view${share.viewsUsed === 1 ? "" : "s"}`;
  const actions = h("span", { class: "pv-share-actions" });

  if (status === "live") {
    actions.append(
      button("Copy link", {
        variant: "secondary",
        onclick: async () => toast((await copy(shareLink(share.token))) ? "Link copied." : `Copy this link: ${shareLink(share.token)}`),
      }),
    );
    if (can(file.permit, "revoke")) {
      actions.append(
        button("Revoke", {
          variant: "ghost",
          onclick: () => {
            share.pending = true;
            refresh();
            withUndo("Link revoked.", {
              undo: () => {
                share.pending = false;
                refresh();
              },
              commit: async ({ keepalive }) => {
                try {
                  const updated = await api.shares.revoke(share.id, { keepalive });
                  Object.assign(share, updated ?? { revokedAt: new Date().toISOString() }, { pending: false });
                } catch (err) {
                  share.pending = false;
                  toast(message(err, "Couldn't revoke the link."), { tone: "danger" });
                }
                refresh();
              },
            });
          },
        }),
      );
    }
  }

  return h(
    "div",
    { class: "pv-share", "data-pending": share.pending ? "true" : null },
    h(
      "span",
      { class: "pv-share-main" },
      h("span", { class: "pv-share-title" }, h("span", { class: "pv-dot", "data-tone": status === "live" ? "success" : "neutral", "aria-hidden": "true" }), `${config.levelLabels[share.accessLevel]} link`, h("span", { class: "pv-share-state", text: share.pending ? "revoking" : status })),
      h("span", { class: "pv-share-meta", text: `${expiryText(share)} · ${views}${share.actions.includes("download") ? " · download allowed" : ""}` }),
    ),
    actions,
  );
}

async function shareDialog(file) {
  const existing = h("div", { class: "pv-share-list" }, h("p", { class: "pv-note", text: "Loading links…" }));
  let shares = [];
  const refresh = () => {
    existing.replaceChildren(
      ...(shares.length ? shares.map((s) => shareRow(s, file, refresh)) : [h("p", { class: "pv-note", text: "No links yet." })]),
    );
  };

  const allowDownload = h("input", { class: "hos-switch__input", type: "checkbox", id: "share-download" });
  const downloadSwitch = h(
    "label",
    { class: "hos-switch", for: "share-download" },
    h("span", { class: "hos-switch__control" }, allowDownload, h("span", { class: "hos-switch__track" }, h("span", { class: "hos-switch__thumb" }))),
    h("span", { class: "hos-switch__text" }, h("span", { class: "hos-switch__label", text: "Allow download" })),
  );
  const maxViews = h("input", { class: "hos-input", id: "share-views", type: "number", min: "1", max: "100000", inputmode: "numeric", placeholder: "No limit", "data-hos-focus": true });
  const error = h("p", { class: "hos-field__error", role: "alert", hidden: true });
  const result = h("div", { class: "pv-share-result", hidden: true });

  const form = h(
    "form",
    { id: "share-form", class: "pv-form", novalidate: true },
    h(
      "fieldset",
      { class: "pv-fieldset" },
      h("legend", { class: "hos-label", text: "Who gets" }),
      h(
        "div",
        { class: "hos-radio-group", "data-orientation": "vertical" },
        radio("level", "viewer", "Viewer", "Sees a reduced-quality copy.", true),
        radio("level", "guest", "Guest", "Preview only: the lowest-quality copy or first page.", false),
      ),
      downloadSwitch,
    ),
    h(
      "fieldset",
      { class: "pv-fieldset" },
      h("legend", { class: "hos-label", text: "Link ends" }),
      h(
        "div",
        { class: "hos-radio-group", "data-orientation": "horizontal" },
        radio("expiry", "1", "In 1 day", null, false),
        radio("expiry", "7", "In 7 days", null, true),
        radio("expiry", "30", "In 30 days", null, false),
        radio("expiry", "", "Never", null, false),
      ),
    ),
    h("div", { class: "hos-field" }, h("label", { class: "hos-label", for: "share-views", text: "Maximum views" }), maxViews),
    error,
    result,
  );

  form.addEventListener("change", () => {
    const guest = form.elements.level.value === "guest";
    allowDownload.disabled = guest;
    if (guest) allowDownload.checked = false;
  });

  const create = button("Create link", { variant: "primary", type: "submit", form: "share-form" });
  const dialog = openDialog({
    title: `Share ${file.name}`,
    description: "Anyone with the link can open it at the level you pick. You can revoke it any time.",
    size: "md",
    body: [existing, h("hr", { class: "hos-separator" }), form],
    footer: [button("Done", { variant: "ghost", onclick: () => dialog.close() }), create],
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    error.hidden = true;
    const level = form.elements.level.value;
    const days = form.elements.expiry.value;
    const views = maxViews.value.trim();
    if (views && (!/^\d+$/.test(views) || Number(views) < 1)) {
      error.textContent = "Maximum views has to be a whole number above zero.";
      error.hidden = false;
      return;
    }
    const payload = {
      accessLevel: level,
      actions: level === "viewer" && allowDownload.checked ? ["view", "download"] : ["view"],
      expiresAt: days ? new Date(Date.now() + Number(days) * 864e5).toISOString() : null,
      maxViews: views ? Number(views) : null,
    };
    busy(create, true, "Creating…");
    try {
      const share = await api.shares.create(file.id, payload);
      shares = [share, ...shares];
      refresh();
      const link = shareLink(share.token);
      const copied = await copy(link);
      result.replaceChildren(
        h("p", { class: "pv-note", text: copied ? "Link created and copied." : "Link created. Copy it from here:" }),
        h("input", { class: "hos-input pv-mono", value: link, readonly: true, "aria-label": "Share link", onfocus: (e) => e.target.select() }),
      );
      result.hidden = false;
    } catch (err) {
      error.textContent = message(err, "Couldn't create the link.");
      error.hidden = false;
    } finally {
      busy(create, false, "Create link");
    }
  });

  try {
    shares = await api.shares.list(file.id);
    refresh();
  } catch (err) {
    existing.replaceChildren(h("p", { class: "pv-note", text: message(err, "Couldn't load the existing links.") }));
  }
}

const queue = new UploadQueue({ api, createHasher: workerHasher, store: browserStore(), maxBytes: config.maxUploadBytes });
let refreshTimer;

function uploadStatus(item) {
  switch (item.state) {
    case "queued":
      return "Waiting";
    case "uploading":
      return `${readableSize(item.sent)} of ${readableSize(item.size)}`;
    case "finishing":
      return "Checking and encrypting";
    case "done":
      return "Uploaded";
    case "cancelled":
      return "Cancelled";
    default:
      return item.error || "Failed";
  }
}

function renderUpload(item) {
  let rowEl = uploadRows.get(item.id);
  if (!rowEl) {
    rowEl = h("div", { class: "pv-upload" });
    uploadRows.set(item.id, rowEl);
    uploadsEl.append(rowEl);
  }
  const pct = item.size ? Math.min(100, Math.round((item.sent / item.size) * 100)) : 0;
  const actions = [];
  if (["queued", "uploading"].includes(item.state)) actions.push(button("Cancel", { variant: "ghost", onclick: () => queue.cancel(item) }));
  if (item.state === "failed") actions.push(button("Retry", { variant: "secondary", onclick: () => queue.retry(item) }));

  rowEl.dataset.state = item.state;
  rowEl.replaceChildren(
    h(
      "div",
      { class: "pv-upload-main" },
      h("span", { class: "pv-upload-name", title: item.name, text: item.name }),
      h("span", { class: "pv-upload-status", text: uploadStatus(item) }),
      h(
        "div",
        { class: "pv-progress", role: "progressbar", "aria-label": `Upload of ${item.name}`, "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": String(pct) },
        h("span", { class: "pv-progress-bar", style: `width:${item.state === "done" ? 100 : pct}%` }),
      ),
    ),
    h("span", { class: "pv-upload-actions" }, actions),
  );
}

queue.addEventListener("change", (event) => {
  const item = event.detail;
  if (!item) {
    uploadsEl.replaceChildren();
    uploadRows.clear();
    queue.items.forEach(renderUpload);
  } else {
    renderUpload(item);
    if (item.state === "done" && item.result) {
      files = [item.result, ...files.filter((f) => f.id !== item.result.id)];
      render();
      clearTimeout(refreshTimer);
      refreshTimer = setTimeout(load, 8000);
    }
  }

  const finished = queue.items.length > 0 && queue.items.every((i) => ["done", "cancelled"].includes(i.state));
  uploadsEl.hidden = queue.items.length === 0;
  if (finished) setTimeout(() => {
    if (queue.items.every((i) => ["done", "cancelled"].includes(i.state))) queue.clearFinished();
    uploadsEl.hidden = queue.items.length === 0;
  }, 4000);
});

function addFiles(fileList) {
  const picked = [...fileList];
  if (picked.length) {
    uploadsEl.hidden = false;
    queue.add(picked);
  }
}

document.querySelector("#upload").addEventListener("click", () => picker.click());
picker.addEventListener("change", () => {
  addFiles(picker.files);
  picker.value = "";
});

let dragDepth = 0;
const hasFiles = (event) => [...(event.dataTransfer?.types ?? [])].includes("Files");
addEventListener("dragenter", (event) => {
  if (!hasFiles(event)) return;
  dragDepth += 1;
  drop.hidden = false;
});
addEventListener("dragleave", () => {
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) drop.hidden = true;
});
addEventListener("dragover", (event) => {
  if (hasFiles(event)) event.preventDefault();
});
addEventListener("drop", (event) => {
  if (!hasFiles(event)) return;
  event.preventDefault();
  dragDepth = 0;
  drop.hidden = true;
  addFiles(event.dataTransfer.files);
});

async function load() {
  try {
    files = await api.files.list();
    render();
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      location.replace("login.html");
      return;
    }
    list.hidden = true;
    state.hidden = false;
    state.replaceChildren(
      h(
        "div",
        { class: "hos-empty-state" },
        h(
          "div",
          { class: "hos-empty-state__text" },
          h("p", { class: "hos-empty-state__title", text: "Couldn't load your files" }),
          h("p", { class: "hos-empty-state__description", text: message(err, "Something went wrong on the way to the server.") }),
        ),
        h("div", { class: "hos-empty-state__actions" }, button("Try again", { variant: "secondary", onclick: load })),
      ),
    );
  }
}

document.querySelector("#signout").addEventListener("click", async () => {
  try {
    await api.auth.logout();
  } finally {
    location.replace("index.html");
  }
});

try {
  ({ user: me } = await api.auth.session());
  who.textContent = me.email;
  load();
} catch {
  location.replace("login.html");
}
