package com.storeprofit.system.storage;

import com.storeprofit.system.appauth.AppAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
  private final StorageService storageService;
  private final AppAuthService appAuthService;

  public StorageController(StorageService storageService, AppAuthService appAuthService) {
    this.storageService = storageService;
    this.appAuthService = appAuthService;
  }

  @GetMapping
  public StorageValueResponse get(
      @RequestParam String key,
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    appAuthService.requireSession(authorization);
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    return new StorageValueResponse(storageService.get(key).orElse(null));
  }

  @PostMapping
  public StorageValueResponse set(
      @Valid @RequestBody StorageWriteRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    appAuthService.requireSession(authorization);
    storageService.set(request.key(), request.value());
    return new StorageValueResponse(request.value());
  }
}
