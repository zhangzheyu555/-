package com.storeprofit.system.knowledgebase;

import java.util.List;

/** Integrated search matches for one business topic, with every statement traceable to a source. */
public record KnowledgeBaseTopicSearchResponse(
    long topicId,
    String topicName,
    String summary,
    List<KnowledgeBaseSearchResultResponse> sources
) {}
