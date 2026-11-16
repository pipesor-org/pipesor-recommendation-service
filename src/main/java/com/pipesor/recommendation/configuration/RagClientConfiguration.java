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
    RagKnowledgeRetriever ragKnowledgeRetriever(RestClient.Builder builder, RagClientProperties properties,
                                                RagAccessTokenProvider tokenProvider) {
        return new RestRagKnowledgeRetriever(builder, properties, tokenProvider);
    }
}
