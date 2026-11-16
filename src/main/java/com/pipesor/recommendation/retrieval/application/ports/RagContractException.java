package com.pipesor.recommendation.retrieval.application.ports;

public class RagContractException extends RuntimeException {
    public RagContractException(String message) {
        super(message);
    }

    public RagContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
