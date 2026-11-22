package com.pipesor.recommendation.receipt;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Approved Correlation → Recommendation v1 examples. */
public final class ReceiptFixtures {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ReceiptFixtures() { }

    public static JsonNode fixture(int index) throws Exception {
        try (var input = ReceiptFixtures.class.getResourceAsStream("/correlation-recommendation-v1.examples.json")) {
            return JSON.readTree(input.readAllBytes()).get("fixtures").get(index);
        }
    }
}
