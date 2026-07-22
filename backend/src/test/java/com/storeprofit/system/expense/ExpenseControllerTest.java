package com.storeprofit.system.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

class ExpenseControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final ExpenseService expenseService = mock(ExpenseService.class);
  private final ExpenseController controller = new ExpenseController(authService, expenseService);
  private final AuthUser boss = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);

  @Test
  void listUsesAuthenticatedUserAndWrapsResponse() {
    ExpenseClaimResponse row = response("exp-1", "草稿");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(expenseService.claims(boss, "2026-05", 1L, "s1", "草稿")).thenReturn(List.of(row));

    ApiResponse<List<ExpenseClaimResponse>> result = controller.claims("Bearer token", "2026-05", 1L, "s1", "草稿");

    assertThat(result.success()).isTrue();
    assertThat(result.data()).containsExactly(row);
    verify(authService).requireUser("Bearer token");
    verify(expenseService).claims(boss, "2026-05", 1L, "s1", "草稿");
  }

  @Test
  void createUpdateAndDeleteUseAuthenticatedUser() {
    ExpenseClaimRequest request = request();
    ExpenseClaimResponse row = response("exp-1", "草稿");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(expenseService.save(boss, null, request, "expense-create-retry-1")).thenReturn(row);
    when(expenseService.save(boss, "exp-1", request)).thenReturn(row);

    ApiResponse<ExpenseClaimResponse> created = controller.create("Bearer token", "expense-create-retry-1", request);
    ApiResponse<ExpenseClaimResponse> updated = controller.update("Bearer token", "exp-1", request);
    ApiResponse<Void> deleted = controller.delete("Bearer token", "exp-1");

    assertThat(created.data()).isSameAs(row);
    assertThat(updated.data()).isSameAs(row);
    assertThat(deleted.success()).isTrue();
    verify(expenseService).save(boss, null, request, "expense-create-retry-1");
    verify(expenseService).save(boss, "exp-1", request);
    verify(expenseService).delete(boss, "exp-1");
  }

  @Test
  void detailAndPrimaryAttachmentDeletionUseAuthenticatedScopedService() {
    ExpenseClaimResponse row = response("exp-1", "草稿");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(expenseService.claim(boss, "exp-1")).thenReturn(row);

    assertThat(controller.claim("Bearer token", "exp-1").data()).isSameAs(row);
    assertThat(controller.deleteAttachment("Bearer token", "exp-1", 9L).success()).isTrue();

    verify(expenseService).claim(boss, "exp-1");
    verify(expenseService).deleteAttachment(boss, "exp-1", 9L);
  }

  @Test
  void stateActionsUsePathIdAndReviewNote() {
    ExpenseReviewRequest review = new ExpenseReviewRequest("OK");
    ExpenseClaimResponse pending = response("exp-1", "待审核");
    ExpenseClaimResponse approved = response("exp-1", "已完成");
    ExpenseClaimResponse rejected = response("exp-1", "已驳回");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(expenseService.submit(boss, "exp-1")).thenReturn(pending);
    when(expenseService.approve(boss, "exp-1", review)).thenReturn(approved);
    when(expenseService.reject(boss, "exp-1", review)).thenReturn(rejected);

    assertThat(controller.submit("Bearer token", "exp-1").data()).isSameAs(pending);
    assertThat(controller.approve("Bearer token", "exp-1", review).data()).isSameAs(approved);
    assertThat(controller.reject("Bearer token", "exp-1", review).data()).isSameAs(rejected);

    verify(expenseService).submit(boss, "exp-1");
    verify(expenseService).approve(boss, "exp-1", review);
    verify(expenseService).reject(boss, "exp-1", review);
  }

  @Test
  void multipartSupplementAndAuthenticatedDownloadUseDedicatedService() throws Exception {
    ExpenseSupplementService supplementService = mock(ExpenseSupplementService.class);
    ExpenseController supplementController = new ExpenseController(authService, expenseService, supplementService);
    MockMultipartFile file = new MockMultipartFile(
        "files", "invoice.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes());
    ExpenseClaimResponse row = response("exp-1", "待审核");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(supplementService.submit(boss, "exp-1", "付款凭证", List.of(file))).thenReturn(row);
    when(supplementService.attachment(boss, "exp-1", 9L, true)).thenReturn(
        new ExpenseSupplementService.AttachmentContent("付款凭证.pdf", "application/pdf", file.getBytes()));

    ApiResponse<ExpenseClaimResponse> submitted = supplementController.supplement(
        "Bearer token", "exp-1", "付款凭证", List.of(file));
    ResponseEntity<byte[]> downloaded = supplementController.downloadSupplementAttachment(
        "Bearer token", "exp-1", 9L);

    assertThat(submitted.data()).isSameAs(row);
    assertThat(downloaded.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
    assertThat(downloaded.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store, max-age=0");
    assertThat(downloaded.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(downloaded.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
    verify(supplementService).submit(boss, "exp-1", "付款凭证", List.of(file));
    verify(supplementService).attachment(boss, "exp-1", 9L, true);
  }

  @Test
  void authenticatedContentEndpointReturnsImagesInlineAndPdfAsAttachment() {
    ExpenseSupplementService supplementService = mock(ExpenseSupplementService.class);
    ExpenseController supplementController = new ExpenseController(authService, expenseService, supplementService);
    byte[] image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
    byte[] pdf = "%PDF-1.7\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(supplementService.attachment(boss, "exp-1", 10L, false)).thenReturn(
        new ExpenseSupplementService.AttachmentContent("付款截图.png", "image/png", image));
    when(supplementService.attachment(boss, "exp-1", 11L, false)).thenReturn(
        new ExpenseSupplementService.AttachmentContent("付款凭证.pdf", "application/pdf", pdf));
    when(supplementService.attachment(boss, "exp-1", 10L, true)).thenReturn(
        new ExpenseSupplementService.AttachmentContent("付款截图.png", "image/png", image));

    ResponseEntity<byte[]> imageResponse = supplementController.attachmentContent(
        "Bearer token", "exp-1", 10L, false);
    ResponseEntity<byte[]> pdfResponse = supplementController.attachmentContent(
        "Bearer token", "exp-1", 11L, false);
    ResponseEntity<byte[]> imageDownloadResponse = supplementController.attachmentContent(
        "Bearer token", "exp-1", 10L, true);

    assertThat(imageResponse.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(imageResponse.getBody()).isEqualTo(image);
    ContentDisposition imageDisposition = ContentDisposition.parse(
        imageResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    assertThat(imageDisposition.getType()).isEqualTo("inline");
    assertThat(imageDisposition.getFilename()).isEqualTo("付款截图.png");
    assertThat(pdfResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    ContentDisposition pdfDisposition = ContentDisposition.parse(
        pdfResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    assertThat(pdfDisposition.getType()).isEqualTo("attachment");
    assertThat(pdfDisposition.getFilename()).isEqualTo("付款凭证.pdf");
    assertThat(ContentDisposition.parse(imageDownloadResponse.getHeaders()
        .getFirst(HttpHeaders.CONTENT_DISPOSITION)).getType()).isEqualTo("attachment");
    verify(supplementService).attachment(boss, "exp-1", 10L, false);
    verify(supplementService).attachment(boss, "exp-1", 11L, false);
    verify(supplementService).attachment(boss, "exp-1", 10L, true);
  }

  @Test
  void contentEndpointRejectsUnauthenticatedRequestBeforeReadingAttachment() {
    ExpenseSupplementService supplementService = mock(ExpenseSupplementService.class);
    ExpenseController supplementController = new ExpenseController(authService, expenseService, supplementService);
    BusinessException unauthorized = new BusinessException(
        "UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED);
    when(authService.requireUser(null)).thenThrow(unauthorized);

    assertThatThrownBy(() -> supplementController.attachmentContent(null, "exp-1", 10L, false))
        .isSameAs(unauthorized);
  }

  private ExpenseClaimRequest request() {
    return new ExpenseClaimRequest(
        "s1",
        "2026-05",
        "2026-05-15",
        new BigDecimal("128.50"),
        "物料采购",
        "牛奶采购",
        "https://example.test/invoice.jpg"
    );
  }

  private ExpenseClaimResponse response(String id, String status) {
    return new ExpenseClaimResponse(
        id,
        "s1",
        "001",
        "One",
        1L,
        "Tea",
        "2026-05",
        new BigDecimal("128.50"),
        "物料采购",
        "牛奶采购",
        status,
        "https://example.test/invoice.jpg",
        3L,
        1L,
        LocalDateTime.of(2026, 5, 1, 12, 0)
    );
  }
}
