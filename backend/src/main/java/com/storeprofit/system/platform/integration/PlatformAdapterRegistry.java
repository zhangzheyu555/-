package com.storeprofit.system.platform.integration;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PlatformAdapterRegistry {
  private final List<PlatformAdapter> adapters;

  public PlatformAdapterRegistry(List<PlatformAdapter> adapters) {
    this.adapters = List.copyOf(adapters);
  }

  public List<PlatformAdapterStatus> statuses() {
    return adapters.stream()
        .sorted(Comparator.comparing(PlatformAdapter::platform))
        .map(PlatformAdapter::status)
        .toList();
  }
}
