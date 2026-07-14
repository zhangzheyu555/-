package com.storeprofit.system.eleme;

import com.storeprofit.system.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 回调验签边界。
 *
 * <p>当前只支持显式启用的 HMAC_SHA256_BODY 契约：对原始请求体做 HMAC-SHA256，
 * 签名用十六进制传递。未完成契约确认或缺少独立密钥时一律拒绝请求。
 */
@Service
public class ElemeWebhookSignatureVerifier {
  private final ElemeProperties properties;

  public ElemeWebhookSignatureVerifier(ElemeProperties properties) {
    this.properties = properties;
  }

  public void verify(byte[] body, String suppliedSignature) {
    if (!properties.isWebhookConfigured()) {
      throw unauthorized(
          "ELEME_WEBHOOK_NOT_CONFIGURED",
          "饿了么回调验签尚未配置"
      );
    }
    if (suppliedSignature == null || suppliedSignature.isBlank()) {
      throw unauthorized("ELEME_WEBHOOK_SIGNATURE_MISSING", "缺少饿了么回调签名");
    }

    byte[] supplied = decodeSignature(suppliedSignature);
    byte[] expected = hmac(body == null ? new byte[0] : body);
    if (supplied == null || !MessageDigest.isEqual(expected, supplied)) {
      throw unauthorized("ELEME_WEBHOOK_SIGNATURE_INVALID", "饿了么回调签名无效");
    }
  }

  String signForTest(byte[] body) {
    return HexFormat.of().formatHex(hmac(body == null ? new byte[0] : body));
  }

  private byte[] decodeSignature(String value) {
    String normalized = value.trim();
    if (normalized.regionMatches(true, 0, "sha256=", 0, 7)) {
      normalized = normalized.substring(7);
    }
    try {
      return HexFormat.of().parseHex(normalized);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private byte[] hmac(byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(
          properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
          "HmacSHA256"
      ));
      return mac.doFinal(body);
    } catch (Exception ex) {
      throw new IllegalStateException("无法执行饿了么回调验签", ex);
    }
  }

  private BusinessException unauthorized(String code, String message) {
    return new BusinessException(code, message, HttpStatus.UNAUTHORIZED);
  }
}
