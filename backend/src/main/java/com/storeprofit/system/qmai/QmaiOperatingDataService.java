package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Tenant-scoped reports over imported QMAI snapshots. */
@Service
public class QmaiOperatingDataService {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private final QmaiOperatingDataRepository repository;

  public QmaiOperatingDataService(QmaiOperatingDataRepository repository) {
    this.repository = repository;
  }

  public List<QmaiOperatingDataRepository.RevenueRow> revenue(long tenantId, String brand, String month,
      Collection<String> allowedStoreIds) {
    Period period = period(month);
    if (period.from().isAfter(period.to())) {
      return List.of();
    }
    return repository.revenue(tenantId, QmaiConfigService.normBrand(brand), period.from(), period.to(), allowedStoreIds);
  }

  public List<QmaiOperatingDataRepository.ProductRow> products(long tenantId, String brand, String month,
      Collection<String> allowedStoreIds) {
    Period period = period(month);
    if (period.from().isAfter(period.to())) {
      return List.of();
    }
    return repository.products(tenantId, QmaiConfigService.normBrand(brand), period.from(), period.to(), allowedStoreIds);
  }

  public List<QmaiOperatingDataRepository.RevenueRow> revenueForDate(
      long tenantId, String brand, String businessDate, Collection<String> allowedStoreIds) {
    LocalDate date = parseBusinessDate(null, businessDate);
    return repository.revenue(
        tenantId, QmaiConfigService.normBrand(brand), date, date, allowedStoreIds);
  }

  public List<QmaiOperatingDataRepository.ProductRow> productsForDate(
      long tenantId, String brand, String businessDate, Collection<String> allowedStoreIds) {
    LocalDate date = parseBusinessDate(null, businessDate);
    return repository.products(
        tenantId, QmaiConfigService.normBrand(brand), date, date, allowedStoreIds);
  }

  /** Validates an exact date and, when supplied, requires it to belong to the selected month. */
  public String businessDate(String month, String businessDate) {
    return parseBusinessDate(month, businessDate).toString();
  }

  public String month(String month) {
    return period(month).value();
  }

  private Period period(String raw) {
    try {
      YearMonth value = YearMonth.parse(
          raw == null || raw.isBlank() ? YearMonth.now(ZONE).toString() : raw.trim());
      LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
      LocalDate to = value.atEndOfMonth().isAfter(yesterday)
          ? yesterday
          : value.atEndOfMonth();
      return new Period(value.toString(), value.atDay(1), to);
    } catch (RuntimeException ex) {
      throw new BusinessException("QMAI_MONTH_INVALID", "月份格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private LocalDate parseBusinessDate(String month, String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("empty date");
      }
      LocalDate date = LocalDate.parse(raw.trim());
      if (month != null && !month.isBlank()
          && !YearMonth.from(date).equals(YearMonth.parse(month.trim()))) {
        throw new BusinessException(
            "QMAI_DATE_MONTH_MISMATCH", "所选日期不在当前月份内", HttpStatus.BAD_REQUEST);
      }
      if (date.isAfter(LocalDate.now(ZONE).minusDays(1))) {
        throw new BusinessException(
            "QMAI_DATE_NOT_FINAL", "只能查看截至昨天的企迈营业额", HttpStatus.BAD_REQUEST);
      }
      return date;
    } catch (BusinessException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new BusinessException("QMAI_DATE_INVALID", "日期格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private record Period(String value, LocalDate from, LocalDate to) {}
}
