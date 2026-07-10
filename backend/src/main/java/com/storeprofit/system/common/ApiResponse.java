package com.storeprofit.system.common;

public record ApiResponse<T>(boolean success, String message, String code, T data, String requestId) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, "OK", "OK", data, null);
  }

  public static ApiResponse<Void> ok() {
    return new ApiResponse<>(true, "OK", "OK", null, null);
  }

  public static ApiResponse<Void> fail(String code, String message) {
    return new ApiResponse<>(false, message, code, null, null);
  }

  public static ApiResponse<Void> fail(String code, String message, String requestId) {
    return new ApiResponse<>(false, message, code, null, requestId);
  }
}
