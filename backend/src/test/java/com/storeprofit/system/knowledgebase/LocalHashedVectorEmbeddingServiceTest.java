package com.storeprofit.system.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalHashedVectorEmbeddingServiceTest {
  private final LocalHashedVectorEmbeddingService embeddings = new LocalHashedVectorEmbeddingService();

  @Test
  void keepsSimilarChineseOperatingTermsCloserThanUnrelatedContent() {
    byte[] query = embeddings.embed("门店交接班卫生检查流程");
    double related = embeddings.cosine(query, embeddings.embed("交接班时完成卫生检查并登记异常"));
    double unrelated = embeddings.cosine(query, embeddings.embed("员工工资发放和个税核对说明"));

    assertThat(related).isGreaterThan(unrelated);
    assertThat(embeddings.cosine(query, query)).isGreaterThan(0.999d);
  }
}
