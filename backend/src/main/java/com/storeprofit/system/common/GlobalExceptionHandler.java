package com.storeprofit.system.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import com.storeprofit.system.inspection.InspectionScoreRepairRequiredException;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * Historical inspection exports need an actionable, machine-readable repair response.  Keep the
   * ordinary business-exception contract unchanged for all other endpoints.
   */
  @ExceptionHandler(InspectionScoreRepairRequiredException.class)
  public ResponseEntity<ApiResponse<Map<String, List<String>>>> handleInspectionScoreRepairRequired(
      InspectionScoreRepairRequiredException ex,
      HttpServletRequest request
  ) {
    String requestId = RequestIdFilter.getRequestId(request);
    log.warn("Inspection score repair required: missingFields={} requestId={}",
        ex.missingFields(), requestId);
    return ResponseEntity.status(ex.getStatus()).body(new ApiResponse<>(
        false,
        ex.getMessage(),
        ex.getCode(),
        Map.of("missingFields", ex.missingFields()),
        requestId
    ));
  }

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

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(
      MaxUploadSizeExceededException ex,
      HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(ApiResponse.fail(
            "FILE_TOO_LARGE",
            "单个文件不能超过10MB，每次最多上传6个文件",
            RequestIdFilter.getRequestId(request)
        ));
  }

  @ExceptionHandler(MultipartException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleMalformedMultipart(MultipartException ex, HttpServletRequest request) {
    return ApiResponse.fail(
        "BAD_MULTIPART_REQUEST",
        "上传请求格式不正确，请重新选择文件后重试",
        RequestIdFilter.getRequestId(request)
    );
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

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingResource(
      NoResourceFoundException ex,
      HttpServletRequest request
  ) {
    String requestId = RequestIdFilter.getRequestId(request);
    log.info("Requested resource is unavailable: {} {} requestId={}",
        request.getMethod(), request.getRequestURI(), requestId);
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.fail(
            "API_ENDPOINT_UNAVAILABLE",
            "请求的服务当前不可用，请确认前后端版本一致后重试。",
            requestId
        ));
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
