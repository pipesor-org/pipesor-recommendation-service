package com.pipesor.recommendation.diagnosis.application.queryhandlers;

import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisProjection;

/** Markdown artifact for the failed run; separates inferred, observed and retrieved content. */
final class ReportMarkdown {
    private ReportMarkdown() { }

    static String render(DiagnosisProjection report) {
        var out = new StringBuilder();
        out.append("# Pipesor diagnosis\n\n");
        out.append("- Incident: `").append(report.incidentId()).append("`\n");
        out.append("- Execution: `").append(report.executionId()).append("`\n");
        out.append("- Diagnosis: `").append(report.diagnosisId()).append("` version ").append(report.version()).append('\n');
        out.append("- Correlation ID: `").append(report.correlationId()).append("`\n");
        out.append("- Review status: ").append(report.status()).append("\n\n");
        var hypothesis = report.hypothesis();
        out.append("## Probable cause (inferred, not verified)\n\n").append(quote(hypothesis.probableCause())).append('\n');
        out.append("## Recommended manual check (inferred)\n\n").append(quote(hypothesis.recommendation())).append('\n');
        out.append("## Limitations\n\n").append(quote(hypothesis.limitations())).append('\n');
        out.append("## Observed evidence (sanitized)\n\n");
        for (var evidence : report.evidence()) {
            out.append("- `").append(evidence.evidenceId()).append("` stage ").append(evidence.stage());
            if (evidence.sourceLocator() != null) out.append(", source `").append(evidence.sourceLocator()).append('`');
            out.append('\n');
            String fence = fence(evidence.content());
            out.append('\n').append(fence).append("text\n").append(evidence.content()).append('\n').append(fence).append("\n\n");
        }
        out.append("## Retrieved knowledge\n\n");
        if (report.sources().isEmpty()) out.append("No knowledge source was cited.\n");
        for (var source : report.sources()) {
            out.append("- ").append(escape(source.provenance())).append(" (chunk `").append(source.chunkId()).append("`)\n");
        }
        if (report.model() != null) {
            out.append("\n_Model: ").append(escape(report.model().deployment()));
            if (report.model().modelVersion() != null) out.append(" (").append(escape(report.model().modelVersion())).append(')');
            out.append("._\n");
        }
        out.append("\nThis is an assisted hypothesis that requires human review. It does not change any workflow.\n");
        return out.toString();
    }

    private static String quote(String text) {
        var out = new StringBuilder();
        for (String line : escape(text).split("\\R", -1)) out.append("> ").append(line).append('\n');
        return out.toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String fence(String content) {
        int longest = 0, current = 0;
        for (int i = 0; i < content.length(); i++) {
            current = content.charAt(i) == '`' ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return "`".repeat(Math.max(3, longest + 1));
    }
}
