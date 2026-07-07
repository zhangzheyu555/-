package com.storeprofit.system.finance;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final FinanceRepository financeRepository;
  private final OrganizationRepository organizationRepository;

  public FinanceService(FinanceRepository financeRepository, OrganizationRepository organizationRepository) {
    this.financeRepository = financeRepository;
    this.organizationRepository = organizationRepository;
  }

  public ProfitDashboardResponse dashboard(AuthUser user, String month, Long brandId) {
    String targetMonth = normalizeMonth(month);
    List<String> months = months(user);
    List<ProfitEntryResponse> entries = scoped(user, financeRepository.entries(user.tenantId(), targetMonth, brandId, null));
    return new ProfitDashboardResponse(
        months,
        organizationRepository.brands(user.tenantId()),
        summary(targetMonth, entries),
        entries,
        trend(user, brandId, months)
    );
  }

  public List<ProfitEntryResponse> entries(AuthUser user, String month, Long brandId, String storeId) {
    return scoped(user, financeRepository.entries(user.tenantId(), normalizeMonth(month), brandId, storeId));
  }

  public ProfitEntryResponse entry(AuthUser user, String storeId, String month) {
    ProfitEntryResponse entry = financeRepository.entry(user.tenantId(), storeId, normalizeMonth(month))
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "该门店本月还没有利润数据", HttpStatus.NOT_FOUND));
    return scoped(user, List.of(entry)).stream()
        .findFirst()
        .orElseThrow(() -> new BusinessException("FORBIDDEN", "无权查看该门店数据", HttpStatus.FORBIDDEN));
  }

  @Transactional
  public void save(AuthUser user, ProfitEntryRequest request) {
    requireEditRole(user);
    if (!financeRepository.storeExists(user.tenantId(), request.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业，不能保存利润数据", HttpStatus.BAD_REQUEST);
    }
    ProfitEntryRequest normalized = new ProfitEntryRequest(
        request.storeId(),
        normalizeMonth(request.month()),
        request.sales(),
        request.refund(),
        request.discount(),
        request.material(),
        request.packaging(),
        request.loss(),
        request.costOther(),
        request.rent(),
        request.labor(),
        request.utility(),
        request.property(),
        request.commission(),
        request.promo(),
        request.repair(),
        request.equip(),
        request.expOther(),
        request.note()
    );
    financeRepository.upsert(user.tenantId(), normalized, user.id());
    financeRepository.logSave(user.tenantId(), user.id(), user.displayName(), normalized.storeId(), normalized.month());
  }

  public List<String> months(AuthUser user) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    YearMonth current = YearMonth.now(BUSINESS_ZONE);
    for (int i = 0; i < 8; i++) {
      values.add(current.minusMonths(i).toString());
    }
    values.addAll(financeRepository.availableMonths(user.tenantId()));
    return new ArrayList<>(values);
  }

  private List<ProfitTrendPoint> trend(AuthUser user, Long brandId, List<String> months) {
    List<String> targetMonths = months.stream().limit(6).toList();
    ArrayList<ProfitTrendPoint> points = new ArrayList<>();
    for (int i = targetMonths.size() - 1; i >= 0; i--) {
      String month = targetMonths.get(i);
      ProfitSummaryResponse summary = summary(month, scoped(user, financeRepository.entries(user.tenantId(), month, brandId, null)));
      points.add(new ProfitTrendPoint(month, summary.income(), summary.net(), summary.margin()));
    }
    return points;
  }

  private ProfitSummaryResponse summary(String month, List<ProfitEntryResponse> entries) {
    BigDecimal sales = sum(entries.stream().map(ProfitEntryResponse::sales).toList());
    BigDecimal income = sum(entries.stream().map(ProfitEntryResponse::income).toList());
    BigDecimal costSum = sum(entries.stream().map(ProfitEntryResponse::costSum).toList());
    BigDecimal expenseSum = sum(entries.stream().map(ProfitEntryResponse::expenseSum).toList());
    BigDecimal net = sum(entries.stream().map(ProfitEntryResponse::net).toList());
    int risk = (int) entries.stream().filter(entry -> !"健康".equals(entry.risk())).count();
    int storeCount = (int) entries.stream().map(ProfitEntryResponse::storeId).distinct().count();
    return new ProfitSummaryResponse(month, storeCount, entries.size(), sales, income, costSum, expenseSum, net, ratio(net, income), risk);
  }

  private List<ProfitEntryResponse> scoped(AuthUser user, List<ProfitEntryResponse> entries) {
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null && !user.storeId().isBlank()) {
      return entries.stream().filter(entry -> user.storeId().equals(entry.storeId())).toList();
    }
    return entries;
  }

  private void requireEditRole(AuthUser user) {
    if (!List.of("ADMIN", "FINANCE").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅管理员和财务可保存利润数据", HttpStatus.FORBIDDEN);
    }
  }

  private String normalizeMonth(String value) {
    if (value == null || value.isBlank()) {
      return YearMonth.now(BUSINESS_ZONE).toString();
    }
    try {
      return YearMonth.parse(value.trim()).toString();
    } catch (Exception ex) {
      throw new BusinessException("BAD_MONTH", "月份格式必须为 YYYY-MM", HttpStatus.BAD_REQUEST);
    }
  }

  private BigDecimal sum(List<BigDecimal> values) {
    return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (denominator.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
    return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
  }
}
