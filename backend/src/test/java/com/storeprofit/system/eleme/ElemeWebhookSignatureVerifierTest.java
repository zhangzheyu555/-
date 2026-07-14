package com.storeprofit.system.eleme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.storeprofit.system.common.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ElemeWebhookSignatureVerifierTest {
  @Test
  void acceptsOnlyTheConfiguredRawBodySignature() {
    ElemeProperties properties = configuredProperties();
    ElemeWebhookSignatureVerifier verifier = new ElemeWebhookSignatureVerifier(properties);
    byte[] body = "{\"type\":217}".getBytes(StandardCharsets.UTF_8);
    String signature = verifier.signForTest(body);

    assertThatCode(() -> verifier.verify(body, signature)).doesNotThrowAnyException();
    assertThatCode(() -> verifier.verify(body, "sha256=" + signature)).doesNotThrowAnyException();

    BusinessException invalid = catchThrowableOfType(
        () -> verifier.verify("{\"type\":18}".getBytes(StandardCharsets.UTF_8), signature),
        BusinessException.class
    );
    assertThat(invalid.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(invalid.getCode()).isEqualTo("ELEME_WEBHOOK_SIGNATURE_INVALID");
  }

  @Test
  void failsClosedWhenSignatureContractOrSignatureIsMissing() {
    ElemeProperties properties = new ElemeProperties();
    ElemeWebhookSignatureVerifier verifier = new ElemeWebhookSignatureVerifier(properties);

    BusinessException notConfigured = catchThrowableOfType(
        () -> verifier.verify("{}".getBytes(StandardCharsets.UTF_8), "deadbeef"),
        BusinessException.class
    );
    assertThat(notConfigured.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(notConfigured.getCode()).isEqualTo("ELEME_WEBHOOK_NOT_CONFIGURED");

    properties.setWebhookSecret("test-only-secret");
    properties.setWebhookSignatureMode("HMAC_SHA256_BODY");
    BusinessException missing = catchThrowableOfType(
        () -> verifier.verify("{}".getBytes(StandardCharsets.UTF_8), null),
        BusinessException.class
    );
    assertThat(missing.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(missing.getCode()).isEqualTo("ELEME_WEBHOOK_SIGNATURE_MISSING");
  }

  private ElemeProperties configuredProperties() {
    ElemeProperties properties = new ElemeProperties();
    properties.setWebhookSecret("test-only-secret");
    properties.setWebhookSignatureMode("HMAC_SHA256_BODY");
    return properties;
  }
}
