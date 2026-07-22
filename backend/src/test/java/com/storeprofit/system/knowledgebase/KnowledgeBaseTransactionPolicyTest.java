package com.storeprofit.system.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class KnowledgeBaseTransactionPolicyTest {

  @Test
  void downloadingAnOriginalDocumentIsWritableBecauseItCreatesAnAuditLog() throws Exception {
    Transactional transactional = KnowledgeBaseService.class
        .getMethod("download", AuthUser.class, long.class)
        .getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.readOnly()).isFalse();
  }
}
