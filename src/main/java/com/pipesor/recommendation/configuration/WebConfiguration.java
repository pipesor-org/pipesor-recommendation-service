package com.pipesor.recommendation.configuration;

import com.pipesor.recommendation.shared.web.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class WebConfiguration {
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        var registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** CORS for the dashboard origin only, on human routes, without credentials mode. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${pipesor.dashboard-origin:}") String origin) {
        var source = new UrlBasedCorsConfigurationSource();
        if (origin.isBlank()) return source;
        var uri = URI.create(origin);
        if (uri.getScheme() == null || uri.getHost() == null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("pipesor.dashboard-origin must be a bare origin");
        }
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST"));
        // Idempotency-Key lets the dashboard retry a review submission safely (ReviewController).
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Correlation-ID"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofMinutes(10));
        source.registerCorsConfiguration("/api/v1/**", cors);
        return source;
    }
}
