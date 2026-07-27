package com.storeprofit.system.knowledgebase;

import java.time.LocalDateTime;
import java.util.List;

/** Safe metadata only; the original file stays behind the authenticated download endpoint. */
public record KnowledgeBaseDocumentResponse(
    long id,
    long topicId,
    String topicName,
    int versionNo,
    String relationType,
    Long predecessorDocumentId,
    String title,
    String category,
    String originalFileName,
    String contentType,
    long fileSize,
    String visibility,
    String status,
    List<String> roleScopes,
    List<String> storeScopes,
    int parsedCharCount,
    int chunkCount,
    long createdBy,
    Long publishedBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime publishedAt
) {}
