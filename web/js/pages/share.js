import { api, ApiError, can } from "../api/index.js";
import { config } from "../config.js";
import { h, icon } from "../lib/dom.js";
import { readableSize, ago, servedTier } from "../lib/format.js";

const main = document.querySelector("#main");
const state = document.querySelector("#state");

function fail(title, text) {
  document.title = `${title} · Vault`;
  state.replaceChildren(
    h(
      "div",
      { class: "hos-empty-state" },
      h("div", { class: "hos-empty-state__icon" }, icon("link", 24)),
      h("div", { class: "hos-empty-state__text" }, h("p", { class: "hos-empty-state__title", text: title }), h("p", { class: "hos-empty-state__description", text })),
    ),
  );
}

function media(file, token, served) {
  const url = api.shares.publicContentUrl(token, served);
  if (!url || served === "metadata") return null;
  if (file.kind === "image" || (file.kind === "pdf" && served === "lowest")) {
    return h("img", { class: "pv-preview-media", src: url, alt: file.name });
  }
  if (file.kind === "video") {
    return h("video", { class: "pv-preview-media", src: url, controls: true, preload: "metadata", playsinline: true });
  }
  if (file.kind === "pdf") {
    return h(
      "a",
      { class: "hos-button", href: url, target: "_blank", rel: "noopener noreferrer", "data-variant": "secondary", "data-size": "md", "data-hos-focus": true },
      h("span", { class: "hos-button__label", text: "Open the PDF" }),
    );
  }
  return null;
}

async function show() {
  const token = decodeURIComponent(location.hash.slice(1));
  if (!/^[A-Za-z0-9_-]{8,128}$/.test(token)) {
    fail("This link is incomplete", "Ask the person who shared it to send the full link again.");
    return;
  }

  let shared;
  try {
    shared = await api.shares.publicGet(token);
  } catch (err) {
    if (err instanceof ApiError && err.status === 410) {
      fail("This link has ended", err.message || "The owner revoked it, it expired, or it ran out of views.");
    } else if (err instanceof ApiError && err.status === 404) {
      fail("This link doesn't exist", "Check that you copied all of it.");
    } else {
      fail("Couldn't open the link", err instanceof ApiError && err.message ? err.message : "Try again in a moment.");
    }
    return;
  }

  const { file, permit } = shared;
  const served = servedTier({ ...file, permit });
  document.title = `${file.name} · Vault`;

  const notes = [`Shared with you as ${config.levelLabels[permit.accessLevel] ?? permit.accessLevel}`];
  if (permit.expiresAt) notes.push(`link ends ${ago(permit.expiresAt)}`);
  if (permit.maxViews != null) notes.push(`${Math.max(0, permit.maxViews - permit.viewsUsed)} views left`);

  const preview = media(file, token, served);
  const actions = [];
  if (can(permit, "download") && served !== "metadata") {
    const url = api.shares.publicContentUrl(token, served);
    if (url) {
      actions.push(
        h(
          "a",
          { class: "hos-button", href: url, download: file.name, "data-variant": "primary", "data-size": "md", "data-hos-focus": true },
          h("span", { class: "hos-button__icon" }, icon("download", 16)),
          h("span", { class: "hos-button__label", text: "Download" }),
        ),
      );
    }
  }

  main.replaceChildren(
    h(
      "article",
      { class: "pv-shared" },
      h(
        "header",
        { class: "pv-shared-head" },
        h("span", { class: "pv-file-icon", "data-kind": file.kind }, icon(file.kind, 22)),
        h("div", { class: "pv-shared-title" }, h("h1", { text: file.name }), h("p", { class: "pv-file-meta", text: `${readableSize(file.size)} · ${config.tierLabels[served]} copy` })),
      ),
      preview ?? h("p", { class: "pv-note", text: served === "metadata" ? "This link shows the file's details only." : "There's no preview of this file yet." }),
      h("p", { class: "pv-note", text: notes.join(" · ") }),
      actions.length ? h("div", { class: "pv-shared-actions" }, actions) : null,
    ),
  );
}

addEventListener("hashchange", show);
show();
