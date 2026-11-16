package com.pipesor.recommendation.retrieval.application.ports;

public class RagUnavailableException extends RuntimeException {
    public RagUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
