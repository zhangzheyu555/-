package com.storeprofit.system.boss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.ExamCenterModels.ExamAggregate;
import com.storeprofit.system.operations.ExamCenterModels.ExamStoreAggregate;
import com.storeprofit.system.operations.ExamCenterRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BossExamSummaryServiceTest {
  private final ExamCenterRepository examRepository = mock(ExamCenterRepository.class);
  private final AuthService authService = mock(AuthService.class);
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final AccessControlService accessControl = new AccessControlService(authService, authRepository, auditRepository);
  private final BossExamSummaryService service = new BossExamSummaryService(
      examRepository,
      accessControl,
      auditRepository,
      new BigDecimal("80"),
      new BigDecimal("80"),
      3,
      new BigDecimal("70")
  );

  @Test
  void bossSummaryCalculatesRatesAndRiskStoresFromMysqlAggregates() {
    AuthUser boss = user("BOSS", null);
    when(examRepository.aggregate(1L)).thenReturn(new ExamAggregate(
        2, 10, 6, 4, 3, new BigDecimal("76.50")));
    when(examRepository.storeAggregates(1L)).thenReturn(List.of(
        new ExamStoreAggregate("rg1", "荆州之星店", 10, 6, 4, 3, new BigDecimal("68.00")),
        new ExamStoreAggregate("rg2", "花台店", 5, 5, 5, 0, new BigDecimal("92.00"))
    ));

    BossExamSummaryResponse result = service.summary(boss);

    assertThat(result.activeExamCount()).isEqualTo(2);
    assertThat(result.completionRate()).isEqualByComparingTo("60.00");
    assertThat(result.passRate()).isEqualByComparingTo("66.67");
    assertThat(result.riskStores()).hasSize(1);
    assertThat(result.riskStores().getFirst().risks())
        .containsExactly("完成率偏低", "通过率偏低", "逾期人数较多", "平均分偏低");
    verify(auditRepository).writeLog(any(AuthUser.class), any());
  }

  @Test
  void financeCannotReadBossExamSummary() {
    AuthUser finance = user("FINANCE", null);

    assertThatThrownBy(() -> service.summary(finance))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.getCode()).isEqualTo("FORBIDDEN"));

    verify(examRepository, never()).aggregate(1L);
    verify(auditRepository).writePermissionDenied(any(), any(), any(), any(), any(), any());
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(10L, 1L, "默认企业", "tester", "", "测试账号", role, storeId, true);
  }
}
