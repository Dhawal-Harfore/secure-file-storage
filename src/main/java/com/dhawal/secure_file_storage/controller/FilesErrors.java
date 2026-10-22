package com.dhawal.secure_file_storage.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.dhawal.secure_file_storage.storage.ApiException;

@RestControllerAdvice(assignableTypes = { FilesController.class, SharesController.class })
public class FilesErrors {

    private static final Logger log = LoggerFactory.getLogger(FilesErrors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage(), e.reason());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_request", "The request body isn't valid JSON.", "");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unexpected error in the file service", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Something went wrong on our side.", "");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, String reason) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of("code", code, "message", message, "reason", reason)));
    }
}
