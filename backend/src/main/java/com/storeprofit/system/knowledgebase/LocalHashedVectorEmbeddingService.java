package com.storeprofit.system.knowledgebase;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * A deterministic, no-network embedding for the first knowledge-base release.
 *
 * <p>It represents Chinese character n-grams and alphanumeric terms in a fixed-size dense vector.
 * This gives useful similarity retrieval for internal procedures without sending source documents to
 * an external embedding provider. The persisted model name makes a later model upgrade explicit and
 * re-indexable instead of silently mixing incompatible vectors.</p>
 */
@Service
public class LocalHashedVectorEmbeddingService {
  public static final String MODEL = "LOCAL_CHAR_NGRAM_V1";
  public static final int DIMENSIONS = 384;

  public byte[] embed(String value) {
    float[] vector = new float[DIMENSIONS];
    String normalized = normalized(value);
    if (normalized.isBlank()) return toBytes(vector);

    String compact = compact(normalized);
    if (compact.isBlank()) return toBytes(vector);
    addNgrams(vector, "^" + compact + "$", 1, 0.45f);
    addNgrams(vector, "^" + compact + "$", 2, 1.0f);
    addNgrams(vector, "^" + compact + "$", 3, 0.75f);
    for (String word : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
      if (word.length() >= 2) addFeature(vector, "word:" + word, 1.5f);
    }
    normalize(vector);
    return toBytes(vector);
  }

  public double cosine(byte[] left, byte[] right) {
    if (!valid(left) || !valid(right)) return 0d;
    ByteBuffer a = ByteBuffer.wrap(left).order(ByteOrder.BIG_ENDIAN);
    ByteBuffer b = ByteBuffer.wrap(right).order(ByteOrder.BIG_ENDIAN);
    double score = 0d;
    for (int index = 0; index < DIMENSIONS; index++) score += a.getFloat() * b.getFloat();
    return Math.max(0d, Math.min(1d, score));
  }

  private void addNgrams(float[] vector, String value, int size, float weight) {
    int[] codePoints = value.codePoints().toArray();
    for (int index = 0; index + size <= codePoints.length; index++) {
      String feature = new String(codePoints, index, size);
      addFeature(vector, feature, weight);
    }
  }

  private void addFeature(float[] vector, String feature, float weight) {
    int hash = 0x811c9dc5;
    for (int index = 0; index < feature.length(); index++) {
      hash ^= feature.charAt(index);
      hash *= 0x01000193;
    }
    vector[(hash & 0x7fffffff) % DIMENSIONS] += weight;
  }

  private String normalized(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String compact(String value) {
    StringBuilder result = new StringBuilder(value.length());
    value.codePoints().filter(codePoint -> Character.isLetterOrDigit(codePoint)).limit(8_000)
        .forEach(result::appendCodePoint);
    return result.toString();
  }

  private void normalize(float[] vector) {
    double squared = 0d;
    for (float value : vector) squared += value * value;
    if (squared <= 0d) return;
    double divisor = Math.sqrt(squared);
    for (int index = 0; index < vector.length; index++) vector[index] = (float) (vector[index] / divisor);
  }

  private byte[] toBytes(float[] vector) {
    ByteBuffer buffer = ByteBuffer.allocate(DIMENSIONS * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
    for (float value : vector) buffer.putFloat(value);
    return buffer.array();
  }

  private boolean valid(byte[] value) {
    return value != null && value.length == DIMENSIONS * Float.BYTES;
  }
}
