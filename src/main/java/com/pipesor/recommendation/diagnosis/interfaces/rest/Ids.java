package com.pipesor.recommendation.diagnosis.interfaces.rest;

import com.pipesor.recommendation.shared.web.ApiProblem;

import java.util.UUID;

final class Ids {
    private Ids() { }

    /** Canonical lowercase UUID path value; anything else is a malformed request. */
    static UUID uuid(String value) {
        try {
            var id = UUID.fromString(value);
            if (id.toString().equals(value)) return id;
        } catch (IllegalArgumentException invalid) {
            // Reported as a malformed request below.
        }
        throw new ApiProblem(400, "invalid_request");
    }
}
