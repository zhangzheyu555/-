package com.storeprofit.system.organization;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class StoreController {
  private final AuthService authService;
  private final OrganizationService organizationService;

  public StoreController(AuthService authService, OrganizationService organizationService) {
    this.authService = authService;
    this.organizationService = organizationService;
  }

  @GetMapping
  public ApiResponse<List<StoreResponse>> list(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(defaultValue = "false") boolean knowledgeBaseScope
  ) {
    AuthUser user = authService.requireUser(authorization);
    return ApiResponse.ok(knowledgeBaseScope
        ? organizationService.knowledgeBaseStores(user)
        : organizationService.stores(user));
  }

  @GetMapping("/options")
  public ApiResponse<StoreArchiveOptionsResponse> options(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(organizationService.storeOptions(authService.requireUser(authorization)));
  }

  @PostMapping
  public ApiResponse<StoreResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody StoreUpsertRequest request
  ) {
    return ApiResponse.ok(organizationService.createStore(authService.requireUser(authorization), request));
  }

  @PutMapping
  public ApiResponse<StoreResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody StoreUpsertRequest request
  ) {
    return ApiResponse.ok(organizationService.updateStore(authService.requireUser(authorization), request));
  }

  @PutMapping("/{id}/status")
  public ApiResponse<StoreResponse> changeStatus(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody StoreStatusChangeRequest request
  ) {
    return ApiResponse.ok(organizationService.changeStoreStatus(
        authService.requireUser(authorization), id, request));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    organizationService.deleteStore(authService.requireUser(authorization), id);
    return ApiResponse.ok();
  }
}
