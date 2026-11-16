package com.pipesor.recommendation.retrieval.infrastructure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.pipesor.recommendation.retrieval.application.ports.RagAccessTokenProvider;

import java.time.Duration;

public final class EntraRagAccessTokenProvider implements RagAccessTokenProvider {
    private final TokenCredential credential;
    private final String scope;
    private final Duration timeout;

    public EntraRagAccessTokenProvider(TokenCredential credential, RagClientProperties properties) {
        this.credential = credential;
        this.scope = properties.audience() + "/.default";
        this.timeout = properties.timeout();
    }

    @Override
    public String accessToken() {
        var token = credential.getToken(new TokenRequestContext().addScopes(scope))
                .map(accessToken -> accessToken.getToken())
                .block(timeout);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Entra returned no access token for the RAG audience");
        }
        return token;
    }
}
