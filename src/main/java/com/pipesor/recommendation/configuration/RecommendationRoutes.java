package com.pipesor.recommendation.configuration;

import com.pipesor.recommendation.diagnosis.interfaces.rest.GitHubActionsReportController;
import com.pipesor.recommendation.diagnosis.interfaces.rest.IncidentDiagnosisController;
import com.pipesor.recommendation.diagnosis.interfaces.rest.ReviewController;
import com.pipesor.recommendation.receipt.interfaces.rest.CorrelatedContextController;
import com.pipesor.recommendation.shared.security.PlatformAuthorities;
import com.pipesor.recommendation.shared.security.PlatformSecurityConfiguration.PlatformRoutes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;

/** Route catalog of this service (identity contract v1 §2, MVP HTTP API v1); everything else is denied. */
@Configuration(proxyBeanMethods = false)
public class RecommendationRoutes {
    @Bean
    PlatformRoutes recommendationRouteRules(Environment environment) {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        return routes -> {
            routes.requestMatchers(HttpMethod.POST, CorrelatedContextController.PATH)
                    .access(PlatformAuthorities.workload("correlation", "Correlation.Contexts.Deliver"));
            routes.requestMatchers(HttpMethod.GET, IncidentDiagnosisController.PATH)
                    .access(PlatformAuthorities.session("Diagnosis.Read"));
            routes.requestMatchers(HttpMethod.GET, ReviewController.PATH)
                    .access(PlatformAuthorities.session("Diagnosis.Read"));
            routes.requestMatchers(HttpMethod.POST, ReviewController.PATH)
                    .access(PlatformAuthorities.session("Diagnosis.Review"));
            routes.requestMatchers(HttpMethod.GET, GitHubActionsReportController.PATH)
                    .access(PlatformAuthorities.gitHubActions());
            // CORS preflight for the dashboard's human routes; the CORS filter answers it.
            routes.requestMatchers(HttpMethod.OPTIONS, "/api/v1/**").permitAll();
            if (local) routes.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**").permitAll();
        };
    }
}
