package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.multipart.MultipartFile;

class InspectionControlledYoloTest {
  private static final AuthUser SUPERVISOR = new AuthUser(
      71L, 1L, "测试租户", "inspection_supervisor", "", "巡检督导", "SUPERVISOR", null, true);
  private static final String STORE_ID = "S-QA-1";

  @Test
  void validImageUsesConfiguredLoopbackAndWritesOnlyRedactedSuccessAudit() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = detector(calls, 200,
        "{\"detections\":[{\"class_name\":\"floor_litter\",\"confidence\":0.91}]}", 0);
    server.start();
    try {
      JdbcTemplate jdbc = auditJdbc();
      InspectionRecordRepository records = recordsWithStore();
      InspectionService service = service(records, null, server, Duration.ofMillis(500), new AuditRepository(jdbc));
      TrackingMultipartFile file = imageFile("private-person-name.png", "image/png", png(), png().length);

      Map<String, Object> result = service.detect(SUPERVISOR, STORE_ID, file);

      assertThat(result.get("detections")).asList().hasSize(1);
      assertThat(calls).hasValue(1);
      verify(records).storeExists(1L, STORE_ID);
      verifyNoMoreInteractions(records);
      Map<String, Object> audit = jdbc.queryForMap("""
          select tenant_id, operator_id, action, target_type, store_id, reason,
                 target_id, before_json, after_json
          from operation_log
          """);
      assertThat(audit).containsEntry("TENANT_ID", 1L)
          .containsEntry("OPERATOR_ID", 71L)
          .containsEntry("ACTION", "inspection_detection_request")
          .containsEntry("TARGET_TYPE", "inspection_detection")
          .containsEntry("STORE_ID", STORE_ID)
          .containsEntry("TARGET_ID", null)
          .containsEntry("BEFORE_JSON", null)
          .containsEntry("AFTER_JSON", null);
      assertThat(audit.get("REASON").toString()).contains("result=SUCCESS", "elapsed_ms=");
      assertThat(audit.values().toString())
          .doesNotContain("private-person-name.png", "floor_litter", "confidence", "PNG");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void validNoResultResponseIsReadOnly() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = detector(calls, 200, "{\"detections\":[]}", 0);
    server.start();
    try {
      InspectionRecordRepository records = recordsWithStore();
      InspectionService service = service(records, null, server, Duration.ofMillis(500), null);

      Map<String, Object> result = service.detect(SUPERVISOR, STORE_ID, imageFile("scene.jpg", "image/jpeg", jpeg(), jpeg().length));

      assertThat(result).containsEntry("detections", List.of());
      assertThat(calls).hasValue(1);
      verify(records).storeExists(1L, STORE_ID);
      verifyNoMoreInteractions(records);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void timeoutFiveXxAndInvalidResponseNeverWriteInspectionBusinessData() throws Exception {
    assertDetectorFailure(503, "{\"error\":\"temporary\"}", 0, Duration.ofMillis(500),
        "INSPECTION_SERVICE_UNAVAILABLE");
    assertDetectorFailure(200, "{\"detections\":[]}", 180, Duration.ofMillis(40),
        "INSPECTION_SERVICE_UNAVAILABLE");
    assertDetectorFailure(200, "{\"unexpected\":true}", 0, Duration.ofMillis(500),
        "INSPECTION_INVALID_RESPONSE");
  }

  @Test
  void nonImageOversizeAndDamagedImageAreRejectedBeforeMockCall() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = detector(calls, 200, "{\"detections\":[]}", 0);
    server.start();
    try {
      InspectionRecordRepository records = recordsWithStore();
      InspectionService service = service(records, null, server, Duration.ofMillis(500), null);

      assertCode(() -> service.detect(SUPERVISOR, STORE_ID,
          imageFile("note.txt", "text/plain", "not-image".getBytes(StandardCharsets.UTF_8), 9)),
          "INSPECTION_IMAGE_TYPE_NOT_ALLOWED");
      assertCode(() -> service.detect(SUPERVISOR, STORE_ID,
          imageFile("fake.png", "image/png", "not-image".getBytes(StandardCharsets.UTF_8), 9)),
          "INSPECTION_IMAGE_INVALID");
      TrackingMultipartFile oversized = imageFile("large.png", "image/png", png(), 10L * 1024 * 1024 + 1);
      assertCode(() -> service.detect(SUPERVISOR, STORE_ID, oversized), "INSPECTION_IMAGE_TOO_LARGE");

      assertThat(oversized.byteReads()).isZero();
      assertThat(calls).hasValue(0);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void crossStoreAndCrossTenantAreRejectedBeforeImageReadOrMockCall() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = detector(calls, 200, "{\"detections\":[]}", 0);
    server.start();
    try {
      AccessControlService access = mock(AccessControlService.class);
      InspectionRecordRepository records = recordsWithStore();
      doThrow(new BusinessException("FORBIDDEN", "门店不在范围内", HttpStatus.FORBIDDEN))
          .when(access).requireStoreAccess(eq(SUPERVISOR), eq(DataScopeDomains.INSPECTION), eq("S-OTHER"), any());
      InspectionService service = service(records, access, server, Duration.ofMillis(500), null);
      TrackingMultipartFile crossStore = imageFile("scene.png", "image/png", png(), png().length);

      assertCode(() -> service.detect(SUPERVISOR, "S-OTHER", crossStore), "FORBIDDEN");
      assertThat(crossStore.byteReads()).isZero();

      when(records.storeExists(1L, "S-TENANT-2")).thenReturn(false);
      TrackingMultipartFile crossTenant = imageFile("scene.png", "image/png", png(), png().length);
      assertCode(() -> service.detect(SUPERVISOR, "S-TENANT-2", crossTenant), "FORBIDDEN");
      assertThat(crossTenant.byteReads()).isZero();
      assertThat(calls).hasValue(0);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void qaRejectsExternalTargetWithoutReadingImage() throws Exception {
    InspectionRecordRepository records = recordsWithStore();
    InspectionService service = new InspectionService(records, null, null, null,
        "https://example.invalid/detect", "https://example.invalid/export", Duration.ofMillis(100),
        "QA", "MOCK", null);
    TrackingMultipartFile file = imageFile("scene.png", "image/png", png(), png().length);

    assertCode(() -> service.detect(SUPERVISOR, STORE_ID, file), "INSPECTION_OUTBOUND_BLOCKED");

    assertThat(file.byteReads()).isZero();
  }

  @Test
  void qaRejectsLocalhostAliasAndNeverFollowsDetectorRedirects() throws Exception {
    InspectionRecordRepository records = recordsWithStore();
    InspectionService localhostService = new InspectionService(records, null, null, null,
        "http://localhost:19090/detect", "http://localhost:19090/export", Duration.ofMillis(100),
        "QA", "MOCK", null);
    TrackingMultipartFile aliasFile = imageFile("scene.png", "image/png", png(), png().length);
    assertCode(() -> localhostService.detect(SUPERVISOR, STORE_ID, aliasFile), "INSPECTION_OUTBOUND_BLOCKED");
    assertThat(aliasFile.byteReads()).isZero();

    AtomicInteger redirectCalls = new AtomicInteger();
    AtomicInteger redirectedTargetCalls = new AtomicInteger();
    HttpServer target = detector(redirectedTargetCalls, 200, "{\"detections\":[]}", 0);
    target.start();
    HttpServer redirect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    redirect.createContext("/detect", exchange -> {
      redirectCalls.incrementAndGet();
      exchange.getResponseHeaders().add(
          "Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/detect");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    redirect.start();
    try {
      InspectionService redirectService = service(recordsWithStore(), null, redirect, Duration.ofMillis(500), null);
      assertCode(() -> redirectService.detect(SUPERVISOR, STORE_ID,
          imageFile("scene.png", "image/png", png(), png().length)), "INSPECTION_INVALID_RESPONSE");
      assertThat(redirectCalls).hasValue(1);
      assertThat(redirectedTargetCalls).hasValue(0);
    } finally {
      redirect.stop(0);
      target.stop(0);
    }
  }

  private void assertDetectorFailure(
      int status,
      String body,
      long delayMs,
      Duration timeout,
      String expectedCode
  ) throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = detector(calls, status, body, delayMs);
    server.start();
    try {
      InspectionRecordRepository records = recordsWithStore();
      InspectionService service = service(records, null, server, timeout, null);

      assertCode(() -> service.detect(SUPERVISOR, STORE_ID,
          imageFile("scene.png", "image/png", png(), png().length)), expectedCode);

      assertThat(calls.get()).isGreaterThanOrEqualTo(1);
      verify(records).storeExists(1L, STORE_ID);
      verifyNoMoreInteractions(records);
    } finally {
      server.stop(0);
    }
  }

  private InspectionRecordRepository recordsWithStore() {
    InspectionRecordRepository records = mock(InspectionRecordRepository.class);
    when(records.storeExists(1L, STORE_ID)).thenReturn(true);
    return records;
  }

  private InspectionService service(
      InspectionRecordRepository records,
      AccessControlService access,
      HttpServer server,
      Duration timeout,
      AuditRepository audit
  ) {
    String detectUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/detect";
    return new InspectionService(records, access, null, null, detectUrl,
        detectUrl.replace("/detect", "/export"), timeout, "QA", "MOCK", audit);
  }

  private HttpServer detector(AtomicInteger calls, int status, String body, long delayMs) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/detect", exchange -> {
      calls.incrementAndGet();
      if (delayMs > 0) {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] response = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, response.length);
      try {
        exchange.getResponseBody().write(response);
      } catch (IOException ignored) {
        // The timeout case closes the client connection before this synthetic response is written.
      } finally {
        exchange.close();
      }
    });
    return server;
  }

  private JdbcTemplate auditJdbc() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:inspection_yolo_audit_" + UUID.randomUUID()
            + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table operation_log (
          id bigint auto_increment primary key, tenant_id bigint not null, operator_id bigint,
          operator_name varchar(120), action varchar(80) not null, target_type varchar(80) not null,
          target_id varchar(120), store_id varchar(64), month char(7), before_json clob,
          after_json clob, reason varchar(255), created_at timestamp not null
        )
        """);
    return jdbc;
  }

  private void assertCode(ThrowingCall call, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(code));
  }

  private byte[] png() throws IOException {
    return image("png");
  }

  private byte[] jpeg() throws IOException {
    return image("jpg");
  }

  private byte[] image(String format) throws IOException {
    BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0x55aa77);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, format, output);
    return output.toByteArray();
  }

  private TrackingMultipartFile imageFile(String name, String type, byte[] bytes, long declaredSize) {
    return new TrackingMultipartFile(name, type, bytes, declaredSize);
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws Exception;
  }

  private static final class TrackingMultipartFile implements MultipartFile {
    private final String filename;
    private final String contentType;
    private final byte[] bytes;
    private final long declaredSize;
    private final AtomicInteger byteReads = new AtomicInteger();

    private TrackingMultipartFile(String filename, String contentType, byte[] bytes, long declaredSize) {
      this.filename = filename;
      this.contentType = contentType;
      this.bytes = bytes;
      this.declaredSize = declaredSize;
    }

    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return filename; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return bytes.length == 0; }
    @Override public long getSize() { return declaredSize; }
    @Override public byte[] getBytes() { byteReads.incrementAndGet(); return bytes.clone(); }
    @Override public InputStream getInputStream() { byteReads.incrementAndGet(); return new ByteArrayInputStream(bytes); }
    @Override public void transferTo(File destination) throws IOException { throw new IOException("not used"); }
    private int byteReads() { return byteReads.get(); }
  }
}
