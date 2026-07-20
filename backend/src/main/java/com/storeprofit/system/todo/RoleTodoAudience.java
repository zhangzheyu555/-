package com.storeprofit.system.todo;

public enum RoleTodoAudience {
  BOSS("老板（系统管理员）", "BOSS"),
  FINANCE("财务", "FINANCE"),
  SUPERVISOR("督导", "SUPERVISOR"),
  STORE_MANAGER("店长", "STORE_MANAGER"),
  WAREHOUSE("仓库管理员", "WAREHOUSE");

  private final String roleName;
  private final String roleCode;

  RoleTodoAudience(String roleName, String roleCode) {
    this.roleName = roleName;
    this.roleCode = roleCode;
  }

  public String roleName() {
    return roleName;
  }

  public String roleCode() {
    return roleCode;
  }
}
