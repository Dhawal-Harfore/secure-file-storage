import { config } from "../config.js";
import { mockApi } from "./mock.js";

export const api =
  config.apiSource === "live" ? (await import("./client.js")).liveApi : mockApi;

export { ApiError } from "./errors.js";
export { can } from "./mock-policy.js";
