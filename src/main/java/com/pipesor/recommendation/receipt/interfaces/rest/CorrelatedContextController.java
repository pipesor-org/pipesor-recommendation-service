package com.pipesor.recommendation.receipt.interfaces.rest;

import com.pipesor.recommendation.receipt.ReceiptJson;
import com.pipesor.recommendation.receipt.ReceiptStore;
import com.pipesor.recommendation.receipt.ReceiptSubmission;
import com.pipesor.recommendation.shared.web.CorrelationIdFilter;
import com.pipesor.recommendation.shared.web.Problems;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Correlation → Recommendation context delivery v1. Caller and role are enforced by the platform
 * filter chain; this adapter validates the strict wire contract and commits the receipt before
 * answering {@code accepted} or {@code duplicate}.
 */
@RestController
public class CorrelatedContextController {
    public static final String PATH = "/internal/v1/correlated-contexts";
    private static final Logger LOG = LoggerFactory.getLogger(CorrelatedContextController.class);
    private final ReceiptStore store;
    private final int maxRequestBytes;

    public CorrelatedContextController(ReceiptStore store, ReceiptProperties limits) {
        this.store = Objects.requireNonNull(store);
        this.maxRequestBytes = limits.maxRequestBytes();
    }

    @PostMapping(PATH)
    public ResponseEntity<?> receive(HttpServletRequest request) {
        ReceiptSubmission submission;
        try {
            var contentType = request.getContentType() == null ? null : MediaType.parseMediaType(request.getContentType());
            if (contentType == null || !contentType.getType().equalsIgnoreCase("application")
                    || !contentType.getSubtype().equalsIgnoreCase("json")
                    || (contentType.getCharset() != null && !StandardCharsets.UTF_8.equals(contentType.getCharset()))) {
                return Problems.entity(400, "invalid_context", request);
            }
            if (request.getContentLengthLong() > maxRequestBytes) return Problems.entity(413, "payload_too_large", request);
            byte[] body = request.getInputStream().readNBytes(maxRequestBytes + 1);
            if (body.length > maxRequestBytes) return Problems.entity(413, "payload_too_large", request);
            submission = ReceiptJson.decode(body, single(request, "Idempotency-Key"), single(request, "X-Correlation-ID"));
        } catch (IllegalArgumentException | IOException failure) {
            return Problems.entity(400, "invalid_context", request);
        }
        try {
            boolean inserted = store.accept(submission);
            String id = submission.operationId().toString();
            Map<String, String> receipt = new LinkedHashMap<>();
            receipt.put("contract_version", "1.0");
            receipt.put("operation_id", id);
            receipt.put("context_id", id);
            receipt.put("status", inserted ? "accepted" : "duplicate");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                    .body(receipt);
        } catch (ReceiptStore.Conflict failure) {
            return Problems.entity(409, "operation_conflict", request);
        } catch (ReceiptStore.Unavailable failure) {
            LOG.warn("receipt_unavailable exception={} correlationId={}", failure.getClass().getName(),
                    CorrelationIdFilter.current(request));
            return Problems.entity(503, "temporarily_unavailable", request);
        }
    }

    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }
}
