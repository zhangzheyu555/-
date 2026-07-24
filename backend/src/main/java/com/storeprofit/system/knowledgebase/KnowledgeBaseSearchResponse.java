package com.storeprofit.system.knowledgebase;

import java.util.List;

/** Summarized search answer plus permitted source chunks for verification. */
public record KnowledgeBaseSearchResponse(
    String query,
    String summary,
    List<KnowledgeBaseTopicSearchResponse> topics,
    List<KnowledgeBaseSearchResultResponse> results
) {}
