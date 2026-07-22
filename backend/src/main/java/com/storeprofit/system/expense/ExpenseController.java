package com.storeprofit.system.expense;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {
  private final AuthService authService;
  private final ExpenseService expenseService;
  private final ExpenseSupplementService expenseSupplementService;

  @Autowired
  public ExpenseController(
      AuthService authService,
      ExpenseService expenseService,
      ExpenseSupplementService expenseSupplementService
  ) {
    this.authService = authService;
    this.expenseService = expenseService;
    this.expenseSupplementService = expenseSupplementService;
  }

  public ExpenseController(AuthService authService, ExpenseService expenseService) {
    this(authService, expenseService, null);
  }

  @GetMapping
  public ApiResponse<List<ExpenseClaimResponse>> claims(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.ok(expenseService.claims(authService.requireUser(authorization), month, brandId, storeId, status));
  }

  @GetMapping("/{id}")
  public ApiResponse<ExpenseClaimResponse> claim(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(expenseService.claim(authService.requireUser(authorization), id));
  }

  @PostMapping
  public ApiResponse<ExpenseClaimResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody ExpenseClaimRequest request
  ) {
    return ApiResponse.ok(expenseService.save(authService.requireUser(authorization), null, request, idempotencyKey));
  }

  /** Compatibility entry point for direct Java callers that do not provide an idempotency key. */
  public ApiResponse<ExpenseClaimResponse> create(
      String authorization,
      ExpenseClaimRequest request
  ) {
    return ApiResponse.ok(expenseService.save(authService.requireUser(authorization), null, request));
  }

  @PutMapping("/{id}")
  public ApiResponse<ExpenseClaimResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody ExpenseClaimRequest request
  ) {
    return ApiResponse.ok(expenseService.save(authService.requireUser(authorization), id, request));
  }

  @PostMapping("/{id}/submit")
  public ApiResponse<ExpenseClaimResponse> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(expenseService.submit(authService.requireUser(authorization), id));
  }

  @PostMapping("/{id}/approve")
  public ApiResponse<ExpenseClaimResponse> approve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) ExpenseReviewRequest request
  ) {
    return ApiResponse.ok(expenseService.approve(authService.requireUser(authorization), id, request));
  }

  @PostMapping("/{id}/request-info")
  public ApiResponse<ExpenseClaimResponse> requestInfo(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) ExpenseReviewRequest request
  ) {
    return ApiResponse.ok(expenseService.requestInfo(authService.requireUser(authorization), id, request));
  }

  @PostMapping(value = "/{id}/supplements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<ExpenseClaimResponse> supplement(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestParam(required = false) String note,
      @RequestParam(value = "files", required = false) List<MultipartFile> files
  ) {
    return ApiResponse.ok(expenseSupplementService.submit(
        authService.requireUser(authorization), id, note, files));
  }

  @GetMapping("/{id}/supplements")
  public ApiResponse<List<ExpenseSupplementResponse>> supplements(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(expenseSupplementService.supplements(authService.requireUser(authorization), id));
  }

  @GetMapping("/{expenseId}/attachments/{attachmentId}/content")
  public ResponseEntity<byte[]> attachmentContent(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String expenseId,
      @PathVariable long attachmentId,
      @RequestParam(defaultValue = "false") boolean download
  ) {
    return attachmentResponse(authorization, expenseId, attachmentId, download);
  }

  @GetMapping("/{id}/supplements/attachments/{attachmentId}/preview")
  public ResponseEntity<byte[]> previewSupplementAttachment(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @PathVariable long attachmentId
  ) {
    return attachmentResponse(authorization, id, attachmentId, false);
  }

  @GetMapping("/{id}/supplements/attachments/{attachmentId}/download")
  public ResponseEntity<byte[]> downloadSupplementAttachment(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @PathVariable long attachmentId
  ) {
    return attachmentResponse(authorization, id, attachmentId, true);
  }

  @PostMapping("/{id}/reject")
  public ApiResponse<ExpenseClaimResponse> reject(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) ExpenseReviewRequest request
  ) {
    return ApiResponse.ok(expenseService.reject(authService.requireUser(authorization), id, request));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    expenseService.delete(authService.requireUser(authorization), id);
    return ApiResponse.ok();
  }

  @DeleteMapping("/{id}/attachments/{attachmentId}")
  public ApiResponse<Void> deleteAttachment(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @PathVariable long attachmentId
  ) {
    expenseService.deleteAttachment(authService.requireUser(authorization), id, attachmentId);
    return ApiResponse.ok();
  }

  private ResponseEntity<byte[]> attachmentResponse(
      String authorization,
      String expenseId,
      long attachmentId,
      boolean download
  ) {
    ExpenseSupplementService.AttachmentContent attachment = expenseSupplementService.attachment(
        authService.requireUser(authorization), expenseId, attachmentId, download);
    boolean forceDownload = download || MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(attachment.contentType());
    ContentDisposition disposition = (forceDownload ? ContentDisposition.attachment() : ContentDisposition.inline())
        .filename(attachment.fileName(), StandardCharsets.UTF_8)
        .build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(attachment.contentType()))
        .contentLength(attachment.bytes().length)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .body(attachment.bytes());
  }
}
