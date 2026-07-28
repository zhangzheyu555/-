package com.storeprofit.system.storage;

import jakarta.validation.Valid;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.common.BusinessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
  private final AuthService authService;
  private final StorageService storageService;

  public StorageController(AuthService authService, StorageService storageService) {
    this.authService = authService;
    this.storageService = storageService;
  }

  @GetMapping
  @Deprecated(since = "0.2.0", forRemoval = true)
  public StorageValueResponse get(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam String key
  ) {
    authService.requireUser(authorization);
    throw legacyKvApiDisabled();
  }

  @PostMapping
  @Deprecated(since = "0.2.0", forRemoval = true)
  public StorageValueResponse set(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody StorageWriteRequest request
  ) {
    authService.requireUser(authorization);
    throw legacyKvApiDisabled();
  }

  private BusinessException legacyKvApiDisabled() {
    return new BusinessException(
        "LEGACY_KV_API_DISABLED",
        "历史兼容数据接口已停用",
        HttpStatus.GONE
    );
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public com.storeprofit.system.common.ApiResponse<StorageUploadResponse> upload(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String businessType,
      @RequestParam(required = false) String businessId,
      @RequestParam String storeId
  ) {
    AuthUser user = authService.requireUser(authorization);
    return com.storeprofit.system.common.ApiResponse.ok(storageService.upload(user, file, businessType, businessId, storeId));
  }

  @GetMapping("/attachments/{id}")
  public ResponseEntity<byte[]> attachment(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    AuthUser user = authService.requireUser(authorization);
    StorageService.AttachmentContent attachment = storageService.attachment(user, id)
        .orElseThrow(() -> new BusinessException("ATTACHMENT_NOT_FOUND", "附件不存在", HttpStatus.NOT_FOUND));
    MediaType mediaType = attachment.contentType() == null || attachment.contentType().isBlank()
        ? MediaType.APPLICATION_OCTET_STREAM
        : MediaType.parseMediaType(attachment.contentType());
    return ResponseEntity.ok()
        .contentType(mediaType)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
            .filename(attachment.fileName(), java.nio.charset.StandardCharsets.UTF_8)
            .build()
            .toString())
        .body(attachment.content());
  }
}
