package com.storeprofit.system.eleme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ElemeWebhookService {
  private static final int MAX_EVENT_ID_LENGTH = 160;
  private static final int MAX_EVENT_TYPE_LENGTH = 80;

  private final ElemeProperties properties;
  private final ElemeWebhookSignatureVerifier signatureVerifier;
  private final ElemeWebhookEventRepository eventRepository;
  private final ObjectMapper objectMapper;

  public ElemeWebhookService(
      ElemeProperties properties,
      ElemeWebhookSignatureVerifier signatureVerifier,
      ElemeWebhookEventRepository eventRepository,
      ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.signatureVerifier = signatureVerifier;
    this.eventRepository = eventRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public ElemeWebhookReceipt receive(byte[] rawBody, String signature, String eventId) {
    byte[] body = rawBody == null ? new byte[0] : rawBody;
    validatePayloadSize(body);
    signatureVerifier.verify(body, signature);
    String normalizedEventId = normalizeEventId(eventId);
    JsonNode payload = parsePayload(body);
    String payloadHash = sha256(body);
    String eventType = eventType(payload);

    try {
      eventRepository.insert(normalizedEventId, eventType, payloadHash);
      return new ElemeWebhookReceipt("ok", "RECEIVED", false);
    } catch (DuplicateKeyException duplicate) {
      String existingHash = eventRepository.payloadHash(normalizedEventId)
          .orElseThrow(() -> new BusinessException(
              "ELEME_WEBHOOK_EVENT_CONFLICT",
              "饿了么回调事件发生并发冲突，请稍后重试",
              HttpStatus.CONFLICT
          ));
      if (!MessageDigest.isEqual(
          existingHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          payloadHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )) {
        throw new BusinessException(
            "ELEME_WEBHOOK_EVENT_CONFLICT",
            "相同事件编号对应不同请求内容，已拒绝处理",
            HttpStatus.CONFLICT
        );
      }
      if (eventRepository.recordDuplicate(normalizedEventId, payloadHash) != 1) {
        throw new BusinessException(
            "ELEME_WEBHOOK_EVENT_CONFLICT",
            "饿了么回调事件状态异常，请稍后重试",
            HttpStatus.CONFLICT
        );
      }
      return new ElemeWebhookReceipt("ok", "RECEIVED", true);
    }
  }

  private void validatePayloadSize(byte[] body) {
    long configuredLimit = Math.max(1L, properties.getWebhookMaxPayloadBytes());
    if (body.length == 0) {
      throw new BusinessException(
          "ELEME_WEBHOOK_PAYLOAD_EMPTY",
          "饿了么回调内容不能为空",
          HttpStatus.BAD_REQUEST
      );
    }
    if (body.length > configuredLimit) {
      throw new BusinessException(
          "ELEME_WEBHOOK_PAYLOAD_TOO_LARGE",
          "饿了么回调内容超过允许大小",
          HttpStatus.PAYLOAD_TOO_LARGE
      );
    }
  }

  private String normalizeEventId(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()
        || normalized.length() > MAX_EVENT_ID_LENGTH
        || !normalized.matches("[A-Za-z0-9._:-]+")) {
      throw new BusinessException(
          "ELEME_WEBHOOK_EVENT_ID_INVALID",
          "饿了么回调事件编号缺失或格式不正确",
          HttpStatus.BAD_REQUEST
      );
    }
    return normalized;
  }

  private JsonNode parsePayload(byte[] body) {
    try {
      JsonNode node = objectMapper.readTree(body);
      if (node == null || !node.isObject()) {
        throw new IOException("payload must be an object");
      }
      return node;
    } catch (IOException ex) {
      throw new BusinessException(
          "ELEME_WEBHOOK_PAYLOAD_INVALID",
          "饿了么回调内容不是有效的 JSON 对象",
          HttpStatus.BAD_REQUEST
      );
    }
  }

  private String eventType(JsonNode payload) {
    for (String field : new String[] {"type", "eventType", "messageType"}) {
      JsonNode value = payload.get(field);
      if (value != null && value.isValueNode()) {
        String normalized = value.asText("").trim();
        if (!normalized.isEmpty()) {
          return normalized.substring(0, Math.min(normalized.length(), MAX_EVENT_TYPE_LENGTH));
        }
      }
    }
    return null;
  }

  private String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (Exception ex) {
      throw new IllegalStateException("无法计算饿了么回调摘要", ex);
    }
  }
}
