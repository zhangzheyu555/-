package com.storeprofit.system.knowledgebase;

import java.time.LocalDateTime;

/** Read-only metadata for a published document visible to the current account. */
public record KnowledgeBaseAvailableDocumentResponse(
    long id,
    String title,
    String category,
    String originalFileName,
    long fileSize,
    LocalDateTime publishedAt,
    LocalDateTime updatedAt
) {}
