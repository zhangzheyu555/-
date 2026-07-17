package com.storeprofit.system.qmai;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QmaiBusinessService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final QmaiRepository repository;
  private final QmaiProperties properties;
  private final QmaiSyncWorker worker;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;

  public QmaiBusinessService(QmaiRepository repository, QmaiProperties properties, QmaiSyncWorker worker,
      AccessControlService accessControl, AuditRepository auditRepository) {
    this.repository = repository;
    this.properties = properties;
    this.worker = worker;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
  }

  public QmaiModels.BatchResponse startSync(AuthUser user, String monthValue) {
    accessControl.requireFinanceWrite(user);
    YearMonth month = month(monthValue);
    if (!properties.isConfigured()) {
      throw conflict("企迈凭证尚未由部署环境配置");
    }
    QmaiRepository.ConfigRow config = repository.config(user.tenantId());
    if (!config.enabled()) {
      throw conflict("企迈连接尚未启用");
    }
    QmaiModels.BatchResponse running = repository.runningBatch(user.tenantId(), month.toString()).orElse(null);
    if (running != null) {
      return running;
    }
    Collection<String> allowed = allowedStoreIds(user);
    List<QmaiModels.ShopMapping> mappings = repository.mappings(user.tenantId()).stream()
        .filter(mapping -> allowed == null || allowed.contains(mapping.storeId())).toList();
    if (mappings.isEmpty()) {
      throw conflict("当前数据范围内没有已配置的企迈门店映射");
    }
    List<LocalDate> dates = dates(month);
    long batchId = repository.createBatch(user.tenantId(), month.toString(), user.id(), user.displayName(),
        mappings.size() * dates.size());
    auditRepository.writeLog(user, new AuditLogRequest(
        "启动企迈经营数据同步", "qmai_sync_batch", Long.toString(batchId), null, month.toString(),
        "同步 " + mappings.size() + " 家门店、" + dates.size() + " 个营业日", null, null));
    worker.run(user.tenantId(), batchId, mappings, dates);
    return repository.latestBatch(user.tenantId(), month.toString()).orElseThrow();
  }

  public QmaiModels.SummaryResponse summary(AuthUser user, String monthValue, String storeId) {
    accessControl.requireFinanceRead(user);
    Collection<String> allowed = narrowedScope(user, storeId, "查看企迈经营数据");
    return repository.summary(user.tenantId(), month(monthValue), allowed);
  }

  public Collection<String> narrowedScope(AuthUser user, String storeId, String action) {
    Collection<String> allowed = allowedStoreIds(user);
    if (storeId == null || storeId.isBlank()) {
      return allowed;
    }
    accessControl.requireStoreAccess(user, DataScopeDomains.FINANCE, storeId, action);
    return List.of(storeId.trim());
  }

  private Collection<String> allowedStoreIds(AuthUser user) {
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.FINANCE);
    if (DataScopeModes.ALL.equals(scope.mode())) {
      return null;
    }
    if (DataScopeModes.STORE_LIST.equals(scope.mode()) || DataScopeModes.OWN_STORE.equals(scope.mode())) {
      return List.copyOf(scope.storeIds());
    }
    return List.of();
  }

  private YearMonth month(String value) {
    try {
      YearMonth month = YearMonth.parse(value == null || value.isBlank()
          ? YearMonth.now(BUSINESS_ZONE).toString() : value.trim());
      if (month.isAfter(YearMonth.now(BUSINESS_ZONE))) {
        throw new IllegalArgumentException();
      }
      return month;
    } catch (RuntimeException ex) {
      throw new BusinessException("QMAI_MONTH_INVALID", "月份格式不正确或不能选择未来月份", HttpStatus.BAD_REQUEST);
    }
  }

  private List<LocalDate> dates(YearMonth month) {
    LocalDate end = month.atEndOfMonth();
    LocalDate today = LocalDate.now(BUSINESS_ZONE);
    if (end.isAfter(today)) {
      end = today;
    }
    List<LocalDate> dates = new ArrayList<>();
    for (LocalDate date = month.atDay(1); !date.isAfter(end); date = date.plusDays(1)) {
      dates.add(date);
    }
    return dates;
  }

  private BusinessException conflict(String message) {
    return new BusinessException("QMAI_NOT_READY", message, HttpStatus.CONFLICT);
  }
}
