package com.storeprofit.system.platform.authorization;

public record UserPermissionOverride(String permissionCode, PermissionEffect effect) {
  public UserPermissionOverride {
    permissionCode = permissionCode == null ? "" : permissionCode.trim();
    effect = effect == null ? PermissionEffect.DENY : effect;
  }
}
