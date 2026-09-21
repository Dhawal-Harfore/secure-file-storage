import { api, ApiError, can } from "../api/index.js";
import { config } from "../config.js";

const who = document.querySelector("#who");
const state = document.querySelector("#state");
const list = document.querySelector("#list");
const count = document.querySelector("#count");
const toast = document.querySelector("#toast");

const ICONS = {
  pdf: '<path d="M6 2.5h7l5 5v14H6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M13 2.5v5h5" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>',
  image: '<rect x="3.5" y="4.5" width="17" height="15" rx="2" stroke="currentColor" stroke-width="1.5"/><circle cx="9" cy="10" r="1.6" stroke="currentColor" stroke-width="1.5"/><path d="m5 17 4.5-4.5L14 17l3-3 2 2" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>',
  video: '<rect x="3" y="5" width="13" height="14" rx="2" stroke="currentColor" stroke-width="1.5"/><path d="m16 12 5-3v10l-5-3z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>',
  other: '<path d="M6 2.5h7l5 5v14H6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M13 2.5v5h5" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>',
};

let toastTimer;
function say(message, tone = "info") {
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.dataset.tone = tone;
  toast.hidden = false;
  toastTimer = setTimeout(() => {
    toast.hidden = true;
  }, 4000);
}

function readableSize(bytes) {
  const units = ["B", "KB", "MB", "GB"];
  let n = bytes;
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i += 1;
  }
  return `${n < 10 && i > 0 ? n.toFixed(1) : Math.round(n)} ${units[i]}`;
}

function actionButton(label, variant, handler) {
  const button = document.createElement("button");
  button.className = "hos-button";
  button.type = "button";
  button.dataset.variant = variant;
  button.dataset.size = "sm";
  button.setAttribute("data-hos-focus", "");
  button.innerHTML = `<span class="hos-button__label">${label}</span>`;
  button.addEventListener("click", handler);
  return button;
}

function render(files) {
  list.replaceChildren();
  count.textContent = `${files.length} file${files.length === 1 ? "" : "s"}`;

  for (const file of files) {
    const { permit } = file;

    const row = document.createElement("div");
    row.className = "pv-file";

    row.insertAdjacentHTML(
      "beforeend",
      `<span class="pv-file-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none">${ICONS[file.kind] ?? ICONS.other}</svg></span>
       <span class="pv-file-main">
         <span class="pv-file-name">${file.name}</span>
         <span class="pv-file-meta">
           <span>${config.levelLabels[permit.accessLevel]}</span>
           <span>·</span>
           <span class="tnum">${readableSize(file.size)}</span>
           <span class="pv-tier" data-tier="${permit.qualityTier}">${config.tierLabels[permit.qualityTier]}</span>
         </span>
       </span>`,
    );

    const actions = document.createElement("span");
    actions.className = "pv-file-actions";

    if (can(permit, "download")) {
      actions.append(
        actionButton("Download", "secondary", async () => {
          try {
            const served = await api.files.content(file.id);
            say(`${file.name} served at ${config.tierLabels[served.tier].toLowerCase()}.`);
          } catch (err) {
            say(err instanceof ApiError ? err.message : "Could not open that file.", "danger");
          }
        }),
      );
    }

    if (can(permit, "delete")) {
      actions.append(
        actionButton("Delete", "ghost", async () => {
          try {
            await api.files.remove(file.id);
            say(`${file.name} deleted.`);
            load();
          } catch (err) {
            say(err instanceof ApiError ? err.message : "Could not delete that file.", "danger");
          }
        }),
      );
    }

    row.append(actions);
    list.append(row);
  }

  state.hidden = true;
  list.hidden = false;
}

async function load() {
  try {
    const files = await api.files.list();
    if (files.length === 0) {
      state.textContent = "No files yet.";
      state.hidden = false;
      list.hidden = true;
      count.textContent = "0 files";
      return;
    }
    render(files);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      location.replace("login.html");
      return;
    }
    state.textContent = "Could not load your files.";
    state.hidden = false;
  }
}

document.querySelector("#signout").addEventListener("click", async () => {
  await api.auth.logout();
  location.replace("index.html");
});

try {
  const { user } = await api.auth.session();
  who.textContent = user.email;
  load();
} catch {
  location.replace("login.html");
}
