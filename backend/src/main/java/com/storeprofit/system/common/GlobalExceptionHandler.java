package com.storeprofit.system.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

  @ExceptionHandler({
      MissingServletRequestParameterException.class,
      MethodArgumentTypeMismatchException.class,
      HttpMessageNotReadableException.class,
      ConstraintViolationException.class
  })
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleRequestInput(Exception ex, HttpServletRequest request) {
    String message = ex instanceof MissingServletRequestParameterException missing
        ? "缺少必要参数：" + missing.getParameterName()
        : "请求参数格式不正确";
    return ApiResponse.fail("BAD_REQUEST", message, RequestIdFilter.getRequestId(request));
  }

  @ExceptionHandler(BadSqlGrammarException.class)
  public ResponseEntity<ApiResponse<Void>> handleSqlGrammar(
      BadSqlGrammarException ex,
      HttpServletRequest request
  ) {
    String requestId = RequestIdFilter.getRequestId(request);
    log.error("Database query failed: {} {} requestId={}", request.getMethod(), request.getRequestURI(), requestId, ex);
    SQLException sqlException = ex.getSQLException();
    boolean schemaMismatch = sqlException != null && (
        sqlException.getErrorCode() == 1054
            || sqlException.getErrorCode() == 1146
            || "42S22".equals(sqlException.getSQLState())
            || "42S02".equals(sqlException.getSQLState())
    );
    if (schemaMismatch) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(ApiResponse.fail(
              "DATABASE_MIGRATION_INCOMPLETE",
              "系统升级尚未完成，请联系管理员",
              requestId
          ));
    }
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.fail("INTERNAL_ERROR", "服务器处理失败，请稍后重试", requestId));
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
