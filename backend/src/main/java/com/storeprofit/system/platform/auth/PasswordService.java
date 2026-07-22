package com.storeprofit.system.platform.auth;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final int ITERATIONS = 120_000;
  private static final int KEY_LENGTH = 256;
  private static final int ONE_TIME_PASSWORD_LENGTH = 20;
  private static final char[] UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
  private static final char[] LOWERCASE = "abcdefghijkmnopqrstuvwxyz".toCharArray();
  private static final char[] DIGITS = "23456789".toCharArray();
  private static final char[] SYMBOLS = "!@#$%_-".toCharArray();
  private static final char[] ONE_TIME_PASSWORD_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%_-".toCharArray();
  private final SecureRandom random = new SecureRandom();

  /** Generates a per-account password that is returned once and must never be persisted in clear. */
  public String generateOneTimePassword() {
    char[] value = new char[ONE_TIME_PASSWORD_LENGTH];
    try {
      value[0] = randomCharacter(UPPERCASE);
      value[1] = randomCharacter(LOWERCASE);
      value[2] = randomCharacter(DIGITS);
      value[3] = randomCharacter(SYMBOLS);
      for (int index = 4; index < value.length; index++) {
        value[index] = randomCharacter(ONE_TIME_PASSWORD_ALPHABET);
      }
      for (int index = value.length - 1; index > 0; index--) {
        int swapIndex = random.nextInt(index + 1);
        char current = value[index];
        value[index] = value[swapIndex];
        value[swapIndex] = current;
      }
      return new String(value);
    } finally {
      Arrays.fill(value, '\0');
    }
  }

  public String hash(String rawPassword) {
    Objects.requireNonNull(rawPassword, "rawPassword");
    char[] password = rawPassword.toCharArray();
    try {
      return hash(password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  public String hash(char[] rawPassword) {
    Objects.requireNonNull(rawPassword, "rawPassword");
    byte[] salt = new byte[16];
    byte[] hash = null;
    try {
      random.nextBytes(salt);
      hash = pbkdf(rawPassword, salt, ITERATIONS);
      return "pbkdf2$" + ITERATIONS + "$"
          + Base64.getEncoder().encodeToString(salt) + "$"
          + Base64.getEncoder().encodeToString(hash);
    } finally {
      clear(hash);
      clear(salt);
    }
  }

  public boolean matches(String rawPassword, String encoded) {
    if (rawPassword == null) {
      return false;
    }
    char[] password = rawPassword.toCharArray();
    try {
      return matches(password, encoded);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  public boolean matches(char[] rawPassword, String encoded) {
    if (rawPassword == null || encoded == null || !encoded.startsWith("pbkdf2$")) {
      return false;
    }
    String[] parts = encoded.split("\\$");
    if (parts.length != 4) {
      return false;
    }
    byte[] salt = null;
    byte[] expected = null;
    byte[] actual = null;
    try {
      int iterations = Integer.parseInt(parts[1]);
      salt = Base64.getDecoder().decode(parts[2]);
      expected = Base64.getDecoder().decode(parts[3]);
      actual = pbkdf(rawPassword, salt, iterations);
      return constantTimeEquals(expected, actual);
    } finally {
      clear(actual);
      clear(expected);
      clear(salt);
    }
  }

  private byte[] pbkdf(char[] rawPassword, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(rawPassword, salt, iterations, KEY_LENGTH);
    try {
      return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
      throw new IllegalStateException("Password hashing is not available", ex);
    } finally {
      spec.clearPassword();
    }
  }

  private char randomCharacter(char[] alphabet) {
    return alphabet[random.nextInt(alphabet.length)];
  }

  private void clear(byte[] value) {
    if (value != null) {
      Arrays.fill(value, (byte) 0);
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
