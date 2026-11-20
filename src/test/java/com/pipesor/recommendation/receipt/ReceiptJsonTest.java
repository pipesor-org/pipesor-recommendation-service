package com.pipesor.recommendation.receipt;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptJsonTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void preservesAggregateLocatorAndRejectsMalformedProvenance() throws Exception {
        var fixture = ReceiptEndpointTest.fixture(0);
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(
                fixture.get("proposed_canonical_json").asString());
        var signal = (tools.jackson.databind.node.ObjectNode) body.withArray("signals").get(0);
        String locator = "v2:job:701:member:0_verify.txt:lines:2-2:sha256:" + "a".repeat(64);
        signal.put("source_locator", locator);
        var decoded = ReceiptJson.decode(JSON.writeValueAsBytes(body),
                fixture.get("headers").get("Idempotency-Key").asString(),
                fixture.get("headers").get("X-Correlation-ID").asString());
        assertThat(decoded.signalsJson()).contains(locator);
        signal.put("source_locator", locator.replace("lines:2-2", "lines:2-3"));
        assertThatThrownBy(() -> ReceiptJson.decode(JSON.writeValueAsBytes(body),
                fixture.get("headers").get("Idempotency-Key").asString(),
                fixture.get("headers").get("X-Correlation-ID").asString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
