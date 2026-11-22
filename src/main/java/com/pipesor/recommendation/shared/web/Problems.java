package com.pipesor.recommendation.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code application/problem+json} bodies with {@code type, title, status, code, correlationId}; never internal messages. */
public final class Problems {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Problems() { }

    public static ResponseEntity<ProblemDetail> entity(int status, String code, HttpServletRequest request) {
        var builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == 401) builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return builder.body(detail(status, code, request));
    }

    public static ProblemDetail detail(int status, String code, HttpServletRequest request) {
        var detail = ProblemDetail.forStatus(status);
        detail.setType(URI.create("urn:pipesor:problem:" + code));
        detail.setTitle(code.replace('_', ' '));
        detail.setProperty("code", code);
        detail.setProperty("correlationId", request == null ? null : CorrelationIdFilter.current(request));
        return detail;
    }

    /** Writes a problem directly (security entry points run outside the MVC converters). */
    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:pipesor:problem:" + code);
        body.put("title", code.replace('_', ' '));
        body.put("status", status);
        body.put("code", code);
        body.put("correlationId", CorrelationIdFilter.current(request));
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 401) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }
}
