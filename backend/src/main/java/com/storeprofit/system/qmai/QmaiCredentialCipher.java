package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Encrypts platform credentials before they reach the database. */
@Component
public class QmaiCredentialCipher {
  private static final String PREFIX = "enc:v1:";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final QmaiProperties properties;

  public QmaiCredentialCipher(QmaiProperties properties) {
    this.properties = properties;
  }

  public String encrypt(String plainText) {
    if (plainText == null || plainText.isBlank()) {
      return "";
    }
    try {
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(plainText.trim().getBytes(StandardCharsets.UTF_8));
      byte[] payload = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, payload, 0, iv.length);
      System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
      return PREFIX + Base64.getEncoder().encodeToString(payload);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException("QMAI_CREDENTIAL_ENCRYPT_FAILED", "企迈凭证加密失败", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  public String decrypt(String stored) {
    if (stored == null || stored.isBlank()) {
      return "";
    }
    if (!stored.startsWith(PREFIX)) {
      // Legacy clear-text rows must never be silently reused after the security boundary is on.
      return "";
    }
    try {
      byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
      if (payload.length <= 12) {
        throw new IllegalArgumentException("payload");
      }
      byte[] iv = java.util.Arrays.copyOfRange(payload, 0, 12);
      byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException("QMAI_CREDENTIAL_DECRYPT_FAILED", "企迈凭证无法解密，请由管理员重新保存配置", HttpStatus.CONFLICT);
    }
  }

  private SecretKeySpec key() {
    String configured = properties.getCredentialEncryptionKey();
    if (configured == null || configured.isBlank()) {
      throw new BusinessException("QMAI_CREDENTIAL_KEY_MISSING", "未配置企迈凭证加密密钥，拒绝保存凭证", HttpStatus.SERVICE_UNAVAILABLE);
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(configured.trim());
      if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
        throw new IllegalArgumentException("key length");
      }
      return new SecretKeySpec(bytes, "AES");
    } catch (IllegalArgumentException ex) {
      throw new BusinessException("QMAI_CREDENTIAL_KEY_INVALID", "企迈凭证加密密钥格式无效", HttpStatus.SERVICE_UNAVAILABLE);
    }
  }
}
