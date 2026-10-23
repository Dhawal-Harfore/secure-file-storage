import { api, ApiError } from "../api/index.js";
import { config } from "../config.js";
import { h } from "../lib/dom.js";
import { ago } from "../lib/format.js";

const who = document.querySelector("#who");
const count = document.querySelector("#count");
const state = document.querySelector("#state");
const table = document.querySelector("#table");
const pager = document.querySelector("#pager");
const pageLabel = document.querySelector("#page-label");
const prev = document.querySelector("#prev");
const next = document.querySelector("#next");
const filters = document.querySelector("#filters");

const PAGE_SIZE = 25;
const ACTIONS = { upload: "Uploaded", view: "Opened", edit: "Renamed", share: "Shared", revoke: "Revoked", delete: "Deleted", download: "Downloaded" };
let page = 1;

function row(event) {
  const refused = event.result !== "permit";
  return h(
    "tr",
    {},
    h("td", { title: new Date(event.at).toLocaleString(), text: ago(event.at) }),
    h("td", { class: "pv-wrap", title: event.actorEmail ?? "", text: event.actorEmail ?? (event.shareId ? "Someone with a link" : "Unknown") }),
    h("td", { class: "pv-wrap", title: event.fileName ?? "", text: event.fileName ?? "" }),
    h("td", { text: ACTIONS[event.action] ?? event.action }),
    h(
      "td",
      {},
      h("span", { class: "pv-result" }, h("span", { class: "pv-dot", "data-tone": refused ? "danger" : "success", "aria-hidden": "true" }), refused ? "Refused" : "Allowed"),
    ),
    h("td", { text: event.qualityTier ? config.tierLabels[event.qualityTier] ?? event.qualityTier : "" }),
  );
}

async function load() {
  state.hidden = false;
  state.textContent = "Loading activity…";
  try {
    const { events, total, pageSize } = await api.audit.list({
      action: filters.elements.action.value,
      result: filters.elements.result.value,
      page,
      pageSize: PAGE_SIZE,
    });
    const pages = Math.max(1, Math.ceil(total / pageSize));
    count.textContent = `${total} event${total === 1 ? "" : "s"}`;

    if (events.length === 0) {
      table.hidden = true;
      pager.hidden = true;
      state.textContent = "Nothing has happened to your files yet.";
      return;
    }

    table.replaceChildren(
      h(
        "table",
        {},
        h("thead", {}, h("tr", {}, ["When", "Who", "File", "What", "Result", "Copy served"].map((t) => h("th", { scope: "col", text: t })))),
        h("tbody", {}, events.map(row)),
      ),
    );
    state.hidden = true;
    table.hidden = false;
    pager.hidden = pages <= 1;
    pageLabel.textContent = `Page ${page} of ${pages}`;
    prev.disabled = page <= 1;
    next.disabled = page >= pages;
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      location.replace("login.html");
      return;
    }
    table.hidden = true;
    state.textContent = err instanceof ApiError && err.message ? err.message : "Couldn't load the activity.";
  }
}

filters.addEventListener("change", () => {
  page = 1;
  load();
});
prev.addEventListener("click", () => {
  page = Math.max(1, page - 1);
  load();
});
next.addEventListener("click", () => {
  page += 1;
  load();
});

document.querySelector("#signout").addEventListener("click", async () => {
  try {
    await api.auth.logout();
  } finally {
    location.replace("index.html");
  }
});

try {
  const { user } = await api.auth.session();
  who.textContent = user.email;
  load();
} catch {
  location.replace("login.html");
}
