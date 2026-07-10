package com.storeprofit.system.assistant;

/**
 * DeepSeek API 调用异常，包含安全错误码和面向用户的中文消息。
 * 不包含 API Key、完整请求体或供应商原始错误正文。
 */
public class DeepSeekException extends RuntimeException {

  private final String code;
  private final String userMessage;
  private final int httpStatus;

  public DeepSeekException(String code, String userMessage, int httpStatus) {
    super(code + ": " + userMessage);
    this.code = code;
    this.userMessage = userMessage;
    this.httpStatus = httpStatus;
  }

  public String getCode() {
    return code;
  }

  public String getUserMessage() {
    return userMessage;
  }

  public int getHttpStatus() {
    return httpStatus;
  }
}
