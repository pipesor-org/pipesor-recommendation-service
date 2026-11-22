package com.pipesor.recommendation.receipt;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Approved strict wire representation; input bytes must already be capacity-bounded. */
public final class ReceiptJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII, JsonWriteFeature.ESCAPE_FORWARD_SLASHES,
                    JsonWriteFeature.WRITE_HEX_UPPER_CASE)
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8).build();
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final Set<String> STAGES = Set.of("BUILD", "TESTING", "QUALITY_GATE", "DEPLOYMENT", "CONTAINER", "INFRASTRUCTURE");

    private ReceiptJson() { }

    public static ReceiptSubmission decode(byte[] bytes, String operationHeader, String traceHeader) {
        try {
            String input = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (input.startsWith("\ufeff")) throw invalid();
            JsonNode body = JSON.readTree(input);
            fields(body, "contract_version", "operation_id", "authorization_scope", "context", "signals");
            if (!string(body, "contract_version").equals("1.0")) throw invalid();
            UUID operation = id(body, "operation_id");
            if (!operation.toString().equals(operationHeader)) throw invalid();
            JsonNode scope = body.get("authorization_scope");
            fields(scope, "tenant_id", "github_installation_id", "github_repository_id", "pipeline_id", "scope_revision");
            UUID tenant = id(scope, "tenant_id");
            String installation = external(string(scope, "github_installation_id"));
            String repository = external(string(scope, "github_repository_id"));
            UUID pipeline = scope.get("pipeline_id").isNull() ? null : id(scope, "pipeline_id");
            String scopeRevision = safe(string(scope, "scope_revision"));
            JsonNode context = body.get("context");
            fields(context, "id", "incident_id", "execution_id", "evidence_ids", "summary", "correlation_id", "created_at");
            if (!operation.equals(id(context, "id"))) throw invalid();
            UUID incident = id(context, "incident_id");
            UUID execution = id(context, "execution_id");
            String summary = protectedText(string(context, "summary"));
            String trace = safe(string(context, "correlation_id"));
            if (!trace.equals(traceHeader)) throw invalid();
            String timestamp = string(context, "created_at");
            if (!timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z")) throw invalid();
            Instant created = Instant.parse(timestamp);
            if (!TIME.format(created).equals(timestamp)) throw invalid();
            JsonNode members = context.get("evidence_ids");
            if (!members.isArray() || members.isEmpty()) throw invalid();
            var evidence = new ArrayList<UUID>();
            String previous = null;
            for (JsonNode member : members) {
                UUID value = uuid(string(member));
                if (previous != null && previous.compareTo(value.toString()) >= 0) throw invalid();
                evidence.add(value);
                previous = value.toString();
            }
            JsonNode signals = body.get("signals");
            if (!signals.isArray() || signals.size() != evidence.size()) throw invalid();
            var ids = new HashSet<UUID>();
            var origins = new HashSet<UUID>();
            UUID lastOrigin = null;
            String lastTrace = null;
            for (JsonNode signal : signals) {
                fieldsWithOptionalLocator(signal);
                if (!ids.add(id(signal, "evidence_id"))) throw invalid();
                lastOrigin = id(signal, "origin_context_id");
                if (!origins.add(lastOrigin)) throw invalid();
                lastTrace = safe(string(signal, "correlation_id"));
                if (!STAGES.contains(string(signal, "stage"))) throw invalid();
                protectedText(string(signal, "summary"));
                protectedText(string(signal, "content"));
                if (signal.has("source_locator") && !SourceLocators.valid(string(signal, "source_locator"))) {
                    throw invalid();
                }
            }
            if (!ids.equals(new HashSet<>(evidence)) || !operation.equals(lastOrigin) || !trace.equals(lastTrace)) throw invalid();
            byte[] canonical = JSON.writeValueAsBytes(sorted(body));
            String fingerprint = "v1:sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            return new ReceiptSubmission(operation, tenant, installation, repository, pipeline, scopeRevision,
                    incident, execution, evidence, summary, trace, created,
                    JSON.writeValueAsString(sorted(signals)), fingerprint);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static Object sorted(JsonNode value) {
        if (value.isObject()) {
            var result = new TreeMap<String, Object>();
            for (String key : value.propertyNames()) result.put(key, sorted(value.get(key)));
            return result;
        }
        if (value.isArray()) {
            var result = new ArrayList<>();
            for (JsonNode child : value) result.add(sorted(child));
            return result;
        }
        if (value.isNull()) return null;
        return string(value);
    }

    private static void fields(JsonNode value, String... expected) {
        if (value == null || !value.isObject() || !Set.copyOf(value.propertyNames()).equals(Set.of(expected))) throw invalid();
    }
    private static void fieldsWithOptionalLocator(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        var actual = Set.copyOf(value.propertyNames());
        var base = Set.of("evidence_id", "origin_context_id", "correlation_id", "stage", "summary", "content");
        if (!actual.equals(base) && !actual.equals(Set.of("evidence_id", "origin_context_id", "correlation_id",
                "stage", "summary", "content", "source_locator"))) throw invalid();
    }
    private static String string(JsonNode object, String name) { return string(object.get(name)); }
    private static String string(JsonNode value) {
        if (value == null || !value.isString()) throw invalid();
        return value.asString();
    }
    private static UUID id(JsonNode object, String name) { return uuid(string(object, name)); }
    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw invalid();
        return id;
    }
    private static String safe(String value) {
        if (!value.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
        return value;
    }
    private static String external(String value) {
        if (!value.matches("[1-9][0-9]{0,31}")) throw invalid();
        return value;
    }
    private static String protectedText(String value) {
        if (value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85)) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw invalid();
            if (Character.isHighSurrogate(c) && (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))) throw invalid();
        }
        return value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid correlated context"); }
}
