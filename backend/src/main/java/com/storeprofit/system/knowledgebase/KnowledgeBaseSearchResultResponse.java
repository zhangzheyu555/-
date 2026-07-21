package com.storeprofit.system.knowledgebase;

/** A permitted vector match with a source citation suitable for direct display to a user. */
public record KnowledgeBaseSearchResultResponse(
    long documentId,
    String title,
    String category,
    String sourceLocator,
    String excerpt,
    double score
) {}
