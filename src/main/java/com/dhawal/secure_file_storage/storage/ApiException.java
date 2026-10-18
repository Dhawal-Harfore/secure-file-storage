package com.dhawal.secure_file_storage.storage;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String reason;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, "");
    }

    public ApiException(HttpStatus status, String code, String message, String reason) {
        super(message);
        this.status = status;
        this.code = code;
        this.reason = reason == null ? "" : reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String reason() {
        return reason;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
