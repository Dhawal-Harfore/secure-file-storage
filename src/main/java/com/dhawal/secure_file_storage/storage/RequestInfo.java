package com.dhawal.secure_file_storage.storage;

import jakarta.servlet.http.HttpServletRequest;

public record RequestInfo(String ip, String userAgent) {

    public static RequestInfo of(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent != null && agent.length() > 512) {
            agent = agent.substring(0, 512);
        }
        return new RequestInfo(request.getRemoteAddr(), agent);
    }
}
