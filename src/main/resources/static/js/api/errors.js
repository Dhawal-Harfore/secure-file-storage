export class ApiError extends Error {
  constructor(status, code, message, reason = "") {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.reason = reason;
  }
}