package com.storeprofit.system.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
    String requestId = RequestIdFilter.getRequestId(request);
    log.warn("Business exception: code={} message={} requestId={}", ex.getCode(), ex.getMessage(), requestId);
    return ResponseEntity.status(ex.getStatus())
        .body(ApiResponse.fail(ex.getCode(), ex.getMessage(), requestId));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(this::formatFieldError)
        .collect(Collectors.joining("; "));
    return ApiResponse.fail("VALIDATION_ERROR", message, RequestIdFilter.getRequestId(request));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    return ApiResponse.fail("BAD_REQUEST", ex.getMessage(), RequestIdFilter.getRequestId(request));
  }

  // 客户端问题统一 400/413：请求体缺失或不可解析、缺必填参数/文件、参数类型不符、超出上传限制、
  // 以及数据超出列宽/违反约束（如超长名称、DECIMAL 溢出），不再落到 500。
  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
    return ApiResponse.fail("BAD_REQUEST", "请求体缺失或格式不正确", RequestIdFilter.getRequestId(request));
  }

  @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleMissingParameter(Exception ex, HttpServletRequest request) {
    String name = ex instanceof MissingServletRequestParameterException p
        ? p.getParameterName()
        : ((MissingServletRequestPartException) ex).getRequestPartName();
    return ApiResponse.fail("BAD_REQUEST", "缺少必填参数: " + name, RequestIdFilter.getRequestId(request));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    return ApiResponse.fail("BAD_REQUEST", "参数格式不正确: " + ex.getName(), RequestIdFilter.getRequestId(request));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
  public ApiResponse<Void> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
    return ApiResponse.fail("PAYLOAD_TOO_LARGE", "上传内容超出大小限制", RequestIdFilter.getRequestId(request));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
    String requestId = RequestIdFilter.getRequestId(request);
    log.warn("Data integrity violation: {} {} requestId={}", request.getMethod(), request.getRequestURI(), requestId, ex);
    return ApiResponse.fail("BAD_DATA", "数据超出字段允许范围或违反数据约束", requestId);
  }

  @ExceptionHandler(Throwable.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ApiResponse<Void> handleUnexpected(Throwable ex, HttpServletRequest request) {
    String requestId = RequestIdFilter.getRequestId(request);
    log.error("Unhandled API error: {} {} requestId={}", request.getMethod(), request.getRequestURI(), requestId, ex);
    return ApiResponse.fail("INTERNAL_ERROR", "服务器处理失败，请稍后重试", requestId);
  }

  private String formatFieldError(FieldError error) {
    return error.getField() + " " + error.getDefaultMessage();
  }
}
