package com.pipesor.recommendation.shared.web;

/** A public outcome with a stable code; carries no internal message. */
public final class ApiProblem extends RuntimeException {
    private final int status;
    private final String code;

    public ApiProblem(int status, String code) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }

    public String code() { return code; }
}
