package com.storeprofit.system.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AuditRepositoryTransactionPolicyTest {

  @Test
  void permissionDenialsUseAnIndependentTransaction() throws Exception {
    Transactional transactional = AuditRepository.class
        .getMethod(
            "writePermissionDenied",
            AuthUser.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class)
        .getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
  }
}
