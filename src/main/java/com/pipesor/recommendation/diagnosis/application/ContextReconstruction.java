package com.pipesor.recommendation.diagnosis.application;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.diagnosis.application.model.AdmittedContext;
import com.pipesor.recommendation.diagnosis.application.model.DiagnosticInput;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rebuilds the diagnostic inputs from an admitted receipt only (no cross-schema reads).
 *
 * <p>Rules (see docs/diagnosis-worker.md):
 * <ul>
 *   <li>Evidence: every admitted signal, in delivery order, with its stage, sanitized content and
 *       source locator.</li>
 *   <li>Retrieval query: the content of the single signal; for several signals, their contents in
 *       delivery order separated by a blank line, cut at {@value #MAX_QUERY_CHARS} characters. The
 *       ISO-8601 timestamp that GitHub Actions prefixes to every log line is removed from the query
 *       (not from the evidence): it carries no diagnostic meaning and dilutes the embedding.</li>
 *   <li>Corpus: authorized technical documentation only; {@code topK} = {@value #TOP_K}.</li>
 *   <li>Scope and correlation id: the receipt's authorization scope and correlation id.</li>
 * </ul>
 * The query text, corpus and {@code topK} were selected on the evaluation's dev split (protocol
 * section 6.1, pipesor-validation-scenarios results/2026-11-23-dev-retrieval-variants). They are part of
 * the admission fingerprint, so a context admitted with the previous rules conflicts if it is delivered
 * again (see docs/diagnosis-worker.md).
 */
public final class ContextReconstruction {
    static final int MAX_QUERY_CHARS = 4000;
    static final int TOP_K = 3;
    private static final Pattern LOG_LINE_TIMESTAMP =
            Pattern.compile("(?m)^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z ?");
    private static final List<CorpusKind> CORPUS = List.of(CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION);

    private ContextReconstruction() { }

    public static DiagnosticInput reconstruct(AdmittedContext context) {
        var signals = context.signals();
        if (signals.isEmpty()) throw new InvalidContext("admitted context has no signals");
        if (!signals.getLast().originContextId().equals(context.contextId())) {
            throw new InvalidContext("admitted context does not end with its own origin signal");
        }
        var evidence = signals.stream().map(signal -> new Evidence(signal.evidenceId(), signal.originContextId(),
                signal.stage(), signal.content(), signal.sourceLocator())).toList();
        String text = signals.size() == 1 ? queryText(signals.getFirst().content())
                : String.join("\n\n", signals.stream().map(signal -> queryText(signal.content())).toList());
        try {
            var query = new RetrievalQuery(context.correlationId(), truncate(text), context.scope(), CORPUS, TOP_K);
            return new DiagnosticInput(context.contextId(), context.executionId(), context.incidentId(), query, evidence);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidContext("admitted context cannot form a retrieval query");
        }
    }

    /** The signal content without the GitHub Actions timestamp at the start of each line. */
    static String queryText(String content) {
        return LOG_LINE_TIMESTAMP.matcher(content).replaceAll("");
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_QUERY_CHARS) return text;
        int end = MAX_QUERY_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /** The admitted receipt cannot be turned into diagnostic inputs; retrying cannot help. */
    public static final class InvalidContext extends RuntimeException {
        public InvalidContext(String message) { super(message); }
    }
}
