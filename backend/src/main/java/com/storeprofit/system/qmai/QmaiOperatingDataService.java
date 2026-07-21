package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Tenant-scoped reports over imported QMAI snapshots. */
@Service
public class QmaiOperatingDataService {
  private final QmaiOperatingDataRepository repository;

  public QmaiOperatingDataService(QmaiOperatingDataRepository repository) {
    this.repository = repository;
  }

  public List<QmaiOperatingDataRepository.RevenueRow> revenue(long tenantId, String brand, String month,
      Collection<String> allowedStoreIds) {
    Period period = period(month);
    return repository.revenue(tenantId, QmaiConfigService.normBrand(brand), period.from(), period.to(), allowedStoreIds);
  }

  public List<QmaiOperatingDataRepository.ProductRow> products(long tenantId, String brand, String month,
      Collection<String> allowedStoreIds) {
    Period period = period(month);
    return repository.products(tenantId, QmaiConfigService.normBrand(brand), period.from(), period.to(), allowedStoreIds);
  }

  public String month(String month) {
    return period(month).value();
  }

  private Period period(String raw) {
    try {
      YearMonth value = YearMonth.parse(raw == null || raw.isBlank() ? YearMonth.now().toString() : raw.trim());
      return new Period(value.toString(), value.atDay(1), value.atEndOfMonth());
    } catch (RuntimeException ex) {
      throw new BusinessException("QMAI_MONTH_INVALID", "月份格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private record Period(String value, LocalDate from, LocalDate to) {}
}
