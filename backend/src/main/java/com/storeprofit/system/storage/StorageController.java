package com.storeprofit.system.storage;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
  private final StorageService storageService;

  public StorageController(StorageService storageService) {
    this.storageService = storageService;
  }

  @GetMapping
  public StorageValueResponse get(@RequestParam String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    return new StorageValueResponse(storageService.get(key).orElse(null));
  }

  @PostMapping
  public StorageValueResponse set(@Valid @RequestBody StorageWriteRequest request) {
    storageService.set(request.key(), request.value());
    return new StorageValueResponse(request.value());
  }
}
