package com.storeprofit.system.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MultipartException;

class GlobalExceptionHandlerTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void missingParameterReturnsChineseBadRequestWithRequestId() {
    MockHttpServletRequest request = request("request-400");

    ApiResponse<Void> response = handler.handleRequestInput(
        new MissingServletRequestParameterException("month", "String"),
        request
    );

    assertThat(response.code()).isEqualTo("BAD_REQUEST");
    assertThat(response.message()).isEqualTo("缺少必要参数：month");
    assertThat(response.requestId()).isEqualTo("request-400");
  }

  @Test
  void missingDatabaseColumnReturnsSafeMigrationError() {
    MockHttpServletRequest request = request("request-503");
    BadSqlGrammarException exception = new BadSqlGrammarException(
        "salary page",
        "select hidden_column from salary_record",
        new SQLException("Unknown column", "42S22", 1054)
    );

    ResponseEntity<ApiResponse<Void>> response = handler.handleSqlGrammar(exception, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("DATABASE_MIGRATION_INCOMPLETE");
    assertThat(response.getBody().message()).isEqualTo("系统升级尚未完成，请联系管理员");
    assertThat(response.getBody().requestId()).isEqualTo("request-503");
  }

  @Test
  void malformedMultipartReturnsBadRequestInsteadOfInternalError() {
    MockHttpServletRequest request = request("request-multipart-400");

    ApiResponse<Void> response = handler.handleMalformedMultipart(
        new MultipartException("stream ended unexpectedly"),
        request
    );

    assertThat(response.code()).isEqualTo("BAD_MULTIPART_REQUEST");
    assertThat(response.message()).isEqualTo("上传请求格式不正确，请重新选择文件后重试");
    assertThat(response.requestId()).isEqualTo("request-multipart-400");
  }

  private MockHttpServletRequest request(String requestId) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/salaries/page");
    request.setAttribute("requestId", requestId);
    return request;
  }
}
