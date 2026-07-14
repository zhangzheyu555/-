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
        .map(version -> response(user, version))
        .orElseGet(InspectionStandardResponse::empty);
  }

  private InspectionStandardResponse response(
      AuthUser user,
      InspectionStandardRepository.VersionRow version
  ) {
    var items = repository.items(user.tenantId(), version.id());
    InspectionStandardValidation validation = InspectionStandardValidator.validate(version, items);
    return new InspectionStandardResponse(
        version.title(),
        version.fullScore(),
        version.version(),
        version.effectiveDate(),
        items,
        version.id(),
        version.passScore(),
        validation.valid(),
        validation.valid(),
        validation.validationError(),
        validation.diagnostics(),
        validation.categoryStats(),
        validation.riskStats()
    );
  }
}
