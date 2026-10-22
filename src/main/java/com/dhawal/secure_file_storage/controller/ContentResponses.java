package com.dhawal.secure_file_storage.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.dhawal.secure_file_storage.storage.ContentService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class ContentResponses {

    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    interface Opening {
        void run();
    }

    private ContentResponses() {
    }

    static void stream(HttpServletRequest request, HttpServletResponse response, ContentService content,
            ContentService.Delivery delivery, Opening onOpening) throws IOException {

        long size = delivery.rendition().size();
        long start = 0;
        long end = size - 1;
        boolean partial = false;

        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null) {
            Matcher m = RANGE.matcher(range.strip());
            if (m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
                if (m.group(1).isEmpty()) {
                    start = Math.max(0, size - Long.parseLong(m.group(2)));
                } else {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) {
                        end = Math.min(end, Long.parseLong(m.group(2)));
                    }
                }
                if (start > end || start >= size) {
                    response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                    return;
                }
                partial = true;
            }
        }

        if (start == 0) {
            onOpening.run();
        }

        headers(response, delivery);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader("X-Quality-Tier", delivery.rendition().tier().wire());
        if (partial) {
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        }
        response.setContentLengthLong(end - start + 1);
        content.write(delivery, start, end - start + 1, response.getOutputStream());
    }

    static void headers(HttpServletResponse response, ContentService.Delivery delivery) {
        response.setContentType(delivery.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.builder(delivery.inline() ? "inline" : "attachment")
                        .filename(delivery.file().name(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        if (!delivery.rendition().mime().equals("application/pdf")) {
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        }
    }
}
