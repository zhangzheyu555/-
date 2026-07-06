package com.storeprofit.system.common;

public record ApiResponse<T>(boolean success, String message, String code, T data) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, "OK", "OK", data);
  }

  public static ApiResponse<Void> ok() {
    return new ApiResponse<>(true, "OK", "OK", null);
  }

  public static ApiResponse<Void> fail(String code, String message) {
    return new ApiResponse<>(false, message, code, null);
  }
}
