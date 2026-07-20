package com.storeprofit.system.platform.users;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.PasswordService;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeAssignment;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.platform.authorization.PermissionEffect;
import com.storeprofit.system.platform.authorization.UserPermissionOverride;
import com.storeprofit.system.platform.authorization.WorkspaceAccessProfile;
import com.storeprofit.system.platform.authorization.WorkspaceAccessResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService {
  private static final Set<String> ALLOWED_ROLES = Set.of(
      "BOSS", "FINANCE", "STORE_MANAGER", "WAREHOUSE", "SUPERVISOR", "EMPLOYEE");
  private static final Set<String> SUPERVISOR_SCOPE_DOMAINS = Set.of(
      DataScopeDomains.STORE,
      DataScopeDomains.WAREHOUSE,
      DataScopeDomains.INSPECTION,
      DataScopeDomains.EXAM,
      DataScopeDomains.PLATFORM
  );

  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final OrganizationRepository organizationRepository;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;
  private final AuthorizationService authorizationService;
  private final DataScopeService dataScopeService;
  private final WorkspaceAccessResolver workspaceAccessResolver;
  private final ObjectMapper objectMapper;

  @Autowired
  public UserManagementService(
      AuthRepository authRepository,
      PasswordService passwordService,
      OrganizationRepository organizationRepository,
      AccessControlService accessControl,
      AuditRepository auditRepository,
      AuthorizationService authorizationService,
      DataScopeService dataScopeService,
      WorkspaceAccessResolver workspaceAccessResolver,
      ObjectMapper objectMapper
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.organizationRepository = organizationRepository;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
    this.authorizationService = authorizationService;
    this.dataScopeService = dataScopeService;
    this.workspaceAccessResolver = workspaceAccessResolver;
    this.objectMapper = objectMapper;
  }

  /** Compatibility constructor retained for focused authorization tests. */
  public UserManagementService(
      AuthRepository authRepository,
      PasswordService passwordService,
      OrganizationRepository organizationRepository,
      AccessControlService accessControl,
      AuditRepository auditRepository,
      AuthorizationService authorizationService,
      DataScopeService dataScopeService,
      ObjectMapper objectMapper
  ) {
    this(
        authRepository,
        passwordService,
        organizationRepository,
        accessControl,
        auditRepository,
        authorizationService,
        dataScopeService,
        new WorkspaceAccessResolver(),
        objectMapper
    );
  }

  /** Compatibility constructor retained for the existing isolated password/account tests. */
  public UserManagementService(
      AuthRepository authRepository,
      PasswordService passwordService,
      OrganizationRepository organizationRepository,
      AccessControlService accessControl,
      AuditRepository auditRepository
  ) {
    this(authRepository, passwordService, organizationRepository, accessControl, auditRepository,
        null, null, new WorkspaceAccessResolver(), new ObjectMapper());
  }

  public List<UserResponse> users(AuthUser currentUser) {
    accessControl.requireUserManagementRead(currentUser);
    return authRepository.users(currentUser.tenantId()).stream()
        .map(user -> response(user))
        .toList();
  }

  public AuthorizationCatalogResponse authorizationCatalog(AuthUser currentUser) {
    accessControl.requireUserManagementRead(currentUser);
    requireAuthorizationServices();
    return new AuthorizationCatalogResponse(
        List.copyOf(authorizationService.catalog()),
        DataScopeDomains.ALL.stream().sorted().toList(),
        DataScopeModes.ALL_MODES.stream().sorted().toList()
    );
  }

  public UserAuthorizationResponse authorization(AuthUser currentUser, long userId) {
    accessControl.requireUserManagementRead(currentUser);
    requireAuthorizationServices();
    AuthUser target = target(currentUser, userId);
    return authorizationResponse(target, target.permissionVersion());
  }

  @Transactional
  public UserResponse create(AuthUser currentUser, UserCreateRequest request) {
    accessControl.requireUserManagementWrite(currentUser);
    String username = normalizeUsername(request == null ? null : request.username());
    if (authRepository.userExists(currentUser.tenantId(), username)) {
      throw new BusinessException("USER_EXISTS", "该登录账号已存在", HttpStatus.CONFLICT);
    }
    UserProfile profile = normalizeProfile(
        currentUser.tenantId(),
        request == null ? null : request.displayName(),
        request == null ? null : request.role(),
        request == null ? null : request.storeId(),
        request == null ? null : request.storeScope(),
        true
    );
    String password = normalizePassword(request == null ? null : request.password());
    authRepository.createUser(
        currentUser.tenantId(), username, passwordService.hash(password), profile.displayName(), profile.role(), profile.storeId());
    AuthUser created = authRepository.findByUsername(currentUser.tenantId(), username)
        .orElseThrow(() -> new BusinessException("USER_CREATE_FAILED", "账号创建失败", HttpStatus.INTERNAL_SERVER_ERROR));
    authRepository.replaceStoreScope(currentUser.tenantId(), created.id(), profile.storeScope());
    if (dataScopeService != null) {
      List<DataScopeAssignment> restrictedScopes = restrictedRoleTransitionScopes(profile.role(), profile.storeScope());
      if (!restrictedScopes.isEmpty()) {
        dataScopeService.replaceAssignments(
            currentUser.tenantId(),
            created.id(),
            restrictedScopes,
            currentUser.id()
        );
      }
    }
    requireAvailableWorkspace(created);
    writeAudit(currentUser, "创建账号", created.id(), profile.storeId(), "创建 " + created.username() + "（" + roleLabel(profile.role()) + "）");
    return response(created);
  }

  @Transactional
  public UserResponse update(AuthUser currentUser, long userId, UserUpdateRequest request) {
    accessControl.requireUserManagementWrite(currentUser);
    AuthUser target = target(currentUser, userId);
    List<String> previousStoreScope = normalizedStoreIds(
        authRepository.assignedStoreScope(currentUser.tenantId(), target.id()));
    AccountAuthorizationSnapshot before = accountSnapshot(target, previousStoreScope, target.permissionVersion());
    UserProfile profile = normalizeProfile(
        currentUser.tenantId(),
        request == null ? null : request.displayName(),
        request == null ? null : request.role(),
        request == null ? null : request.storeId(),
        request == null ? null : request.storeScope(),
        request != null && request.enabled()
    );
    if (target.id() == currentUser.id() && (!profile.enabled() || !profile.role().equals(target.role()))) {
      throw new BusinessException("SELF_ACCOUNT_PROTECTED", "不能停用自己或修改自己的角色", HttpStatus.CONFLICT);
    }
    if (AccessControlService.isBoss(target) && target.enabled()
        && (!"BOSS".equals(profile.role()) || !profile.enabled())
        && authRepository.activeBossCount(currentUser.tenantId()) <= 1) {
      throw new BusinessException("LAST_BOSS_PROTECTED", "系统至少需要保留一个启用的老板账号", HttpStatus.CONFLICT);
    }
    authRepository.updateUser(currentUser.tenantId(), target.id(), profile.displayName(), profile.role(), profile.storeId(), profile.enabled());
    authRepository.replaceStoreScope(currentUser.tenantId(), target.id(), profile.storeScope());
    boolean roleChanged = !AccessControlService.canonicalRole(target.role()).equals(profile.role());
    if (roleChanged && dataScopeService != null) {
      List<DataScopeAssignment> restrictedScopes = restrictedRoleTransitionScopes(profile.role(), profile.storeScope());
      if (!restrictedScopes.isEmpty()) {
        dataScopeService.replaceAssignments(
            currentUser.tenantId(), target.id(), restrictedScopes, currentUser.id());
      }
    }
    boolean authorizationChanged = roleChanged
        || !Objects.equals(blankToNull(target.storeId()), profile.storeId())
        || !previousStoreScope.equals(normalizedStoreIds(profile.storeScope()))
        || target.enabled() != profile.enabled();
    AuthUser updated = target(currentUser, target.id());
    if (profile.enabled() && (!target.enabled() || roleChanged)) {
      requireAvailableWorkspace(updated);
    }
    long permissionVersion = target.permissionVersion();
    if (authorizationChanged) {
      permissionVersion = invalidateAuthorization(currentUser.tenantId(), target.id(), target.permissionVersion());
    }
    updated = target(currentUser, target.id());
    AccountAuthorizationSnapshot after = accountSnapshot(updated, profile.storeScope(), permissionVersion);
    writeAudit(
        currentUser,
        "更新账号权限",
        target.id(),
        profile.storeId(),
        "更新 " + target.username() + " 的基础账号、角色、门店范围或启用状态",
        toJson(before),
        toJson(after)
    );
    return response(updated);
  }

  @Transactional
  public UserAuthorizationResponse updateAuthorization(
      AuthUser currentUser,
      long userId,
      UserAuthorizationUpdateRequest request
  ) {
    accessControl.requireUserManagementWrite(currentUser);
    requireAuthorizationServices();
    AuthUser target = target(currentUser, userId);
    if (AccessControlService.isBoss(target)) {
      throw new BusinessException(
          "BOSS_AUTHORIZATION_FIXED",
          "老板账号始终拥有全部权限和数据范围，不能配置个人授权",
          HttpStatus.CONFLICT
      );
    }
    UserAuthorizationResponse before = authorizationResponse(target, target.permissionVersion());
    List<UserPermissionOverride> overrides = normalizeOverrides(target, request == null ? null : request.overrides());
    List<DataScopeAssignment> dataScopes = normalizeDataScopes(
        target, request == null ? null : request.dataScopes());

    authorizationService.replaceUserOverrides(
        currentUser.tenantId(), target.id(), overrides, currentUser.id());
    dataScopeService.replaceAssignments(
        currentUser.tenantId(), target.id(), dataScopes, currentUser.id());
    long permissionVersion = invalidateAuthorization(
        currentUser.tenantId(), target.id(), target.permissionVersion());
    UserAuthorizationResponse after = authorizationResponse(target, permissionVersion);
    writeAudit(
        currentUser,
        "更新账号授权",
        target.id(),
        target.storeId(),
        "更新 " + target.username() + " 的数据范围和个人权限，旧登录已失效",
        toJson(before),
        toJson(after)
    );
    return after;
  }

  @Transactional
  public UserAccessProfileResponse updateAccessProfile(
      AuthUser currentUser,
      long userId,
      UserAccessProfileUpdateRequest request
  ) {
    accessControl.requireUserManagementWrite(currentUser);
    requireAuthorizationServices();
    AuthUser target = target(currentUser, userId);
    UserAccessProfileResponse before = new UserAccessProfileResponse(
        response(target), authorizationResponse(target, target.permissionVersion()));
    UserProfile profile = normalizeProfile(
        currentUser.tenantId(),
        request == null ? null : request.displayName(),
        request == null ? null : request.role(),
        request == null ? null : request.storeId(),
        request == null ? null : request.storeScope(),
        request != null && request.enabled()
    );
    if (target.id() == currentUser.id() && (!profile.enabled() || !profile.role().equals(target.role()))) {
      throw new BusinessException("SELF_ACCOUNT_PROTECTED", "不能停用自己或修改自己的角色", HttpStatus.CONFLICT);
    }
    if (AccessControlService.isBoss(target) && target.enabled()
        && (!"BOSS".equals(profile.role()) || !profile.enabled())
        && authRepository.activeBossCount(currentUser.tenantId()) <= 1) {
      throw new BusinessException("LAST_BOSS_PROTECTED", "系统至少需要保留一个启用的老板账号", HttpStatus.CONFLICT);
    }

    AuthUser proposed = withProfile(target, profile);
    List<UserPermissionOverride> overrides;
    List<DataScopeAssignment> dataScopes;
    if (AccessControlService.isBoss(proposed)) {
      if (request != null && request.overrides() != null && !request.overrides().isEmpty()) {
        throw new BusinessException(
            "BOSS_AUTHORIZATION_FIXED", "老板账号始终拥有全部权限，不能配置个人授权", HttpStatus.CONFLICT);
      }
      overrides = List.of();
      dataScopes = List.of();
    } else {
      overrides = normalizeOverrides(proposed, request == null ? null : request.overrides());
      dataScopes = normalizeDataScopes(proposed, request == null ? null : request.dataScopes());
    }

    authRepository.updateUser(
        currentUser.tenantId(), target.id(), profile.displayName(), profile.role(), profile.storeId(), profile.enabled());
    authRepository.replaceStoreScope(currentUser.tenantId(), target.id(), profile.storeScope());
    authorizationService.replaceUserOverrides(
        currentUser.tenantId(), target.id(), overrides, currentUser.id());
    dataScopeService.replaceAssignments(
        currentUser.tenantId(), target.id(), dataScopes, currentUser.id());

    boolean roleChanged = !AccessControlService.canonicalRole(target.role()).equals(profile.role());
    AuthUser persisted = target(currentUser, target.id());
    if (profile.enabled() && (!target.enabled() || roleChanged)) {
      requireAvailableWorkspace(persisted);
    }
    long permissionVersion = invalidateAuthorization(
        currentUser.tenantId(), target.id(), target.permissionVersion());
    AuthUser updated = target(currentUser, target.id());
    UserAccessProfileResponse after = new UserAccessProfileResponse(
        response(updated), authorizationResponse(updated, permissionVersion));
    writeAudit(
        currentUser,
        "统一更新账号权限",
        target.id(),
        profile.storeId(),
        "统一更新 " + target.username() + " 的角色、门店、数据范围和个人权限，旧登录已失效",
        toJson(before),
        toJson(after)
    );
    return after;
  }

  @Transactional
  public void resetPassword(AuthUser currentUser, long userId, UserPasswordResetRequest request) {
    accessControl.requireUserManagementWrite(currentUser);
    AuthUser target = target(currentUser, userId);
    if (AccessControlService.isBoss(target)) {
      if (target.id() != currentUser.id()) {
        throw new BusinessException("BOSS_PASSWORD_SELF_SERVICE_REQUIRED", "老板密码只能由本人主动修改", HttpStatus.FORBIDDEN);
      }
      String currentPassword = request == null ? null : request.currentPassword();
      if (currentPassword == null || !passwordService.matches(currentPassword, currentUser.passwordHash())) {
        throw new BusinessException("CURRENT_PASSWORD_INVALID", "当前密码不正确", HttpStatus.UNAUTHORIZED);
      }
    }
    String password = normalizePassword(request == null ? null : request.password());
    authRepository.updatePasswordByUserId(currentUser.tenantId(), target.id(), passwordService.hash(password));
    authRepository.deleteTokensForUser(currentUser.tenantId(), target.id());
    String method = AccessControlService.isBoss(target) ? "本人验证当前密码后主动修改" : "老板在账号权限中执行安全重置";
    writeAudit(currentUser, "重置账号密码", target.id(), target.storeId(),
        "操作人 " + currentUser.username() + "；方式：" + method + "；目标账号 " + target.username() + "；旧登录已失效");
  }

  private UserAuthorizationResponse authorizationResponse(AuthUser user, long permissionVersion) {
    requireAuthorizationServices();
    Set<String> effectivePermissions = authorizationService.effectivePermissions(user);
    Map<String, DataScope> effectiveDataScopes = dataScopeService.dataScopes(user);
    WorkspaceAccessProfile workspaceAccess = workspaceAccess(
        user, effectivePermissions, effectiveDataScopes);
    return new UserAuthorizationResponse(
        user.id(),
        AccessControlService.canonicalRole(user.role()),
        user.storeId(),
        permissionVersion,
        sortedPermissions(authorizationService.roleTemplatePermissions(user.tenantId(), user.role())),
        dataScopeService.assignmentsForUser(user.tenantId(), user.id()).stream()
            .sorted(Comparator.comparing(DataScopeAssignment::domainCode))
            .toList(),
        authorizationService.userOverrides(user.tenantId(), user.id()).stream()
            .sorted(Comparator.comparing(UserPermissionOverride::permissionCode))
            .toList(),
        sortedPermissions(effectivePermissions),
        workspaceAccess.availableWorkspaces(),
        workspaceAccess.defaultWorkspace(),
        workspaceAccess.status(),
        workspaceAccess.message()
    );
  }

  private List<UserPermissionOverride> normalizeOverrides(
      AuthUser target,
      List<UserPermissionOverrideRequest> requests
  ) {
    if (requests == null) {
      throw new BusinessException("AUTHORIZATION_OVERRIDES_REQUIRED", "请提交个人权限配置", HttpStatus.BAD_REQUEST);
    }
    Set<String> enabledPermissionCodes = authorizationService.catalog().stream()
        .filter(permission -> permission.enabled())
        .map(permission -> permission.permissionCode())
        .collect(java.util.stream.Collectors.toSet());
    LinkedHashSet<String> seenPermissionCodes = new LinkedHashSet<>();
    ArrayList<UserPermissionOverride> overrides = new ArrayList<>();
    for (UserPermissionOverrideRequest request : requests) {
      if (request == null) {
        throw new BusinessException("PERMISSION_INVALID", "个人权限配置不能为空", HttpStatus.BAD_REQUEST);
      }
      String permissionCode = requireText(
          request.permissionCode(), "PERMISSION_REQUIRED", "请选择权限");
      if (!enabledPermissionCodes.contains(permissionCode)) {
        throw new BusinessException(
            "PERMISSION_INVALID", "权限代码不存在或已停用", HttpStatus.BAD_REQUEST);
      }
      if (!seenPermissionCodes.add(permissionCode)) {
        throw new BusinessException(
            "PERMISSION_DUPLICATE", "同一权限不能重复配置", HttpStatus.BAD_REQUEST);
      }
      PermissionEffect effect;
      try {
        effect = PermissionEffect.valueOf(requireText(
            request.effect(), "PERMISSION_EFFECT_REQUIRED", "请选择授权结果").toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        throw new BusinessException(
            "PERMISSION_EFFECT_INVALID", "授权结果只能是 ALLOW 或 DENY", HttpStatus.BAD_REQUEST);
      }
      if (AccessControlService.isBoss(target)) {
        throw new BusinessException(
            "BOSS_AUTHORIZATION_FIXED", "老板账号始终拥有全部权限，不能配置个人授权", HttpStatus.CONFLICT);
      }
      if (PermissionCodes.STORE_MANAGE.equals(permissionCode) && effect == PermissionEffect.ALLOW) {
        throw new BusinessException(
            "STORE_MANAGEMENT_BOSS_ONLY", "门店管理仅限老板（系统管理员），不能授予其他账号", HttpStatus.BAD_REQUEST);
      }
      if (PermissionCodes.FINANCE_PROFIT_IMPORT.equals(permissionCode)
          && effect == PermissionEffect.ALLOW
          && !"FINANCE".equals(AccessControlService.canonicalRole(target.role()))) {
        throw new BusinessException(
            "FINANCE_IMPORT_FINANCE_ONLY", "月度经营数据导入仅限财务或老板，不能授予其他账号", HttpStatus.BAD_REQUEST);
      }
      String targetRole = AccessControlService.canonicalRole(target.role());
      if ("SUPERVISOR".equals(targetRole)
          && effect == PermissionEffect.ALLOW
          && Set.of(
              PermissionCodes.SYSTEM_USER_MANAGE,
              PermissionCodes.STORE_MANAGE,
              PermissionCodes.FINANCE_PROFIT_WRITE,
              PermissionCodes.FINANCE_PROFIT_IMPORT,
              PermissionCodes.FINANCE_PROFIT_DELETE,
              PermissionCodes.SALARY_READ,
              PermissionCodes.SALARY_EDIT,
              PermissionCodes.SALARY_REVIEW,
              PermissionCodes.SALARY_PAY,
              PermissionCodes.WAREHOUSE_CENTRAL_READ,
              PermissionCodes.WAREHOUSE_CENTRAL_MANAGE,
              PermissionCodes.WAREHOUSE_PURCHASE,
              PermissionCodes.WAREHOUSE_TRANSFER_REQUEST,
              PermissionCodes.WAREHOUSE_TRANSFER_APPROVE,
              PermissionCodes.WAREHOUSE_TRANSFER_SHIP,
              PermissionCodes.WAREHOUSE_TRANSFER_RECEIVE,
              PermissionCodes.WAREHOUSE_REQUISITION_PROCESS,
              PermissionCodes.WAREHOUSE_CONFIGURE).contains(permissionCode)) {
        throw new BusinessException(
            "SUPERVISOR_PERMISSION_BOUNDARY",
            "督导不能获得财务写入、工资、账号权限、门店管理或总仓操作权限",
            HttpStatus.BAD_REQUEST);
      }
      if ("EMPLOYEE".equals(targetRole)
          && effect == PermissionEffect.ALLOW
          && !Set.of(PermissionCodes.EXAM_LEARN, PermissionCodes.EMPLOYEE_ASSISTANT_USE).contains(permissionCode)) {
        throw new BusinessException(
            "EMPLOYEE_PERMISSION_CEILING",
              "员工账号只能授予本人培训考试和员工服务助手权限",
            HttpStatus.BAD_REQUEST
        );
      }
      overrides.add(new UserPermissionOverride(permissionCode, effect));
    }
    return List.copyOf(overrides);
  }

  private List<DataScopeAssignment> normalizeDataScopes(
      AuthUser target,
      List<UserDataScopeRequest> requests
  ) {
    if (requests == null) {
      throw new BusinessException("DATA_SCOPES_REQUIRED", "请提交数据范围配置", HttpStatus.BAD_REQUEST);
    }
    Set<String> validStoreIds = organizationRepository.stores(target.tenantId()).stream()
        .map(store -> store.id())
        .collect(java.util.stream.Collectors.toSet());
    Set<String> validWarehouseIds = dataScopeService.enabledWarehouseIds(target.tenantId());
    LinkedHashSet<String> seenDomains = new LinkedHashSet<>();
    ArrayList<DataScopeAssignment> assignments = new ArrayList<>();
    String role = AccessControlService.canonicalRole(target.role());
    for (UserDataScopeRequest request : requests) {
      if (request == null) {
        throw new BusinessException("DATA_SCOPE_INVALID", "数据范围配置不能为空", HttpStatus.BAD_REQUEST);
      }
      String domain = requireText(
          request.domainCode(), "DATA_SCOPE_DOMAIN_REQUIRED", "请选择数据范围业务域")
          .toUpperCase(Locale.ROOT);
      String mode = requireText(
          request.mode(), "DATA_SCOPE_MODE_REQUIRED", "请选择数据范围类型")
          .toUpperCase(Locale.ROOT);
      if (!DataScopeDomains.ALL.contains(domain)) {
        throw new BusinessException(
            "DATA_SCOPE_DOMAIN_INVALID", "数据范围业务域不正确", HttpStatus.BAD_REQUEST);
      }
      if (!DataScopeModes.ALL_MODES.contains(mode)) {
        throw new BusinessException(
            "DATA_SCOPE_MODE_INVALID", "数据范围类型不正确", HttpStatus.BAD_REQUEST);
      }
      if (!seenDomains.add(domain)) {
        throw new BusinessException(
            "DATA_SCOPE_DUPLICATE", "同一业务域不能重复配置", HttpStatus.BAD_REQUEST);
      }

      List<String> storeIds = normalizedStoreIds(request.storeIds());
      List<String> warehouseIds = normalizedStoreIds(request.warehouseIds());
      if (DataScopeModes.STORE_LIST.equals(mode)) {
        if (storeIds.isEmpty()) {
          throw new BusinessException(
              "DATA_SCOPE_STORE_REQUIRED", "指定门店范围不能为空", HttpStatus.BAD_REQUEST);
        }
        if (!validStoreIds.containsAll(storeIds)) {
          throw new BusinessException(
              "DATA_SCOPE_STORE_INVALID", "数据范围中包含不存在的门店", HttpStatus.BAD_REQUEST);
        }
      } else if (!storeIds.isEmpty()) {
        throw new BusinessException(
            "DATA_SCOPE_STORE_UNEXPECTED", "只有指定门店范围可以提交门店编号", HttpStatus.BAD_REQUEST);
      }
      if (DataScopeModes.WAREHOUSE_LIST.equals(mode)) {
        if (!DataScopeDomains.WAREHOUSE.equals(domain)) {
          throw new BusinessException(
              "DATA_SCOPE_MODE_DOMAIN_MISMATCH", "指定仓库范围只能用于仓库业务域", HttpStatus.BAD_REQUEST);
        }
        if (warehouseIds.isEmpty()) {
          throw new BusinessException(
              "DATA_SCOPE_WAREHOUSE_REQUIRED", "指定仓库范围不能为空", HttpStatus.BAD_REQUEST);
        }
        if (!validWarehouseIds.containsAll(warehouseIds)) {
          throw new BusinessException(
              "DATA_SCOPE_WAREHOUSE_INVALID", "数据范围中包含不存在或已停用的仓库", HttpStatus.BAD_REQUEST);
        }
      } else if (!warehouseIds.isEmpty()) {
        throw new BusinessException(
            "DATA_SCOPE_WAREHOUSE_UNEXPECTED", "只有指定仓库范围可以提交仓库编号", HttpStatus.BAD_REQUEST);
      }
      if (DataScopeModes.CENTRAL_WAREHOUSE.equals(mode)
          && !DataScopeDomains.WAREHOUSE.equals(domain)) {
        throw new BusinessException(
            "DATA_SCOPE_MODE_DOMAIN_MISMATCH", "总仓库范围只能用于仓库业务域", HttpStatus.BAD_REQUEST);
      }
      if (DataScopeModes.SELF.equals(mode) && !DataScopeDomains.EXAM.equals(domain)) {
        throw new BusinessException(
            "DATA_SCOPE_MODE_DOMAIN_MISMATCH", "本人任务范围只能用于培训考试业务域", HttpStatus.BAD_REQUEST);
      }
      if (DataScopeModes.OWN_STORE.equals(mode)
          && (target.storeId() == null || target.storeId().isBlank())) {
        throw new BusinessException(
            "OWN_STORE_REQUIRED", "当前账号没有绑定门店，不能使用本店范围", HttpStatus.BAD_REQUEST);
      }
      if (DataScopeModes.OWN_STORE.equals(mode)
          && !validStoreIds.contains(target.storeId().trim())) {
        throw new BusinessException(
            "OWN_STORE_INVALID", "当前账号绑定的门店不存在", HttpStatus.BAD_REQUEST);
      }
      validateRoleScope(role, domain, mode);
      assignments.add(new DataScopeAssignment(domain, mode, storeIds, warehouseIds));
    }
    // PUT accepts a partial domain list, but persistence is a full replacement. Materialize every
    // omitted domain as NONE so an empty/partial request can never reactivate the compatibility
    // fallback in DataScopeService.
    DataScopeDomains.ALL.stream()
        .filter(domain -> !seenDomains.contains(domain))
        .sorted()
        .map(domain -> new DataScopeAssignment(domain, DataScopeModes.NONE, List.of()))
        .forEach(assignments::add);
    if ("STORE_MANAGER".equals(role)) {
      boolean hasRequiredStoreScope = assignments.stream()
          .anyMatch(assignment -> DataScopeDomains.STORE.equals(assignment.domainCode())
              && DataScopeModes.OWN_STORE.equals(assignment.mode()));
      if (!hasRequiredStoreScope) {
        throw new BusinessException(
            "STORE_MANAGER_STORE_SCOPE_REQUIRED",
            "店长账号的门店数据范围必须为本店",
            HttpStatus.BAD_REQUEST
        );
      }
    }
    return assignments.stream()
        .sorted(Comparator.comparing(DataScopeAssignment::domainCode))
        .toList();
  }

  private void validateRoleScope(String role, String domain, String mode) {
    if ("BOSS".equals(role) && !DataScopeModes.ALL.equals(mode)) {
      throw new BusinessException(
          "BOSS_DATA_SCOPE_FIXED", "老板账号的数据范围始终为全部", HttpStatus.CONFLICT);
    }
    if ("STORE_MANAGER".equals(role)
        && !Set.of(DataScopeModes.OWN_STORE, DataScopeModes.NONE).contains(mode)) {
      throw new BusinessException(
          "STORE_MANAGER_SCOPE_INVALID", "店长数据范围只能是绑定门店或无权限", HttpStatus.BAD_REQUEST);
    }
    if ("EMPLOYEE".equals(role)) {
      boolean valid = DataScopeDomains.EXAM.equals(domain)
          ? Set.of(DataScopeModes.SELF, DataScopeModes.NONE).contains(mode)
          : DataScopeModes.NONE.equals(mode);
      if (!valid) {
        throw new BusinessException(
            "EMPLOYEE_SCOPE_CEILING", "员工账号只能访问本人培训考试数据", HttpStatus.BAD_REQUEST);
      }
    }
    if ("SUPERVISOR".equals(role)) {
      boolean valid = SUPERVISOR_SCOPE_DOMAINS.contains(domain)
          ? (DataScopeDomains.WAREHOUSE.equals(domain)
              ? Set.of(DataScopeModes.STORE_LIST, DataScopeModes.WAREHOUSE_LIST, DataScopeModes.NONE).contains(mode)
              : Set.of(DataScopeModes.STORE_LIST, DataScopeModes.NONE).contains(mode))
          : DataScopeModes.NONE.equals(mode);
      if (!valid) {
        throw new BusinessException(
            "SUPERVISOR_SCOPE_BOUNDARY",
            "督导角色只能配置已授权门店或仓库的数据范围",
            HttpStatus.BAD_REQUEST
        );
      }
    }
  }

  private List<DataScopeAssignment> restrictedRoleTransitionScopes(String role) {
    return restrictedRoleTransitionScopes(role, List.of());
  }

  private List<DataScopeAssignment> restrictedRoleTransitionScopes(String role, List<String> storeScope) {
    if ("STORE_MANAGER".equals(role)) {
      return List.of(
          DataScopeDomains.STORE,
          DataScopeDomains.FINANCE,
          DataScopeDomains.SALARY,
          DataScopeDomains.WAREHOUSE,
          DataScopeDomains.INSPECTION,
          DataScopeDomains.EXAM
      ).stream()
          .map(domain -> new DataScopeAssignment(domain, DataScopeModes.OWN_STORE, List.of()))
          .toList();
    }
    if ("SUPERVISOR".equals(role)) {
      List<String> storeIds = normalizedStoreIds(storeScope);
      return DataScopeDomains.ALL.stream()
          .map(domain -> SUPERVISOR_SCOPE_DOMAINS.contains(domain) && !storeIds.isEmpty()
              ? new DataScopeAssignment(domain, DataScopeModes.STORE_LIST, storeIds)
              : new DataScopeAssignment(domain, DataScopeModes.NONE, List.of()))
          .toList();
    }
    if ("EMPLOYEE".equals(role)) {
      return List.of(new DataScopeAssignment(
          DataScopeDomains.EXAM, DataScopeModes.SELF, List.of()));
    }
    return List.of();
  }

  private List<String> sortedPermissions(Set<String> permissions) {
    return permissions == null ? List.of() : permissions.stream().sorted().toList();
  }

  private void requireAuthorizationServices() {
    if (authorizationService == null || dataScopeService == null) {
      throw new BusinessException(
          "AUTHORIZATION_SERVICE_UNAVAILABLE", "账号授权服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  private WorkspaceAccessProfile workspaceAccess(AuthUser user) {
    List<String> assignedStoreIds = authRepository.assignedStoreScope(user.tenantId(), user.id());
    if (authorizationService == null || dataScopeService == null) {
      return workspaceAccessResolver.resolve(
          user,
          AuthorizationService.legacyTemplatePermissions(user.role()),
          legacyWorkspaceScopes(user, assignedStoreIds),
          assignedStoreIds
      );
    }
    return workspaceAccess(
        user,
        authorizationService.effectivePermissions(user),
        dataScopeService.dataScopes(user)
    );
  }

  private WorkspaceAccessProfile workspaceAccess(
      AuthUser user,
      Set<String> effectivePermissions,
      Map<String, DataScope> effectiveDataScopes
  ) {
    return workspaceAccessResolver.resolve(
        user,
        effectivePermissions,
        effectiveDataScopes,
        authRepository.assignedStoreScope(user.tenantId(), user.id())
    );
  }

  private Map<String, DataScope> legacyWorkspaceScopes(
      AuthUser user,
      List<String> assignedStoreIds
  ) {
    java.util.LinkedHashMap<String, DataScope> scopes = new java.util.LinkedHashMap<>();
    String role = AccessControlService.canonicalRole(user.role());
    if ("BOSS".equals(role)) {
      DataScopeDomains.ALL.forEach(domain -> scopes.put(domain, DataScope.all()));
      return Map.copyOf(scopes);
    }
    if ("STORE_MANAGER".equals(role) && user.storeId() != null && !user.storeId().isBlank()) {
      for (String domain : List.of(
          DataScopeDomains.STORE,
          DataScopeDomains.FINANCE,
          DataScopeDomains.SALARY,
          DataScopeDomains.WAREHOUSE,
          DataScopeDomains.INSPECTION,
          DataScopeDomains.EXAM)) {
        scopes.put(domain, new DataScope(DataScopeModes.OWN_STORE, List.of(user.storeId())));
      }
    } else if ("WAREHOUSE".equals(role)) {
      scopes.put(DataScopeDomains.WAREHOUSE,
          new DataScope(DataScopeModes.CENTRAL_WAREHOUSE, List.of()));
    } else if ("EMPLOYEE".equals(role)) {
      scopes.put(DataScopeDomains.EXAM, new DataScope(DataScopeModes.SELF, List.of()));
    } else if ("FINANCE".equals(role)) {
      DataScope financeScope = assignedStoreIds == null || assignedStoreIds.isEmpty()
          ? DataScope.all()
          : new DataScope(DataScopeModes.STORE_LIST, assignedStoreIds);
      scopes.put(DataScopeDomains.FINANCE, financeScope);
      scopes.put(DataScopeDomains.STORE, financeScope);
    } else if ("SUPERVISOR".equals(role) && assignedStoreIds != null && !assignedStoreIds.isEmpty()) {
      DataScope supervisorScope = new DataScope(DataScopeModes.STORE_LIST, assignedStoreIds);
      SUPERVISOR_SCOPE_DOMAINS.forEach(domain -> scopes.put(domain, supervisorScope));
    }
    return Map.copyOf(scopes);
  }

  private void requireAvailableWorkspace(AuthUser user) {
    WorkspaceAccessProfile access = workspaceAccess(user);
    if (access.availableWorkspaces().isEmpty()) {
      throw new BusinessException(
          "ACCOUNT_WORKSPACE_REQUIRED",
          "启用账号前必须配置至少一个可用工作台：" + access.message(),
          HttpStatus.BAD_REQUEST
      );
    }
  }

  private AuthUser withProfile(AuthUser user, UserProfile profile) {
    return new AuthUser(
        user.id(),
        user.tenantId(),
        user.tenantName(),
        user.username(),
        user.passwordHash(),
        profile.displayName(),
        profile.role(),
        profile.storeId(),
        profile.enabled(),
        user.permissionVersion()
    );
  }

  private UserResponse response(AuthUser user) {
    WorkspaceAccessProfile workspaceAccess = workspaceAccess(user);
    return new UserResponse(
        user.id(),
        user.tenantId(),
        user.tenantName(),
        user.username(),
        user.displayName(),
        user.role(),
        roleLabel(user.role()),
        user.storeId(),
        user.enabled(),
        authRepository.storeScope(user.tenantId(), user.id(), user.role(), user.storeId()),
        workspaceAccess.availableWorkspaces(),
        workspaceAccess.defaultWorkspace(),
        workspaceAccess.status(),
        workspaceAccess.message()
    );
  }

  private AuthUser target(AuthUser currentUser, long userId) {
    return authRepository.user(currentUser.tenantId(), userId)
        .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "未找到账号", HttpStatus.NOT_FOUND));
  }

  private UserProfile normalizeProfile(
      long tenantId,
      String displayName,
      String rawRole,
      String rawStoreId,
      List<String> rawStoreScope,
      boolean enabled
  ) {
    String name = requireText(displayName, "DISPLAY_NAME_REQUIRED", "请填写姓名或显示名称");
    if (name.length() > 120) {
      throw new BusinessException("DISPLAY_NAME_INVALID", "显示名称不能超过 120 个字符", HttpStatus.BAD_REQUEST);
    }
    String role = normalizeRole(rawRole);
    LinkedHashSet<String> scope = new LinkedHashSet<>();
    if (rawStoreScope != null) {
      for (String value : rawStoreScope) {
        String storeId = blankToNull(value);
        if (storeId != null) {
          scope.add(storeId);
        }
      }
    }
    String directStoreId = blankToNull(rawStoreId);
    if (directStoreId != null) {
      scope.add(directStoreId);
    }
    if (AccessControlService.isBossRole(role)) {
      return new UserProfile(name, role, null, List.of(), enabled);
    }
    if ("STORE_MANAGER".equals(role) && scope.size() != 1) {
      throw new BusinessException(
          "STORE_MANAGER_SINGLE_STORE_REQUIRED",
          "店长账号必须且只能绑定一家门店",
          HttpStatus.BAD_REQUEST
      );
    }
    if (scope.isEmpty()) {
      return new UserProfile(name, role, null, List.of(), enabled);
    }
    Set<String> validStoreIds = organizationRepository.stores(tenantId).stream()
        .map(store -> store.id())
        .collect(java.util.stream.Collectors.toSet());
    if (!validStoreIds.containsAll(scope)) {
      throw new BusinessException("STORE_SCOPE_INVALID", "门店范围中包含不存在的门店", HttpStatus.BAD_REQUEST);
    }
    String storeId = directStoreId == null ? scope.getFirst() : directStoreId;
    if ("STORE_MANAGER".equals(role) && !scope.contains(storeId)) {
      throw new BusinessException(
          "STORE_MANAGER_STORE_MISMATCH",
          "店长绑定门店必须与门店范围一致",
          HttpStatus.BAD_REQUEST
      );
    }
    return new UserProfile(name, role, storeId, List.copyOf(scope), enabled);
  }

  private String normalizeUsername(String value) {
    String username = requireText(value, "USERNAME_REQUIRED", "请填写登录账号").toLowerCase(Locale.ROOT);
    if (!username.matches("[a-z0-9_.-]{3,40}")) {
      throw new BusinessException("USERNAME_INVALID", "登录账号只能包含小写字母、数字、点、下划线或短横线，长度 3 至 40 位", HttpStatus.BAD_REQUEST);
    }
    return username;
  }

  private String normalizeRole(String value) {
    String requested = requireText(value, "ROLE_REQUIRED", "请选择角色").trim().toUpperCase(Locale.ROOT);
    if (Set.of("ADMIN", "OWNER", "OPS", "OPERATIONS").contains(requested)) {
      throw new BusinessException(
          "ROLE_LEGACY_REJECTED",
          "运营、OPS、ADMIN、OWNER 仅用于历史兼容，不能新建、编辑或授权，请选择当前正式角色",
          HttpStatus.BAD_REQUEST
      );
    }
    String role = AccessControlService.canonicalRole(requested);
    if (!ALLOWED_ROLES.contains(role)) {
      throw new BusinessException("ROLE_INVALID", "角色不正确", HttpStatus.BAD_REQUEST);
    }
    return role;
  }

  private String normalizePassword(String value) {
    String password = requireText(value, "PASSWORD_REQUIRED", "请填写密码");
    if (password.length() < 8 || password.length() > 128) {
      throw new BusinessException("PASSWORD_INVALID", "密码长度必须为 8 至 128 位", HttpStatus.BAD_REQUEST);
    }
    return password;
  }

  private void writeAudit(AuthUser user, String action, long targetId, String storeId, String reason) {
    writeAudit(user, action, targetId, storeId, reason, null, null);
  }

  private void writeAudit(
      AuthUser user,
      String action,
      long targetId,
      String storeId,
      String reason,
      String beforeJson,
      String afterJson
  ) {
    auditRepository.writeLog(user, new AuditLogRequest(
        action,
        "auth_user",
        Long.toString(targetId),
        storeId,
        null,
        reason,
        beforeJson,
        afterJson
    ));
  }

  private long invalidateAuthorization(long tenantId, long userId, long currentVersion) {
    if (authorizationService != null) {
      return authorizationService.incrementPermissionVersionAndDeleteTokens(tenantId, userId);
    }
    // Only used by legacy isolated tests; production always has AuthorizationService injected.
    authRepository.deleteTokensForUser(tenantId, userId);
    return currentVersion + 1;
  }

  private AccountAuthorizationSnapshot accountSnapshot(
      AuthUser user,
      List<String> storeScope,
      long permissionVersion
  ) {
    return new AccountAuthorizationSnapshot(
        AccessControlService.canonicalRole(user.role()),
        blankToNull(user.storeId()),
        user.enabled(),
        normalizedStoreIds(storeScope),
        permissionVersion
    );
  }

  private List<String> normalizedStoreIds(List<String> storeIds) {
    if (storeIds == null || storeIds.isEmpty()) {
      return List.of();
    }
    return storeIds.stream()
        .map(this::blankToNull)
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new BusinessException(
          "AUTHORIZATION_AUDIT_FAILED",
          "账号权限审计记录生成失败",
          HttpStatus.INTERNAL_SERVER_ERROR
      );
    }
  }

  private String roleLabel(String role) {
    return switch (AccessControlService.canonicalRole(role)) {
      case "BOSS" -> "老板（系统管理员）";
      case "FINANCE" -> "财务";
      case "SUPERVISOR" -> "督导";
      case "STORE_MANAGER" -> "店长";
      case "WAREHOUSE" -> "仓库管理员";
      case "EMPLOYEE" -> "员工";
      default -> role;
    };
  }

  private String requireText(String value, String code, String message) {
    String normalized = blankToNull(value);
    if (normalized == null) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record UserProfile(String displayName, String role, String storeId, List<String> storeScope, boolean enabled) {
  }

  private record AccountAuthorizationSnapshot(
      String role,
      String storeId,
      boolean enabled,
      List<String> storeScope,
      long permissionVersion
  ) {
  }
}
