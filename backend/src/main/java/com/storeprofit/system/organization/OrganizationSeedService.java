package com.storeprofit.system.organization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.platform.tenant.TenantDefaults;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

/**
 * R1-03: Removed @Service and @PostConstruct to prevent automatic runtime seed creation.
 * The class remains as a utility for explicit dev/demo fixture use only.
 * STAGING/PRODUCTION must never invoke seed methods via any path.
 */
public class OrganizationSeedService {
  private final OrganizationRepository repository;
  private final ObjectMapper objectMapper;
  private final Environment environment;
  private final boolean demoSeedEnabled;
  private final boolean migrationAutoRun;

  public OrganizationSeedService(
      OrganizationRepository repository,
      ObjectMapper objectMapper,
      Environment environment,
      @Value("${app.seed.demo-enabled:false}") boolean demoSeedEnabled,
      @Value("${app.migration.auto-run:false}") boolean migrationAutoRun
  ) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.environment = environment;
    this.demoSeedEnabled = demoSeedEnabled;
    this.migrationAutoRun = migrationAutoRun;
  }

  // R1-03: @PostConstruct removed. This method is no longer auto-triggered.
  // Must be called explicitly for dev/test fixtures only.
  public void seed() {
    if (!shouldSeedDemoData() && !migrationAutoRun) {
      return;
    }
    seedBrands();
    if (repository.storeCount(TenantDefaults.DEFAULT_TENANT_ID) > 0) {
      return;
    }
    boolean migrated = migrationAutoRun && seedStoresFromLegacyJson();
    if (!migrated && shouldSeedDemoData()) {
      seedFallbackStores();
    }
  }

  private void seedBrands() {
    repository.ensureBrand(TenantDefaults.DEFAULT_TENANT_ID, "RG", "茹果", "#EF7E3D", 10);
    repository.ensureBrand(TenantDefaults.DEFAULT_TENANT_ID, "BW", "霸王茶姬", "#9A3340", 20);
    repository.ensureBrand(TenantDefaults.DEFAULT_TENANT_ID, "RX", "瑞幸咖啡", "#2E6BD6", 30);
  }

  private boolean seedStoresFromLegacyJson() {
    return repository.kv("stores").map(raw -> {
      try {
        List<Map<String, Object>> stores = objectMapper.readValue(raw, new TypeReference<>() {});
        for (Map<String, Object> item : stores) {
          String id = text(item.get("id"));
          String name = text(item.get("name"));
          if (id.isBlank() || name.isBlank()) {
            continue;
          }
          String brandName = text(item.get("brand"));
          long brandId = brandId(brandName);
          repository.upsertStore(TenantDefaults.DEFAULT_TENANT_ID, new StoreUpsertRequest(
              id,
              textOrDefault(item.get("code"), id),
              name,
              brandId,
              text(item.get("area")),
              text(item.get("manager")),
              text(item.get("openDate")),
              textOrDefault(item.get("status"), "营业中"),
              text(item.get("note"))
          ));
        }
        return repository.storeCount(TenantDefaults.DEFAULT_TENANT_ID) > 0;
      } catch (Exception ex) {
        return false;
      }
    }).orElse(false);
  }

  private void seedFallbackStores() {
    long rg = brandId("茹果");
    long bw = brandId("霸王茶姬");
    long rx = brandId("瑞幸咖啡");
    repository.upsertStore(TenantDefaults.DEFAULT_TENANT_ID, new StoreUpsertRequest("rg1", "RG001", "保利店", rg, "荆州", "李瑞", "", "营业中", "默认种子门店"));
    repository.upsertStore(TenantDefaults.DEFAULT_TENANT_ID, new StoreUpsertRequest("rg4", "RG004", "荆州之星店", rg, "荆州", "孔繁中", "", "营业中", "默认种子门店"));
    repository.upsertStore(TenantDefaults.DEFAULT_TENANT_ID, new StoreUpsertRequest("rg8", "RG008", "长大店", rg, "荆州", "", "", "营业中", "默认种子门店"));
    repository.upsertStore(TenantDefaults.DEFAULT_TENANT_ID, new StoreUpsertRequest("bw2", "BW002", "万达店", bw, "汕头", "", "", "营业中", "默认种子门店"));
    repository.upsertStore(TenantDefaults.DEFAULT_TENANT_ID, new StoreUpsertRequest("bw5", "BW005", "环美店", bw, "汕头", "", "", "营业中", "默认种子门店"));
    repository.upsertStore(TenantDefaults.DEFAULT_TENANT_ID, new StoreUpsertRequest("rx3", "RX003", "长江大学店", rx, "荆州", "", "", "营业中", "默认种子门店"));
  }

  private long brandId(String brandName) {
    return switch (brandName) {
      case "霸王茶姬" -> repository.ensureBrand(TenantDefaults.DEFAULT_TENANT_ID, "BW", "霸王茶姬", "#9A3340", 20);
      case "瑞幸咖啡" -> repository.ensureBrand(TenantDefaults.DEFAULT_TENANT_ID, "RX", "瑞幸咖啡", "#2E6BD6", 30);
      default -> repository.ensureBrand(TenantDefaults.DEFAULT_TENANT_ID, "RG", "茹果", "#EF7E3D", 10);
    };
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String textOrDefault(Object value, String fallback) {
    String text = text(value);
    return text.isBlank() ? fallback : text;
  }

  private boolean shouldSeedDemoData() {
    return demoSeedEnabled || Arrays.stream(environment.getActiveProfiles())
        .anyMatch(profile -> "dev".equalsIgnoreCase(profile) || "demo".equalsIgnoreCase(profile));
  }
}
