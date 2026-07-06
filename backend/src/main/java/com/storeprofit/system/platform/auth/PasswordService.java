package com.storeprofit.system.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final int ITERATIONS = 120_000;
  private static final int KEY_LENGTH = 256;
  private final SecureRandom random = new SecureRandom();

  public String hash(String rawPassword) {
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    byte[] hash = pbkdf(rawPassword, salt, ITERATIONS);
    return "pbkdf2$" + ITERATIONS + "$"
        + Base64.getEncoder().encodeToString(salt) + "$"
        + Base64.getEncoder().encodeToString(hash);
  }

  public boolean matches(String rawPassword, String encoded) {
    if (rawPassword == null || encoded == null || !encoded.startsWith("pbkdf2$")) {
      return false;
    }
    String[] parts = encoded.split("\\$");
    if (parts.length != 4) {
      return false;
    }
    int iterations = Integer.parseInt(parts[1]);
    byte[] salt = Base64.getDecoder().decode(parts[2].getBytes(StandardCharsets.UTF_8));
    byte[] expected = Base64.getDecoder().decode(parts[3].getBytes(StandardCharsets.UTF_8));
    byte[] actual = pbkdf(rawPassword, salt, iterations);
    return constantTimeEquals(expected, actual);
  }

  private byte[] pbkdf(String rawPassword, byte[] salt, int iterations) {
    try {
      PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_LENGTH);
      return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
      throw new IllegalStateException("Password hashing is not available", ex);
    }
  }

  private boolean constantTimeEquals(byte[] a, byte[] b) {
    if (a.length != b.length) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length; i++) {
      result |= a[i] ^ b[i];
    }
    return result == 0;
  }
}
