import { ApiError } from "./errors.js";
import { config } from "../config.js";

const base = config.apiBase;

async function request(path, options = {}) {
    const response = await fetch(`${base}${path}`, {
        credentials: "include",
        ...options,
    });

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

export const liveApi = {
    auth: {
        async requestMagicLink(email) {
            return request("/auth/magic-link", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ email }),
            });
        },

        async verify(token) {
            return request("/auth/verify", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ token }),
            });
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
            return request(`/files/${encodeURIComponent(id)}`);
        },

        async permit(id) {
            return request(`/files/${encodeURIComponent(id)}/permit`);
        },

        async content(id, tier) {
            const query = tier ? `?tier=${encodeURIComponent(tier)}` : "";
            return request(`/files/${encodeURIComponent(id)}/content${query}`);
        },

        async initiate({ name, mime, size }) {
            return request("/files/initiate", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name, mime, size }),
            });
        },

        async putChunk(uploadId, index, bytes) {
            return request(
                `/files/${encodeURIComponent(uploadId)}/chunk/${index}`,
                {
                    method: "PUT",
                    body: bytes,
                },
            );
        },

        async complete(uploadId, sha256) {
            return request(`/files/${encodeURIComponent(uploadId)}/complete`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ sha256 }),
            });
        },

        async abort(uploadId) {
            await request(`/files/${encodeURIComponent(uploadId)}`, {
                method: "DELETE",
            });
        },

        async remove(id) {
            await request(`/files/${encodeURIComponent(id)}`, {
                method: "DELETE",
            });
        },

        async rename(id, name) {
            return request(`/files/${encodeURIComponent(id)}`, {
                method: "PATCH",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name }),
            });
        },
    },

    shares: {
        async list(fileId) {
            return request(`/files/${encodeURIComponent(fileId)}/shares`);
        },

        async create(fileId, payload) {
            return request(`/files/${encodeURIComponent(fileId)}/shares`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload),
            });
        },

        async revoke(id) {
            return request(`/shares/${encodeURIComponent(id)}/revoke`, {
                method: "POST",
            });
        },

        async publicGet(token) {
            return request(`/s/${encodeURIComponent(token)}`);
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