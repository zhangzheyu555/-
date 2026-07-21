package com.storeprofit.system.knowledgebase;

import java.util.ArrayList;
import java.util.List;

/** Keeps citations stable by never mixing text from different source locations in one chunk. */
final class KnowledgeDocumentChunker {
  private static final int MAX_CHARS_PER_CHUNK = 720;
  private static final int OVERLAP_CHARS = 96;
  private static final int MAX_CHUNKS = 2_000;

  List<ChunkDraft> split(List<KnowledgeDocumentParser.ExtractedSection> sections) {
    ArrayList<ChunkDraft> result = new ArrayList<>();
    for (KnowledgeDocumentParser.ExtractedSection section : sections) {
      String text = normalize(section.text());
      int start = 0;
      while (start < text.length()) {
        int end = preferredEnd(text, start);
        String content = text.substring(start, end).trim();
        if (!content.isBlank()) {
          result.add(new ChunkDraft(section.locator(), content));
          if (result.size() > MAX_CHUNKS) {
            throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_TOO_MANY_CHUNKS", "资料正文过长，请拆分后再上传");
          }
        }
        if (end >= text.length()) break;
        start = Math.max(start + 1, end - OVERLAP_CHARS);
      }
    }
    if (result.isEmpty()) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_EMPTY_DOCUMENT", "未从资料中读取到可检索的文字内容");
    }
    return List.copyOf(result);
  }

  private int preferredEnd(String text, int start) {
    int maximum = Math.min(text.length(), start + MAX_CHARS_PER_CHUNK);
    if (maximum >= text.length()) return text.length();
    int minimum = Math.min(maximum, start + MAX_CHARS_PER_CHUNK / 2);
    for (int index = maximum - 1; index >= minimum; index--) {
      char value = text.charAt(index);
      if (value == '\n' || value == '。' || value == '！' || value == '？' || value == '；' || value == ';') {
        return index + 1;
      }
    }
    return maximum;
  }

  private String normalize(String value) {
    return (value == null ? "" : value)
        .replace('\u0000', ' ')
        .replaceAll("[\\t\\x0B\\f\\r]+", " ")
        .replaceAll("[ ]{2,}", " ")
        .replaceAll("\\n{3,}", "\\n\\n")
        .trim();
  }

  record ChunkDraft(String sourceLocator, String content) {}
}
