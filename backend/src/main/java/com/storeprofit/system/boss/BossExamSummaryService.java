package com.storeprofit.system.boss;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.operations.ExamCenterModels.ExamAggregate;
import com.storeprofit.system.operations.ExamCenterModels.ExamStoreAggregate;
import com.storeprofit.system.operations.ExamCenterRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BossExamSummaryService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private final ExamCenterRepository examRepository;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;
  private final BigDecimal completionRateThreshold;
  private final BigDecimal passRateThreshold;
  private final int overdueCountThreshold;
  private final BigDecimal averageScoreThreshold;

  public BossExamSummaryService(
      ExamCenterRepository examRepository,
      AccessControlService accessControl,
      AuditRepository auditRepository,
      @Value("${app.exam.risk-completion-rate:80}") BigDecimal completionRateThreshold,
      @Value("${app.exam.risk-pass-rate:80}") BigDecimal passRateThreshold,
      @Value("${app.exam.risk-overdue-count:3}") int overdueCountThreshold,
      @Value("${app.exam.risk-average-score:70}") BigDecimal averageScoreThreshold
  ) {
    this.examRepository = examRepository;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
    this.completionRateThreshold = completionRateThreshold;
    this.passRateThreshold = passRateThreshold;
    this.overdueCountThreshold = overdueCountThreshold;
    this.averageScoreThreshold = averageScoreThreshold;
  }

  public BossExamSummaryResponse summary(AuthUser user) {
    accessControl.requireBossExamRead(user);
    ExamAggregate aggregate = examRepository.aggregate(user.tenantId());
    List<BossExamRiskStoreResponse> risks = examRepository.storeAggregates(user.tenantId()).stream()
        .map(this::riskStore)
        .filter(item -> !item.risks().isEmpty())
        .toList();
    auditRepository.writeLog(user, new AuditLogRequest(
        "查看老板考试概览",
        "training_exam_campaign",
        null,
        null,
        null,
        "查看全公司考试完成和风险情况",
        null,
        null
    ));
    return new BossExamSummaryResponse(
        aggregate.activeExamCount(),
        aggregate.assignedCount(),
        aggregate.completedCount(),
        rate(aggregate.completedCount(), aggregate.assignedCount()),
        aggregate.passedCount(),
        rate(aggregate.passedCount(), aggregate.completedCount()),
        aggregate.overdueCount(),
        aggregate.averageScore(),
        risks
    );
  }

  private BossExamRiskStoreResponse riskStore(ExamStoreAggregate store) {
    BigDecimal completionRate = rate(store.completedCount(), store.assignedCount());
    BigDecimal passRate = rate(store.passedCount(), store.completedCount());
    List<String> risks = new ArrayList<>();
    if (store.assignedCount() > 0 && completionRate.compareTo(completionRateThreshold) < 0) {
      risks.add("完成率偏低");
    }
    if (store.completedCount() > 0 && passRate.compareTo(passRateThreshold) < 0) {
      risks.add("通过率偏低");
    }
    if (store.overdueCount() >= overdueCountThreshold) {
      risks.add("逾期人数较多");
    }
    if (store.completedCount() > 0 && store.averageScore().compareTo(averageScoreThreshold) < 0) {
      risks.add("平均分偏低");
    }
    return new BossExamRiskStoreResponse(
        store.storeId(),
        store.storeName(),
        store.assignedCount(),
        store.completedCount(),
        completionRate,
        store.passedCount(),
        passRate,
        store.overdueCount(),
        store.averageScore(),
        List.copyOf(risks)
    );
  }

  private BigDecimal rate(int numerator, int denominator) {
    if (denominator <= 0) {
      return ZERO;
    }
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }
}
