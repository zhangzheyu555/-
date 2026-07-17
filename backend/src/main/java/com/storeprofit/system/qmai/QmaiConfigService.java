package com.storeprofit.system.qmai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QmaiConfigService {
  private final QmaiRepository repository;
  private final QmaiProperties properties;
  private final QmaiClient client;
  private final AuditRepository auditRepository;
  private final ObjectMapper objectMapper;

  public QmaiConfigService(QmaiRepository repository, QmaiProperties properties, QmaiClient client,
      AuditRepository auditRepository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.properties = properties;
    this.client = client;
    this.auditRepository = auditRepository;
    this.objectMapper = objectMapper;
  }

  public QmaiModels.ConfigResponse view(long tenantId) {
    QmaiRepository.ConfigRow config = repository.config(tenantId);
    return new QmaiModels.ConfigResponse(properties.isConfigured(), config.enabled(), config.displayName(),
        repository.mappings(tenantId), repository.latestBatch(tenantId, null).orElse(null));
  }

  public QmaiModels.ConfigResponse save(AuthUser user, QmaiModels.ConfigRequest request) {
    if (request == null) {
      throw badRequest("企迈配置不能为空");
    }
    String displayName = normalize(request.displayName(), "企迈", 120);
    List<QmaiModels.MappingRequest> mappings = request.mappings() == null ? List.of() : request.mappings().stream()
        .map(this::normalizeMapping).toList();
    validateMappings(user.tenantId(), mappings);
    QmaiModels.ConfigResponse before = view(user.tenantId());
    repository.saveConfig(user.tenantId(), Boolean.TRUE.equals(request.enabled()), displayName, user.id(), mappings);
    QmaiModels.ConfigResponse after = view(user.tenantId());
    auditRepository.writeLog(user, new AuditLogRequest(
        "维护企迈连接配置", "qmai_platform_config", QmaiModels.DEFAULT_BRAND, null, null,
        "只保存启用状态和门店映射，平台密钥仍由部署环境管理", json(before), json(after)));
    return after;
  }

  public List<QmaiModels.DiscoveredShop> discover(AuthUser user) {
    List<QmaiModels.DiscoveredShop> shops;
    try {
      shops = client.discoverShops();
    } catch (RuntimeException ex) {
      throw new BusinessException("QMAI_DISCOVERY_FAILED", safeMessage(ex), HttpStatus.BAD_GATEWAY);
    }
    auditRepository.writeLog(user, new AuditLogRequest(
        "读取企迈授权门店", "qmai_shop_discovery", QmaiModels.DEFAULT_BRAND, null, null,
        "发现 " + shops.size() + " 家授权门店", null, null));
    return shops;
  }

  private QmaiModels.MappingRequest normalizeMapping(QmaiModels.MappingRequest mapping) {
    if (mapping == null) {
      throw badRequest("门店映射不能包含空行");
    }
    return new QmaiModels.MappingRequest(
        normalize(mapping.qmaiShopId(), null, 80),
        normalize(mapping.qmaiShopName(), null, 160),
        normalize(mapping.storeId(), null, 64));
  }

  private void validateMappings(long tenantId, List<QmaiModels.MappingRequest> mappings) {
    Set<String> qmaiIds = new HashSet<>();
    Set<String> storeIds = new HashSet<>();
    for (QmaiModels.MappingRequest mapping : mappings) {
      if (!qmaiIds.add(mapping.qmaiShopId())) {
        throw badRequest("企迈门店不能重复映射");
      }
      if (!storeIds.add(mapping.storeId())) {
        throw badRequest("一个系统门店只能映射一个企迈门店");
      }
      if (!repository.storeExists(tenantId, mapping.storeId())) {
        throw badRequest("门店映射包含不存在或跨租户的系统门店");
      }
    }
  }

  private String normalize(String value, String fallback, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) {
      if (fallback == null) {
        throw badRequest("门店映射字段不能为空");
      }
      normalized = fallback;
    }
    if (normalized.length() > max) {
      throw badRequest("企迈配置字段过长");
    }
    return normalized;
  }

  private BusinessException badRequest(String message) {
    return new BusinessException("QMAI_CONFIG_INVALID", message, HttpStatus.BAD_REQUEST);
  }

  private String safeMessage(RuntimeException ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? "企迈平台暂时不可用" : message;
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      return null;
    }
  }
}
