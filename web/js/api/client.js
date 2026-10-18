import { ApiError } from "./errors.js";
import { config } from "../config.js";

const base = config.apiBase;
const enc = encodeURIComponent;

function csrfToken() {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : null;
}

function withCsrf(method, headers = {}) {
    const token = method !== "GET" ? csrfToken() : null;
    return token ? { ...headers, "X-XSRF-TOKEN": token } : headers;
}

async function request(path, options = {}) {
    const method = options.method ?? "GET";
    let response;

    try {
        response = await fetch(`${base}${path}`, {
            credentials: "include",
            ...options,
            headers: withCsrf(method, options.headers),
        });
    } catch {
        throw new ApiError(0, "network", "Couldn't reach the server. Check your connection.");
    }

    let data = null;

    try {
        data = await response.json();
    } catch {
        // No JSON body
    }

    if (!response.ok) {
        const error = data?.error ?? {};
        throw new ApiError(
            response.status,
            error.code ?? "unknown",
            error.message ?? "Request failed.",
            error.reason ?? "",
        );
    }

    return data;
}

function json(method, body) {
    return {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    };
}

export const liveApi = {
    auth: {
        async requestMagicLink(email) {
            return request("/auth/magic-link", json("POST", { email }));
        },

        async verify(token) {
            return request("/auth/verify", json("POST", { token }));
        },

        async session() {
            return request("/auth/session");
        },

        async logout() {
            await request("/auth/logout", { method: "POST" });
        },
    },

    files: {
        async list() {
            return request("/files");
        },

        async get(id) {
            return request(`/files/${enc(id)}`);
        },

        async permit(id) {
            return request(`/files/${enc(id)}/permit`);
        },

        contentUrl(id, tier) {
            const query = tier ? `?tier=${enc(tier)}` : "";
            return `${base}/files/${enc(id)}/content${query}`;
        },

        thumbUrl(id) {
            return `${base}/files/${enc(id)}/thumb`;
        },

        async initiate({ name, mime, size }) {
            return request("/files/initiate", json("POST", { name, mime, size }));
        },

        async status(uploadId) {
            return request(`/files/${enc(uploadId)}`);
        },

        putChunk(uploadId, index, bytes, onProgress) {
            return new Promise((resolve, reject) => {
                const xhr = new XMLHttpRequest();
                xhr.open("PUT", `${base}/files/${enc(uploadId)}/chunk/${index}`);
                xhr.withCredentials = true;
                xhr.setRequestHeader("Content-Type", "application/octet-stream");
                const token = csrfToken();
                if (token) xhr.setRequestHeader("X-XSRF-TOKEN", token);
                xhr.upload.onprogress = (event) => onProgress?.(event.loaded);
                xhr.onload = () => {
                    let data = null;
                    try {
                        data = JSON.parse(xhr.responseText);
                    } catch {
                        // No JSON body
                    }
                    if (xhr.status >= 200 && xhr.status < 300) {
                        resolve(data);
                    } else {
                        const error = data?.error ?? {};
                        reject(new ApiError(xhr.status, error.code ?? "unknown", error.message ?? "Upload failed.", error.reason ?? ""));
                    }
                };
                xhr.onerror = () => reject(new ApiError(0, "network", "The connection dropped during the upload."));
                xhr.send(bytes);
            });
        },

        async complete(uploadId, sha256) {
            return request(`/files/${enc(uploadId)}/complete`, json("POST", { sha256 }));
        },

        async abort(uploadId) {
            await request(`/files/${enc(uploadId)}`, { method: "DELETE" });
        },

        async remove(id, { keepalive = false } = {}) {
            await request(`/files/${enc(id)}`, { method: "DELETE", keepalive });
        },

        async rename(id, name) {
            return request(`/files/${enc(id)}`, json("PATCH", { name }));
        },
    },

    shares: {
        async list(fileId) {
            return request(`/files/${enc(fileId)}/shares`);
        },

        async create(fileId, payload) {
            return request(`/files/${enc(fileId)}/shares`, json("POST", payload));
        },

        async revoke(id, { keepalive = false } = {}) {
            return request(`/shares/${enc(id)}/revoke`, { method: "POST", keepalive });
        },

        async publicGet(token) {
            return request(`/s/${enc(token)}`);
        },

        publicContentUrl(token, tier) {
            const query = tier ? `?tier=${enc(tier)}` : "";
            return `${base}/s/${enc(token)}/content${query}`;
        },
    },

    audit: {
        async list(params = {}) {
            const query = new URLSearchParams();

            for (const [key, value] of Object.entries(params)) {
                if (value !== undefined && value !== null && value !== "") {
                    query.set(key, value);
                }
            }

            const suffix = query.toString() ? `?${query}` : "";
            return request(`/audit${suffix}`);
        },
    },
};
