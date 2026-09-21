import { api, ApiError } from "../api/index.js";
import { config } from "../config.js";

const request = document.querySelector('[data-step="request"]');
const sent = document.querySelector('[data-step="sent"]');
const form = document.querySelector("#request-form");
const email = document.querySelector("#email");
const submit = document.querySelector("#submit");
const errorBox = document.querySelector("#error");
const sentTo = document.querySelector("#sent-to");
const devlink = document.querySelector("#devlink");

function showError(message) {
  errorBox.textContent = message;
  errorBox.hidden = !message;
}

function setBusy(busy) {
  submit.disabled = busy;
  submit.dataset.loading = busy ? "true" : "false";
  submit.querySelector(".hos-button__label").textContent = busy ? "Sending…" : "Send me a link";
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  showError("");

  const address = email.value.trim();
  if (!address || !address.includes("@")) {
    showError("Enter the email address your account uses.");
    email.focus();
    return;
  }

  setBusy(true);
  try {
    const { devToken } = await api.auth.requestMagicLink(address);
    sentTo.textContent = address;

    if (config.apiSource === "mock" && devToken) {
      const href = `unlock.html?token=${devToken}`;
      devlink.innerHTML = `No email is sent in mock mode. <a href="${href}">Open the sign-in link</a>`;
      devlink.hidden = false;
    }

    request.hidden = true;
    sent.hidden = false;
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not send the link. Try again.");
  } finally {
    setBusy(false);
  }
});

document.querySelector("#restart").addEventListener("click", () => {
  sent.hidden = true;
  request.hidden = false;
  devlink.hidden = true;
  email.value = "";
  email.focus();
});
