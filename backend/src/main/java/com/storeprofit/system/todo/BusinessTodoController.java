package com.storeprofit.system.todo;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessTodoController {
  private final AuthService authService;
  private final BusinessTodoService businessTodoService;

  public BusinessTodoController(AuthService authService, BusinessTodoService businessTodoService) {
    this.authService = authService;
    this.businessTodoService = businessTodoService;
  }

  @GetMapping("/api/todos")
  public ApiResponse<List<BusinessTodoResponse>> todos(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.ok(businessTodoService.list(authService.requireUser(authorization), status));
  }

  @GetMapping("/api/todos/{todoId}")
  public ApiResponse<BusinessTodoResponse> todo(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId
  ) {
    return ApiResponse.ok(businessTodoService.detail(authService.requireUser(authorization), todoId));
  }

  @PostMapping("/api/todos/{todoId}/transition")
  public ApiResponse<BusinessTodoResponse> transition(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody BusinessTodoTransitionRequest request
  ) {
    return ApiResponse.ok(businessTodoService.transition(authService.requireUser(authorization), todoId, request));
  }

  @PostMapping("/api/todos/reconcile")
  public ApiResponse<BusinessTodoService.BusinessTodoReconcileResponse> reconcile(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month
  ) {
    return ApiResponse.ok(businessTodoService.reconcileMonth(authService.requireUser(authorization), month));
  }

  @GetMapping("/api/todos/{todoId}/attachments/{attachmentId}")
  public ResponseEntity<byte[]> attachment(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @PathVariable String attachmentId
  ) {
    BusinessTodoService.DownloadedTodoAttachment file = businessTodoService.attachment(
        authService.requireUser(authorization), todoId, attachmentId);
    MediaType contentType = mediaType(file.contentType());
    return ResponseEntity.ok()
        .contentType(contentType)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(file.fileName(), StandardCharsets.UTF_8)
            .build()
            .toString())
        .body(file.content());
  }

  private MediaType mediaType(String value) {
    try {
      return value == null || value.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(value);
    } catch (IllegalArgumentException ex) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }
}
