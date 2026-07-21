package com.storeprofit.system.finance;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final FinanceRepository financeRepository;
  private final OrganizationRepository organizationRepository;
  private final AccessControlService accessControl;
  private final BusinessTodoService businessTodoService;
  private final DataScopeService dataScopeService;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public FinanceService(
      FinanceRepository financeRepository,
      OrganizationRepository organizationRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService,
      DataScopeService dataScopeService,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.financeRepository = financeRepository;
    this.organizationRepository = organizationRepository;
    this.accessControl = accessControl;
    this.businessTodoService = businessTodoService;
    this.dataScopeService = dataScopeService;
    this.businessScopeResolver = businessScopeResolver;
  }

  public FinanceService(
      FinanceRepository financeRepository,
      OrganizationRepository organizationRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService,
      DataScopeService dataScopeService
  ) {
    this(financeRepository, organizationRepository, accessControl, businessTodoService, dataScopeService, null);
  }

  public FinanceService(
      FinanceRepository financeRepository,
      OrganizationRepository organizationRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService
  ) {
    this(financeRepository, organizationRepository, accessControl, businessTodoService, null, null);
  }

  public FinanceService(
      FinanceRepository financeRepository,
      OrganizationRepository organizationRepository,
      AccessControlService accessControl
  ) {
    this(financeRepository, organizationRepository, accessControl, null, null, null);
  }

  public ProfitDashboardResponse dashboard(AuthUser user, String month, Long brandId) {
    return dashboard(user, month, brandId, null);
  }

  public ProfitDashboardResponse dashboard(
      AuthUser user,
      String month,
      Long brandId,
      String storeId
  ) {
    accessControl.requireFinanceRead(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, brandId, "查看利润概览");
    String targetMonth = normalizeMonth(month);
    DataScope dataScope = financeScope(user);
    List<String> months = months(user);
    List<ProfitEntryResponse> entries = profitEntries(
        user.tenantId(), targetMonth, businessScope.brandId(), businessScope.storeId(), dataScope);
    return new ProfitDashboardResponse(
        months,
        scopedBrands(user.tenantId(), dataScope),
        summary(targetMonth, entries),
        entries,
        trend(user, businessScope.brandId(), businessScope.storeId(), months)
    );
  }

  public List<ProfitEntryResponse> entries(AuthUser user, String month, Long brandId, String storeId) {
    accessControl.requireFinanceRead(user);
    DataScope dataScope = financeScope(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, brandId, "查看利润数据");
    String targetStoreId = requestedStoreIdForRead(user, businessScope.storeId(), dataScope);
    return profitEntries(user.tenantId(), normalizeMonth(month), businessScope.brandId(), targetStoreId, dataScope);
  }

  public ProfitEntryPageResponse entriesPaged(
      AuthUser user, String month, Long brandId, String storeId, int page, int size
  ) {
    int normalizedPage = Math.max(1, page);
    int normalizedSize = Math.max(1, Math.min(100, size));
    List<ProfitEntryResponse> allRows = entries(user, month, brandId, storeId);
    int total = allRows.size();
    long requestedOffset = (long) (normalizedPage - 1) * normalizedSize;
    int fromIndex = (int) Math.min(requestedOffset, total);
    int toIndex = Math.min(fromIndex + normalizedSize, total);
    int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / normalizedSize);
    return new ProfitEntryPageResponse(
        List.copyOf(allRows.subList(fromIndex, toIndex)), total, normalizedPage, normalizedSize, totalPages);
  }

  public ProfitEntryResponse entry(AuthUser user, String storeId, String month) {
    accessControl.requireFinanceRead(user);
    DataScope dataScope = financeScope(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, null, "查看利润明细");
    String targetStoreId = requestedStoreIdForRead(user, businessScope.storeId(), dataScope);
    if (targetStoreId == null || targetStoreId.isBlank()) {
      throw new BusinessException("BAD_STORE", "请选择门店", HttpStatus.BAD_REQUEST);
    }
    ProfitEntryResponse entry = profitEntry(user.tenantId(), targetStoreId, normalizeMonth(month), dataScope)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "该门店本月还没有利润数据", HttpStatus.NOT_FOUND));
    return entry;
  }

  @Transactional
  public void save(AuthUser user, ProfitEntryRequest request) {
    accessControl.requireFinanceWrite(user);
    BusinessScope businessScope = resolveBusinessScope(
        user, request == null ? null : request.storeId(), null, "保存利润数据");
    String targetStoreId = businessScope.storeId();
    requireStoreInScope(user, targetStoreId, financeScope(user), "保存利润数据");
    if (!financeRepository.storeExists(user.tenantId(), targetStoreId)) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业，不能保存利润数据", HttpStatus.BAD_REQUEST);
    }
    Long actualBrandId = financeRepository.storeBrandId(user.tenantId(), targetStoreId)
        .orElseThrow(() -> new BusinessException(
            "STORE_BRAND_REQUIRED", "门店尚未归属有效品牌，不能保存经营数据", HttpStatus.BAD_REQUEST));
    if (request.brandId() != null && !actualBrandId.equals(request.brandId())) {
      throw new BusinessException(
          "STORE_BRAND_MISMATCH", "所选品牌与门店归属不一致，不能保存经营数据", HttpStatus.BAD_REQUEST);
    }
    ProfitEntryRequest normalized = new ProfitEntryRequest(
        targetStoreId,
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
        request.note(),
        actualBrandId
    );
    financeRepository.upsert(user.tenantId(), normalized, user.id());
    financeRepository.logSave(user.tenantId(), user.id(), user.displayName(), normalized.storeId(), normalized.month());
    if (businessTodoService != null) {
      businessTodoService.reconcileMonthAfterFinanceSave(user, normalized.month());
    }
  }

  @Transactional
  public void delete(AuthUser user, String storeId, String month) {
    accessControl.requireFinanceDelete(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, null, "删除利润数据");
    String targetStoreId = businessScope.storeId() == null ? "" : businessScope.storeId();
    if (targetStoreId.isBlank()) {
      throw new BusinessException("BAD_STORE", "请选择门店", HttpStatus.BAD_REQUEST);
    }
    requireStoreInScope(user, targetStoreId, financeScope(user), "删除利润数据");
    String targetMonth = normalizeMonth(month);
    if (!financeRepository.storeExists(user.tenantId(), targetStoreId)) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    if (!financeRepository.entryExists(user.tenantId(), targetStoreId, targetMonth)) {
      throw new BusinessException("NOT_FOUND", "未找到该门店对应月份的利润数据", HttpStatus.NOT_FOUND);
    }
    financeRepository.deleteEntry(user.tenantId(), targetStoreId, targetMonth);
    financeRepository.logDelete(user.tenantId(), user.id(), user.displayName(), targetStoreId, targetMonth);
  }

  public List<String> months(AuthUser user) {
    accessControl.requireFinanceRead(user);
    LinkedHashSet<String> values = new LinkedHashSet<>();
    YearMonth current = YearMonth.now(BUSINESS_ZONE);
    for (int i = 0; i < 8; i++) {
      values.add(current.minusMonths(i).toString());
    }
    values.addAll(availableMonths(user.tenantId(), financeScope(user)));
    return new ArrayList<>(values);
  }

  private List<ProfitTrendPoint> trend(
      AuthUser user,
      Long brandId,
      String storeId,
      List<String> months
  ) {
    List<String> targetMonths = months.stream().limit(6).toList();
    DataScope dataScope = financeScope(user);
    ArrayList<ProfitTrendPoint> points = new ArrayList<>();
    for (int i = targetMonths.size() - 1; i >= 0; i--) {
      String month = targetMonths.get(i);
      ProfitSummaryResponse summary = summary(
          month,
          profitEntries(user.tenantId(), month, brandId, storeId, dataScope));
      points.add(new ProfitTrendPoint(
          month, summary.sales(), summary.income(), summary.net(), summary.margin()));
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

  private String requestedStoreIdForRead(AuthUser user, String storeId, DataScope dataScope) {
    String requested = storeId == null ? "" : storeId.trim();
    if (dataScope != null) {
      if (requested.isBlank()) {
        return null;
      }
      requireStoreInScope(user, requested, dataScope, "查看利润数据");
      return requested;
    }
    if (!"STORE_MANAGER".equals(user.role()) || user.storeId() == null || user.storeId().isBlank()) {
      return requested.isBlank() ? null : requested;
    }
    if (requested.isBlank()) {
      return user.storeId();
    }
    if (!user.storeId().equals(requested)) {
      accessControl.requireStoreAccess(user, requested, "查看利润数据");
      throw new BusinessException("FORBIDDEN", "店长只能查看自己门店利润表", HttpStatus.FORBIDDEN);
    }
    return user.storeId();
  }

  private DataScope financeScope(AuthUser user) {
    return dataScopeService == null ? null : dataScopeService.scope(user, DataScopeDomains.FINANCE);
  }

  private BusinessScope resolveBusinessScope(
      AuthUser user,
      String storeId,
      Long brandId,
      String action
  ) {
    if (businessScopeResolver != null) {
      return businessScopeResolver.resolve(
          user, DataScopeDomains.FINANCE, storeId, brandId, action);
    }
    String requestedStoreId = storeId == null || storeId.isBlank() ? null : storeId.trim();
    if (requestedStoreId == null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      requestedStoreId = user.storeId();
    }
    return new BusinessScope(requestedStoreId, null, brandId, null, financeScope(user));
  }

  private void requireStoreInScope(
      AuthUser user,
      String storeId,
      DataScope dataScope,
      String action
  ) {
    String normalizedStoreId = storeId == null ? "" : storeId.trim();
    if (normalizedStoreId.isBlank()) {
      throw new BusinessException("BAD_STORE", "请选择门店", HttpStatus.BAD_REQUEST);
    }
    if (dataScope != null && !dataScope.allowsStore(normalizedStoreId)) {
      throw new BusinessException("FORBIDDEN", "当前账号无权" + action + "：门店不在数据范围内", HttpStatus.FORBIDDEN);
    }
    if (dataScope == null && "STORE_MANAGER".equals(user.role())) {
      if (user.storeId() == null || user.storeId().isBlank()
          || !user.storeId().trim().equals(normalizedStoreId)) {
        accessControl.requireStoreAccess(user, normalizedStoreId, action);
        throw new BusinessException("FORBIDDEN", "店长只能处理所属门店的数据", HttpStatus.FORBIDDEN);
      }
    }
  }

  private List<ProfitEntryResponse> profitEntries(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      DataScope dataScope
  ) {
    return dataScope == null
        ? financeRepository.entries(tenantId, month, brandId, storeId)
        : financeRepository.entries(tenantId, month, brandId, storeId, dataScope);
  }

  private java.util.Optional<ProfitEntryResponse> profitEntry(
      long tenantId,
      String storeId,
      String month,
      DataScope dataScope
  ) {
    return dataScope == null
        ? financeRepository.entry(tenantId, storeId, month)
        : financeRepository.entry(tenantId, storeId, month, dataScope);
  }

  private List<String> availableMonths(long tenantId, DataScope dataScope) {
    return dataScope == null
        ? financeRepository.availableMonths(tenantId)
        : financeRepository.availableMonths(tenantId, dataScope);
  }

  private List<com.storeprofit.system.organization.BrandResponse> scopedBrands(
      long tenantId,
      DataScope dataScope
  ) {
    return dataScope == null
        ? organizationRepository.brands(tenantId)
        : organizationRepository.brands(tenantId, dataScope);
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
