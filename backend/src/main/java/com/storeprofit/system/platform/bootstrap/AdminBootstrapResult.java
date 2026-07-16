package com.storeprofit.system.platform.bootstrap;

public record AdminBootstrapResult(int exitCode, String machineMessage) {
  private static final AdminBootstrapResult CREATED =
      new AdminBootstrapResult(0, "ADMIN_BOOTSTRAP_CREATED");
  private static final AdminBootstrapResult INPUT_INVALID =
      new AdminBootstrapResult(2, "ADMIN_BOOTSTRAP_INPUT_INVALID");
  private static final AdminBootstrapResult SAFETY_REJECTED =
      new AdminBootstrapResult(3, "ADMIN_BOOTSTRAP_SAFETY_REJECTED");
  private static final AdminBootstrapResult TENANT_REJECTED =
      new AdminBootstrapResult(4, "ADMIN_BOOTSTRAP_TENANT_REJECTED");
  private static final AdminBootstrapResult ALREADY_INITIALIZED =
      new AdminBootstrapResult(5, "ADMIN_BOOTSTRAP_ALREADY_INITIALIZED");
  private static final AdminBootstrapResult CONCURRENT_FAILURE =
      new AdminBootstrapResult(6, "ADMIN_BOOTSTRAP_CONCURRENT_FAILURE");
  private static final AdminBootstrapResult TRANSACTION_FAILED =
      new AdminBootstrapResult(7, "ADMIN_BOOTSTRAP_TRANSACTION_FAILED");
  private static final AdminBootstrapResult UNEXPECTED_FAILURE =
      new AdminBootstrapResult(70, "ADMIN_BOOTSTRAP_UNEXPECTED_FAILURE");

  public static AdminBootstrapResult created() {
    return CREATED;
  }

  public static AdminBootstrapResult inputInvalid() {
    return INPUT_INVALID;
  }

  public static AdminBootstrapResult safetyRejected() {
    return SAFETY_REJECTED;
  }

  public static AdminBootstrapResult tenantRejected() {
    return TENANT_REJECTED;
  }

  public static AdminBootstrapResult alreadyInitialized() {
    return ALREADY_INITIALIZED;
  }

  public static AdminBootstrapResult concurrentFailure() {
    return CONCURRENT_FAILURE;
  }

  public static AdminBootstrapResult transactionFailed() {
    return TRANSACTION_FAILED;
  }

  public static AdminBootstrapResult unexpectedFailure() {
    return UNEXPECTED_FAILURE;
  }
}
