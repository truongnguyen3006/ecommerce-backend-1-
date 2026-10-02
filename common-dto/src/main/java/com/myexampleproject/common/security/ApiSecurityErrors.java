package com.myexampleproject.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.dto.ErrorResponse;
import jakarta.servlet.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import java.io.IOException;
import java.time.Instant;

public final class ApiSecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper = new ObjectMapper();
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        res.setHeader("WWW-Authenticate", "Bearer");
        write(req, res, 401, "UNAUTHORIZED", "Authentication required");
    }
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) throws IOException {
        write(req, res, 403, "FORBIDDEN", "Access denied");
    }
    private void write(HttpServletRequest req, HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);res.setContentType("application/json");
        mapper.writeValue(res.getOutputStream(), ErrorResponse.builder().timestamp(Instant.now().toString())
                .status(status).code(code).message(message).path(req.getRequestURI()).build());
    }
}
