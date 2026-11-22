package com.pipesor.recommendation.receipt;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptJsonTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DIGEST = "a".repeat(64);

    @Test
    void preservesAggregateLocatorsOfEvidence21And22AndRejectsMalformedProvenance() throws Exception {
        var fixture = ReceiptFixtures.fixture(0);
        var body = (ObjectNode) JSON.readTree(fixture.get("proposed_canonical_json").asString());
        var signal = (ObjectNode) body.withArray("signals").get(0);
        for (String locator : new String[] {
                "v2:job:701:member:0_verify.txt:lines:2-2:sha256:" + DIGEST,
                "v2:job:701:member:build%2F3_Run%20mvn%20verify.txt:lines:40-52:sha256:" + DIGEST}) {
            signal.put("source_locator", locator);
            assertThat(decode(fixture, body).signalsJson()).contains(locator);
        }
        signal.put("source_locator", "v2:job:701:member:0_verify.txt:lines:3-2:sha256:" + DIGEST);
        assertThatThrownBy(() -> decode(fixture, body)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ReceiptSubmission decode(tools.jackson.databind.JsonNode fixture, ObjectNode body) {
        return ReceiptJson.decode(JSON.writeValueAsBytes(body),
                fixture.get("headers").get("Idempotency-Key").asString(),
                fixture.get("headers").get("X-Correlation-ID").asString());
    }
}
