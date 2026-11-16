package com.pipesor.recommendation.retrieval.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CorpusKind {
    REVIEWED_INCIDENTS("reviewed_incidents"),
    AUTHORIZED_TECHNICAL_DOCUMENTATION("authorized_technical_documentation"),
    AUTHORIZED_RESPONSE_PROCEDURES("authorized_response_procedures");

    private final String wireValue;

    CorpusKind(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
