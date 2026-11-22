package com.pipesor.recommendation.configuration;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.commandhandlers.DiagnoseAdmittedContextsHandler;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisEntitlements;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisReadModel;
import com.pipesor.recommendation.diagnosis.application.queryhandlers.GitHubActionsReportQueryHandler;
import com.pipesor.recommendation.diagnosis.application.queryhandlers.IncidentDiagnosisQueryHandler;
import com.pipesor.recommendation.diagnosis.application.queryhandlers.ReviewAccessHandler;
import com.pipesor.recommendation.diagnosis.infrastructure.AzureOpenAiInference;
import com.pipesor.recommendation.diagnosis.infrastructure.DiagnosisWorker;
import com.pipesor.recommendation.diagnosis.infrastructure.DiagnosisWorkerProperties;
import com.pipesor.recommendation.diagnosis.infrastructure.InferenceProperties;
import com.pipesor.recommendation.diagnosis.infrastructure.persistence.JdbcAdmittedContexts;
import com.pipesor.recommendation.diagnosis.infrastructure.persistence.JdbcDiagnosisJobs;
import com.pipesor.recommendation.diagnosis.infrastructure.persistence.JdbcDiagnosisReadModel;
import com.pipesor.recommendation.diagnosis.infrastructure.pipeline.PipelineClientProperties;
import com.pipesor.recommendation.diagnosis.infrastructure.pipeline.PipelineDiagnosisEntitlements;
import com.pipesor.recommendation.diagnosis.review.PersistedDiagnosticWorkflow;
import com.pipesor.recommendation.diagnosis.review.ReviewStore;
import com.pipesor.recommendation.receipt.ReceiptStore;
import com.pipesor.recommendation.receipt.interfaces.rest.ReceiptProperties;
import com.pipesor.recommendation.retrieval.application.ports.RagKnowledgeRetriever;
import com.pipesor.recommendation.shared.security.TransportPolicy;
import com.pipesor.recommendation.shared.security.WorkloadTokens;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Explicit composition of receipts, automatic diagnosis, inference and the human/report read side. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InferenceProperties.class, DiagnosisWorkerProperties.class, ReceiptProperties.class,
        PipelineClientProperties.class})
class DiagnosisConfiguration {

    @Bean
    ReceiptStore receiptStore(DataSource dataSource) {
        return new ReceiptStore(dataSource);
    }

    @Bean
    ReviewStore reviewStore(DataSource dataSource) {
        return new ReviewStore(dataSource);
    }

    @Bean
    TokenCredential inferenceCredential() {
        return new DefaultAzureCredentialBuilder().build();
    }

    @Bean
    DiagnosticOrchestrator.Inference inference(InferenceProperties properties, TokenCredential inferenceCredential) {
        return new AzureOpenAiInference(properties, inferenceCredential);
    }

    @Bean
    PersistedDiagnosticWorkflow persistedDiagnosticWorkflow(RagKnowledgeRetriever retrieval,
            DiagnosticOrchestrator.Inference inference, ReviewStore reviewStore) {
        return new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(retrieval, inference), reviewStore);
    }

    @Bean
    DiagnoseAdmittedContextsHandler diagnoseAdmittedContextsHandler(JdbcTemplate jdbc,
            PersistedDiagnosticWorkflow workflow, DiagnosisWorkerProperties worker) {
        return new DiagnoseAdmittedContextsHandler(new JdbcDiagnosisJobs(jdbc), new JdbcAdmittedContexts(jdbc), workflow,
                new DiagnoseAdmittedContextsHandler.Policy(worker.batchSize(), worker.lease(), worker.initialBackoff(),
                        worker.maxBackoff(), worker.maxAttempts()));
    }

    @Bean
    @ConditionalOnProperty(name = "pipesor.diagnosis-worker.enabled", havingValue = "true", matchIfMissing = true)
    DiagnosisWorker diagnosisWorker(DiagnoseAdmittedContextsHandler handler, DiagnosisWorkerProperties worker) {
        return new DiagnosisWorker(handler, worker.pollInterval(), worker.batchSize());
    }

    @Bean
    DiagnosisEntitlements diagnosisEntitlements(ServiceEndpoints endpoints, TransportPolicy transport,
            WorkloadTokens tokens, PipelineClientProperties pipeline) {
        return new PipelineDiagnosisEntitlements(
                transport.resolve(endpoints.pipeline(), PipelineDiagnosisEntitlements.PATH), tokens, pipeline.timeout());
    }

    @Bean
    DiagnosisReadModel diagnosisReadModel(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcDiagnosisReadModel(jdbc, transactionManager);
    }

    @Bean
    IncidentDiagnosisQueryHandler incidentDiagnosisQueryHandler(DiagnosisEntitlements entitlements,
            DiagnosisReadModel diagnoses) {
        return new IncidentDiagnosisQueryHandler(entitlements, diagnoses);
    }

    @Bean
    GitHubActionsReportQueryHandler gitHubActionsReportQueryHandler(DiagnosisReadModel diagnoses) {
        return new GitHubActionsReportQueryHandler(diagnoses);
    }

    @Bean
    ReviewAccessHandler reviewAccessHandler(ReviewStore reviewStore, DiagnosisEntitlements entitlements) {
        return new ReviewAccessHandler(reviewStore, entitlements);
    }
}
