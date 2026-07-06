package com.storeprofit.system.organization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OrganizationSeedService {
  private final OrganizationRepository repository;
  private final ObjectMapper objectMapper;

  public OrganizationSeedService(OrganizationRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  public void seed() {
    seedBrands();
    if (repository.storeCount() > 0) {
      return;
    }
    if (!seedStoresFromLegacyJson()) {
      seedFallbackStores();
    }
  }

  private void seedBrands() {
    repository.ensureBrand("RG", "茹果", "#EF7E3D", 10);
    repository.ensureBrand("BW", "霸王茶姬", "#9A3340", 20);
    repository.ensureBrand("RX", "瑞幸咖啡", "#2E6BD6", 30);
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
          repository.upsertStore(new StoreUpsertRequest(
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
        return repository.storeCount() > 0;
      } catch (Exception ex) {
        return false;
      }
    }).orElse(false);
  }

  private void seedFallbackStores() {
    long rg = brandId("茹果");
    long bw = brandId("霸王茶姬");
    long rx = brandId("瑞幸咖啡");
    repository.upsertStore(new StoreUpsertRequest("rg1", "RG001", "保利店", rg, "荆州", "李瑜", "", "营业中", "默认种子门店"));
    repository.upsertStore(new StoreUpsertRequest("rg4", "RG004", "荆州之星店", rg, "荆州", "孔繁丽", "", "营业中", "默认种子门店"));
    repository.upsertStore(new StoreUpsertRequest("rg8", "RG008", "长大店", rg, "荆州", "", "", "营业中", "默认种子门店"));
    repository.upsertStore(new StoreUpsertRequest("bw2", "BW002", "万达店", bw, "汕头", "", "", "营业中", "默认种子门店"));
    repository.upsertStore(new StoreUpsertRequest("bw5", "BW005", "环美店", bw, "汕头", "", "", "营业中", "默认种子门店"));
    repository.upsertStore(new StoreUpsertRequest("rx3", "RX003", "长江大学店", rx, "荆州", "", "", "营业中", "默认种子门店"));
  }

  private long brandId(String brandName) {
    return switch (brandName) {
      case "霸王茶姬" -> repository.ensureBrand("BW", "霸王茶姬", "#9A3340", 20);
      case "瑞幸咖啡" -> repository.ensureBrand("RX", "瑞幸咖啡", "#2E6BD6", 30);
      default -> repository.ensureBrand("RG", "茹果", "#EF7E3D", 10);
    };
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String textOrDefault(Object value, String fallback) {
    String text = text(value);
    return text.isBlank() ? fallback : text;
  }
}
