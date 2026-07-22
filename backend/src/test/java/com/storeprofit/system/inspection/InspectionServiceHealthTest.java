package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InspectionServiceHealthTest {
  @Test
  void serviceHealthReturnsUpWhenInspectionServiceHealthEndpointResponds() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/health", exchange -> {
      byte[] body = "{\"ok\":true,\"model_path\":\"model.pt\",\"has_trained_model\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      int port = server.getAddress().getPort();
      InspectionService service = serviceFor("http://127.0.0.1:" + port + "/detect");

      InspectionServiceHealthResponse response = service.serviceHealth();

      assertThat(response.status()).isEqualTo("UP");
      assertThat(response.configured()).isTrue();
      assertThat(response.healthUrl()).isEqualTo("http://127.0.0.1:" + port + "/health");
      assertThat(response.detectUrl()).isEqualTo("http://127.0.0.1:" + port + "/detect");
      assertThat(response.exportUrl()).isEqualTo("http://127.0.0.1:" + port + "/export");
      assertThat(response.message()).contains("可用");
      assertThat(response.details()).containsEntry("ok", true);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void serviceHealthReturnsDownInsteadOfThrowingWhenInspectionServiceIsUnavailable() throws Exception {
    int port = closedLocalPort();
    InspectionService service = serviceFor("http://127.0.0.1:" + port + "/detect");

    InspectionServiceHealthResponse response = service.serviceHealth();

    assertThat(response.status()).isEqualTo("DOWN");
    assertThat(response.configured()).isTrue();
    assertThat(response.healthUrl()).isEqualTo("http://127.0.0.1:" + port + "/health");
    assertThat(response.message()).contains("不可用");
    assertThat(response.details()).isEmpty();
  }

  @Test
  void serviceHealthReturnsUnconfiguredWhenDetectUrlIsBlank() {
    InspectionService service = serviceFor("");

    InspectionServiceHealthResponse response = service.serviceHealth();

    assertThat(response.status()).isEqualTo("UNCONFIGURED");
    assertThat(response.configured()).isFalse();
    assertThat(response.message()).contains("未配置");
  }

  @Test
  void qaHealthRefusesLiveOrExternalDetectionTargetsWithoutOpeningANetworkConnection() {
    InspectionService service = new InspectionService(
        mock(InspectionRecordRepository.class), null, null, null,
        "https://inspection.example.test/detect", "https://inspection.example.test/export", Duration.ofMillis(500),
        "QA", "MOCK", null);

    InspectionServiceHealthResponse response = service.serviceHealth();

    assertThat(response.status()).isEqualTo("OUTBOUND_BLOCKED");
    assertThat(response.configured()).isFalse();
    assertThat(response.message()).contains("本机 Mock");
  }

  private InspectionService serviceFor(String detectUrl) {
    String exportUrl = detectUrl == null || detectUrl.isBlank()
        ? ""
        : detectUrl.replace("/detect", "/export");
    return new InspectionService(
        mock(InspectionRecordRepository.class), null, null, null,
        detectUrl, exportUrl, Duration.ofMillis(500), "TEST", "MOCK", null);
  }

  private int closedLocalPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
