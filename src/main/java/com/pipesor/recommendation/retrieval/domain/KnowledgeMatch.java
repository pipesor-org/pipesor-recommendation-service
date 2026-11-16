package com.pipesor.recommendation.retrieval.domain;

import java.util.UUID;

public record KnowledgeMatch(
        UUID chunkId,
        UUID documentId,
        CorpusKind corpusKind,
        String title,
        String content,
        String provenance,
        double score) {
}
