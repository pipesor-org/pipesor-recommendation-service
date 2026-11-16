package com.pipesor.recommendation.retrieval.application.ports;

import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;

public interface RagKnowledgeRetriever {
    RetrievalResult retrieve(RetrievalQuery query);
}
