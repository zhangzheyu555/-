package com.storeprofit.system.inspection;

import com.storeprofit.system.platform.auth.AuthUser;
import org.springframework.stereotype.Service;

@Service
public class InspectionStandardService {
  private final InspectionStandardRepository repository;

  public InspectionStandardService(InspectionStandardRepository repository) {
    this.repository = repository;
  }

  public InspectionStandardResponse activeStandard(AuthUser user) {
    return repository.activeVersion(user.tenantId())
        .map(version -> new InspectionStandardResponse(
            version.title(),
            version.fullScore(),
            version.version(),
            version.effectiveDate(),
            repository.items(user.tenantId(), version.id())
        ))
        .orElseGet(InspectionStandardResponse::empty);
  }
}
