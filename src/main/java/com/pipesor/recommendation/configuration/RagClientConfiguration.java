package com.pipesor.recommendation.configuration;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.pipesor.recommendation.retrieval.application.ports.RagAccessTokenProvider;
import com.pipesor.recommendation.retrieval.application.ports.RagKnowledgeRetriever;
import com.pipesor.recommendation.retrieval.infrastructure.EntraRagAccessTokenProvider;
import com.pipesor.recommendation.retrieval.infrastructure.RagClientProperties;
import com.pipesor.recommendation.retrieval.infrastructure.RestRagKnowledgeRetriever;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagClientProperties.class)
class RagClientConfiguration {
    @Bean
    TokenCredential ragTokenCredential() {
        return new DefaultAzureCredentialBuilder().build();
    }

    @Bean
    RagAccessTokenProvider ragAccessTokenProvider(TokenCredential credential, RagClientProperties properties) {
        return new EntraRagAccessTokenProvider(credential, properties);
    }

    @Bean
    RagKnowledgeRetriever ragKnowledgeRetriever(RagClientProperties properties,
                                                RagAccessTokenProvider tokenProvider) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        var perAttemptTimeout = properties.timeout().dividedBy(RestRagKnowledgeRetriever.MAX_RETRIES + 1L);
        requestFactory.setConnectTimeout(perAttemptTimeout);
        requestFactory.setReadTimeout(perAttemptTimeout);
        var builder = RestClient.builder().requestFactory(requestFactory);
        return new RestRagKnowledgeRetriever(builder, properties, tokenProvider);
    }
}
