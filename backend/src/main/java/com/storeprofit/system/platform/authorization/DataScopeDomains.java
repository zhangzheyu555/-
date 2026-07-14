package com.storeprofit.system.platform.authorization;

import java.util.Set;

public final class DataScopeDomains {
  public static final String STORE = "STORE";
  public static final String FINANCE = "FINANCE";
  public static final String SALARY = "SALARY";
  public static final String WAREHOUSE = "WAREHOUSE";
  public static final String INSPECTION = "INSPECTION";
  public static final String EXAM = "EXAM";
  public static final String PLATFORM = "PLATFORM";

  public static final Set<String> ALL = Set.of(
      STORE, FINANCE, SALARY, WAREHOUSE, INSPECTION, EXAM, PLATFORM);

  private DataScopeDomains() {
  }
}
