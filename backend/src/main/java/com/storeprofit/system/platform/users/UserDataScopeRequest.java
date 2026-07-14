package com.storeprofit.system.platform.users;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record UserDataScopeRequest(
    @NotBlank String domainCode,
    @NotBlank String mode,
    List<String> storeIds,
    List<String> warehouseIds
) {
  public UserDataScopeRequest(String domainCode, String mode, List<String> storeIds) {
    this(domainCode, mode, storeIds, List.of());
  }
}
