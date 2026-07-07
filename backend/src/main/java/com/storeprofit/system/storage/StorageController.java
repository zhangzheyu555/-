package com.storeprofit.system.storage;

import jakarta.validation.Valid;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
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
  private final AuthService authService;
  private final StorageService storageService;

  public StorageController(AuthService authService, StorageService storageService) {
    this.authService = authService;
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
  public StorageValueResponse set(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody StorageWriteRequest request
  ) {
    AuthUser user = authService.requireUser(authorization);
    storageService.set(user, request.key(), request.value());
    return new StorageValueResponse(request.value());
  }
}
