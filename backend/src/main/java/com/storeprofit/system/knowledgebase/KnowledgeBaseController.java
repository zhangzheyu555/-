package com.storeprofit.system.knowledgebase;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** APIs for internal document upload, controlled publication, retrieval and original-file download. */
@RestController
@RequestMapping("/api/knowledge-base")
public class KnowledgeBaseController {
  private final AuthService authService;
  private final KnowledgeBaseService knowledgeBaseService;

  public KnowledgeBaseController(AuthService authService, KnowledgeBaseService knowledgeBaseService) {
    this.authService = authService;
    this.knowledgeBaseService = knowledgeBaseService;
  }

  @GetMapping("/search")
  public ApiResponse<List<KnowledgeBaseSearchResultResponse>> search(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam String q,
      @RequestParam(defaultValue = "5") int limit
  ) {
    return ApiResponse.ok(knowledgeBaseService.search(user(authorization), q, limit));
  }

  @GetMapping("/documents")
  public ApiResponse<List<KnowledgeBaseDocumentResponse>> documents(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(knowledgeBaseService.listDocuments(user(authorization)));
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<KnowledgeBaseDocumentResponse> upload(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String title,
      @RequestParam String category,
      @RequestParam String visibility,
      @RequestParam(required = false) List<String> roleScopes,
      @RequestParam(required = false) List<String> storeScopes
  ) {
    return ApiResponse.ok(knowledgeBaseService.upload(
        user(authorization), file, title, category, visibility, roleScopes, storeScopes));
  }

  @PostMapping("/documents/{id}/publish")
  public ApiResponse<KnowledgeBaseDocumentResponse> publish(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(knowledgeBaseService.publish(user(authorization), id));
  }

  @PostMapping("/documents/{id}/archive")
  public ApiResponse<KnowledgeBaseDocumentResponse> archive(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(knowledgeBaseService.archive(user(authorization), id));
  }

  @GetMapping("/documents/{id}/download")
  public ResponseEntity<byte[]> download(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    KnowledgeBaseService.DownloadedDocument document = knowledgeBaseService.download(user(authorization), id);
    MediaType contentType = safeMediaType(document.contentType());
    return ResponseEntity.ok()
        .contentType(contentType)
        .contentLength(document.fileSize())
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(document.fileName(), StandardCharsets.UTF_8).build().toString())
        .body(document.content());
  }

  private AuthUser user(String authorization) {
    return authService.requireUser(authorization);
  }

  private MediaType safeMediaType(String value) {
    try {
      return value == null || value.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(value);
    } catch (IllegalArgumentException ex) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }
}
