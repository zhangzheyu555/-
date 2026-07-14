package com.storeprofit.system.platform.authorization;

import java.util.Set;

public final class DataScopeModes {
  public static final String ALL = "ALL";
  public static final String STORE_LIST = "STORE_LIST";
  public static final String WAREHOUSE_LIST = "WAREHOUSE_LIST";
  public static final String OWN_STORE = "OWN_STORE";
  public static final String NONE = "NONE";
  public static final String CENTRAL_WAREHOUSE = "CENTRAL_WAREHOUSE";
  public static final String SELF = "SELF";

  public static final Set<String> ALL_MODES = Set.of(
      ALL, STORE_LIST, WAREHOUSE_LIST, OWN_STORE, NONE, CENTRAL_WAREHOUSE, SELF);

  private DataScopeModes() {
  }
}
