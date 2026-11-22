package com.pipesor.recommendation.configuration;

import com.pipesor.recommendation.retrieval.application.ports.RagKnowledgeRetriever;
import com.pipesor.recommendation.retrieval.infrastructure.RagClientProperties;
import com.pipesor.recommendation.retrieval.infrastructure.RestRagKnowledgeRetriever;
import com.pipesor.recommendation.retrieval.infrastructure.WorkloadRagAccessTokenProvider;
import com.pipesor.recommendation.shared.security.TransportPolicy;
import com.pipesor.recommendation.shared.security.WorkloadTokens;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RagClientProperties.class, ServiceEndpoints.class})
class RagClientConfiguration {
    @Bean
    RagKnowledgeRetriever ragKnowledgeRetriever(RagClientProperties properties, ServiceEndpoints endpoints,
                                                TransportPolicy transport, WorkloadTokens tokens) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        var perAttemptTimeout = properties.timeout().dividedBy(RestRagKnowledgeRetriever.MAX_RETRIES + 1L);
        requestFactory.setConnectTimeout(perAttemptTimeout);
        requestFactory.setReadTimeout(perAttemptTimeout);
        var builder = RestClient.builder().requestFactory(requestFactory);
        return new RestRagKnowledgeRetriever(builder, transport.require(endpoints.rag()), properties,
                new WorkloadRagAccessTokenProvider(tokens));
    }
}
