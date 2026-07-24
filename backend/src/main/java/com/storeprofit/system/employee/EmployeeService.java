package com.storeprofit.system.employee;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.PasswordService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class EmployeeService {
  private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

  private final EmployeeRepository employeeRepository;
  private final AccessControlService accessControl;
  private final BusinessScopeResolver businessScopeResolver;
  private final PasswordService passwordService;
  private final AuthRepository authRepository;
  private final AuditRepository auditRepository;

  @Autowired
  public EmployeeService(
      EmployeeRepository employeeRepository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver,
      PasswordService passwordService,
      AuthRepository authRepository,
      AuditRepository auditRepository
  ) {
    this.employeeRepository = employeeRepository;
    this.accessControl = accessControl;
    this.businessScopeResolver = businessScopeResolver;
    this.passwordService = passwordService;
    this.authRepository = authRepository;
    this.auditRepository = auditRepository;
  }

  /** Compatibility constructor retained for focused employee-account tests. */
  public EmployeeService(
      EmployeeRepository employeeRepository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver,
      PasswordService passwordService,
      AuthRepository authRepository
  ) {
    this(employeeRepository, accessControl, businessScopeResolver, passwordService, authRepository, null);
  }

  public EmployeeService(
      EmployeeRepository employeeRepository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver
  ) {
    this(employeeRepository, accessControl, businessScopeResolver, null, null, null);
  }

  public EmployeeService(
      EmployeeRepository employeeRepository,
      AccessControlService accessControl
  ) {
    this(employeeRepository, accessControl, null);
  }

  /** Compatibility constructor retained for isolated service tests. */
  public EmployeeService(EmployeeRepository employeeRepository) {
    this(employeeRepository, null, null);
  }

  public List<EmployeeResponse> records(AuthUser user, Long brandId, String storeId, String status) {
    requireEmployeeRead(user);
    if (businessScopeResolver != null) {
      BusinessScope businessScope = businessScopeResolver.resolve(
          user, DataScopeDomains.STORE, storeId, brandId, "查看员工档案");
      storeId = businessScope.storeId();
      brandId = businessScope.brandId();
    }
    if (accessControl != null) {
      String requestedStoreId = blankToNull(storeId);
      if (requestedStoreId != null) {
        accessControl.requireStoreAccess(
            user, DataScopeDomains.STORE, requestedStoreId, "查看员工档案");
        return employeeRepository.records(
            user.tenantId(), brandId, requestedStoreId, blankToNull(status));
      }
      if (accessControl.hasAllDataScope(user, DataScopeDomains.STORE)) {
        return employeeRepository.records(
            user.tenantId(), brandId, null, blankToNull(status));
      }
      Set<String> allowedStoreIds = accessControl.allowedStoreIds(user, DataScopeDomains.STORE);
      return employeeRepository.records(
          user.tenantId(), brandId, null, blankToNull(status), allowedStoreIds);
    }
    if ("STORE_MANAGER".equals(user.role())) {
      String scopedStoreId = requireManagerStore(user);
      if (storeId != null && !storeId.isBlank() && !scopedStoreId.equals(storeId.trim())) {
        return List.of();
      }
      return employeeRepository.records(user.tenantId(), brandId, scopedStoreId, blankToNull(status));
    }
    return employeeRepository.records(user.tenantId(), brandId, blankToNull(storeId), blankToNull(status));
  }

  private void requireEmployeeRead(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireEmployeeRead(user);
      return;
    }
    if (AccessControlService.isBoss(user)
        || AuthorizationService.legacyTemplatePermissions(user == null ? null : user.role())
            .contains(PermissionCodes.EMPLOYEE_READ)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "No permission to read employees", HttpStatus.FORBIDDEN);
  }

  private String requireManagerStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "Store manager is not bound to a store", HttpStatus.FORBIDDEN);
    }
    return user.storeId().trim();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /* ---------------- 档案增删改 + 开号 + 导入（docs/员工信息管理设计文档.md） ---------------- */

  public EmployeeResponse detail(AuthUser user, String id) {
    requireEmployeeRead(user);
    EmployeeResponse record = employeeForCurrentTenant(user, id, "查看员工档案");
    requireStoreWritable(user, record.storeId(), "查看员工档案");
    return maskForRole(user, record);
  }

  /** 员工本人档案（员工工作台「我的资料」用）。 */
  public EmployeeResponse me(AuthUser user) {
    return employeeRepository.byAuthUserId(user.tenantId(), user.id())
        .map(record -> maskForRole(user, record))
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "当前账号未关联员工档案", HttpStatus.NOT_FOUND));
  }

  @Transactional
  public EmployeeResponse create(AuthUser user, EmployeeUpsertRequest request) {
    requireEmployeeManage(user);
    validate(user.tenantId(), request);
    requireStoreWritable(user, request.storeId(), "新增员工档案");
    String id = employeeId(user.tenantId(), request.storeId(), request.name().trim());
    if (employeeRepository.exists(user.tenantId(), id)) {
      throw new BusinessException("DUPLICATE", "该门店已有同名员工：" + request.name(), HttpStatus.CONFLICT);
    }
    employeeRepository.upsertProfile(user.tenantId(), id, request, "MANUAL_ENTRY");
    EmployeeResponse created = employeeRepository.record(user.tenantId(), id).orElseThrow();
    auditEmployeeChange(user, "新增员工档案", created, "员工档案新增成功", null, employeeAuditState(created));
    return created;
  }

  @Transactional
  public EmployeeResponse update(AuthUser user, String id, EmployeeUpsertRequest request) {
    requireEmployeeManage(user);
    validate(user.tenantId(), request);
    EmployeeResponse existing = employeeForCurrentTenant(user, id, "修改员工档案");
    requireStoreWritable(user, existing.storeId(), "修改员工档案");
    if (!existing.storeId().equals(request.storeId()) || !existing.name().equals(request.name().trim())) {
      throw new BusinessException("IMMUTABLE", "门店与姓名不支持直接修改（调店/更名请离职后重建档案）",
          HttpStatus.BAD_REQUEST);
    }
    employeeRepository.upsertProfile(user.tenantId(), id, request, "MANUAL_ENTRY");
    EmployeeResponse updated = employeeRepository.record(user.tenantId(), id).orElseThrow();
    auditEmployeeChange(
        user,
        "修改员工档案",
        updated,
        "员工档案修改成功",
        employeeAuditState(existing),
        employeeAuditState(updated)
    );
    return updated;
  }

  /** 删除 = 离职留档（长期保留，工资/考试历史不受影响）+ 禁用账号。 */
  @Transactional
  public void remove(AuthUser user, String id) {
    requireEmployeeManage(user);
    EmployeeResponse existing = employeeForCurrentTenant(user, id, "员工离职");
    requireStoreWritable(user, existing.storeId(), "员工离职");
    employeeRepository.updateStatus(user.tenantId(), id, "离职");
    if (existing.authUserId() != null) {
      employeeRepository.setAccountEnabled(existing.authUserId(), false);
    }
    auditEmployeeChange(
        user,
        "员工离职",
        existing,
        "员工离职办理成功",
        employeeAuditState(existing),
        employeeAuditState(existing, "离职", existing.authUserId() != null ? Boolean.FALSE : existing.accountEnabled())
    );
  }

  /** 开号：账号 = 所在门店店长账号-序号（如 ruguo1-1）；兼职员工不开号。 */
  @Transactional
  public EmployeeAccountResponse createAccount(AuthUser user, String id) {
    requireEmployeeManage(user);
    EmployeeResponse employee = employeeForCurrentTenant(user, id, "开员工账号");
    requireStoreWritable(user, employee.storeId(), "开员工账号");
    if (employee.authUserId() != null) {
      throw new BusinessException("DUPLICATE", "该员工已有账号：" + employee.accountUsername(), HttpStatus.CONFLICT);
    }
    if ("兼职".equals(employee.employmentType())) {
      throw new BusinessException("PART_TIME", "兼职员工不开登录账号（转全职后再开）", HttpStatus.BAD_REQUEST);
    }
    if (!"在职".equals(employee.status())) {
      throw new BusinessException("INACTIVE", "只有在职员工可开账号", HttpStatus.BAD_REQUEST);
    }
    String prefix = employeeRepository.managerUsername(user.tenantId(), employee.storeId())
        .orElseThrow(() -> new BusinessException("NO_MANAGER",
            "该门店尚未配置店长账号，无法按「店长账号-序号」规则开号", HttpStatus.BAD_REQUEST));
    String username = prefix + "-" + (employeeRepository.maxAccountSeq(user.tenantId(), prefix) + 1);
    if (passwordService == null || authRepository == null) {
      throw new BusinessException("INTERNAL", "账号服务不可用", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    String initialPassword = passwordService.generateOneTimePassword();
    createAuthUser(user.tenantId(), username, employee, initialPassword);
    long authUserId = employeeRepository.userIdByUsername(user.tenantId(), username)
        .orElseThrow(() -> new BusinessException("INTERNAL", "账号创建后查询失败", HttpStatus.INTERNAL_SERVER_ERROR));
    employeeRepository.linkAccount(user.tenantId(), id, authUserId);
    auditEmployeeChange(
        user,
        "创建员工登录账号",
        employee,
        "员工登录账号创建并关联成功",
        employeeAuditState(employee),
        employeeAuditState(employee, employee.status(), Boolean.TRUE)
    );
    return new EmployeeAccountResponse(id, employee.name(), username, initialPassword);
  }

  /** BOSS 上传《门店人员信息.xlsx》导入；重复导入按（门店+姓名）覆盖更新。 */
  public EmployeeImportReport importWorkbook(AuthUser user, byte[] bytes) {
    if (!AccessControlService.isBoss(user)) {
      throw new BusinessException("FORBIDDEN", "员工导入仅限老板执行", HttpStatus.FORBIDDEN);
    }
    List<EmployeeImportParser.ImportRow> rows;
    try {
      rows = EmployeeImportParser.parse(bytes);
    } catch (IOException ex) {
      throw new BusinessException("BAD_FILE", "Excel 解析失败：" + ex.getMessage(), HttpStatus.BAD_REQUEST);
    }
    List<String[]> stores = new ArrayList<>(employeeRepository.storeIdNames(user.tenantId()));
    List<String> createdStores = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    int created = 0;
    int updated = 0;
    int skipped = 0;
    for (EmployeeImportParser.ImportRow row : rows) {
      if (row.storeAlias().isBlank()) {
        problems.add("第" + row.rowNum() + "行：无所属门店，跳过");
        skipped++;
        continue;
      }
      String storeId = matchStore(stores, row.storeAlias());
      if (storeId == null) {
        storeId = "xls" + (stores.size() + 1);
        employeeRepository.createStore(user.tenantId(), storeId, storeId.toUpperCase(), row.storeAlias());
        stores.add(new String[] {storeId, row.storeAlias()});
        createdStores.add(row.storeAlias() + "(" + storeId + ")");
      }
      EmployeeUpsertRequest request = withStore(row.request(), storeId);
      String id = employeeId(user.tenantId(), storeId, request.name().trim());
      boolean exists = employeeRepository.exists(user.tenantId(), id);
      employeeRepository.upsertProfile(user.tenantId(), id, request, "EXCEL_IMPORT");
      if (exists) {
        updated++;
      } else {
        created++;
      }
      for (String problem : row.problems()) {
        problems.add("第" + row.rowNum() + "行 " + request.name() + "：" + problem);
      }
    }
    return new EmployeeImportReport(created, updated, skipped, createdStores, problems);
  }

  private void createAuthUser(
      long tenantId, String username, EmployeeResponse employee, String initialPassword) {
    authRepository.createUserRequiringPasswordChange(
        tenantId, username, passwordService.hash(initialPassword),
        employee.name(), "EMPLOYEE", employee.storeId());
  }

  /** 门店简称匹配：去「店」字与空格后**全等**才算命中；模糊包含会把「宜昌万达/万达2/万达三」误并进「万达店」。 */
  private String matchStore(List<String[]> stores, String alias) {
    String normalizedAlias = alias.replace("店", "").replaceAll("\\s", "");
    for (String[] store : stores) {
      String name = store[1] == null ? "" : store[1].replace("店", "").replaceAll("\\s", "");
      if (!name.isEmpty() && name.equals(normalizedAlias)) {
        return store[0];
      }
    }
    return null;
  }

  private EmployeeUpsertRequest withStore(EmployeeUpsertRequest req, String storeId) {
    return new EmployeeUpsertRequest(storeId, req.name(), req.phone(), req.position(),
        req.employmentType(), req.status(), req.hireDate(), req.birthday(), req.idCardNo(),
        req.healthCertIssueDate(), req.healthCertExpireDate(), req.contractSignText(),
        req.regularDate(), req.trainerDate(), req.shiftLeaderDate(), req.managerDate(), req.remark(),
        req.hourlyRate());
  }

  private void validate(long tenantId, EmployeeUpsertRequest request) {
    if (request == null || request.name() == null || request.name().isBlank()
        || request.storeId() == null || request.storeId().isBlank()) {
      throw new BusinessException("BAD_REQUEST", "门店与姓名必填", HttpStatus.BAD_REQUEST);
    }
    String phone = blankToNull(request.phone());
    if (phone != null && !phone.matches("^\\d{11}$")) {
      throw new BusinessException("EMPLOYEE_PHONE_INVALID", "电话号码必须是11位数字", HttpStatus.BAD_REQUEST);
    }
    String idCardNo = blankToNull(request.idCardNo());
    if (idCardNo != null && !idCardNo.toUpperCase(java.util.Locale.ROOT).matches("^\\d{17}[\\dX]$")) {
      throw new BusinessException(
          "EMPLOYEE_ID_CARD_INVALID", "身份证号码必须是18位，最后一位可以是数字或X", HttpStatus.BAD_REQUEST);
    }
    String birthday = blankToNull(request.birthday());
    if (birthday != null) {
      java.util.regex.Matcher matcher = java.util.regex.Pattern
          .compile("^(\\d{1,2})月(\\d{1,2})日$")
          .matcher(birthday);
      if (!matcher.matches() || !validMonthDay(matcher)) {
        throw new BusinessException(
            "EMPLOYEE_BIRTHDAY_INVALID", "生日请按“4月14日”格式填写", HttpStatus.BAD_REQUEST);
      }
    }
    if (!employeeRepository.storeExists(tenantId, request.storeId())) {
      throw new BusinessException("BAD_REQUEST", "门店不存在：" + request.storeId(), HttpStatus.BAD_REQUEST);
    }
  }

  private boolean validMonthDay(java.util.regex.Matcher matcher) {
    try {
      int month = Integer.parseInt(matcher.group(1));
      int day = Integer.parseInt(matcher.group(2));
      java.time.MonthDay.of(month, day);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private void requireEmployeeManage(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireEmployeeManage(user);
    }
  }

  private void requireStoreWritable(AuthUser user, String storeId, String action) {
    if (accessControl != null && storeId != null && !storeId.isBlank()) {
      accessControl.requireStoreAccess(user, DataScopeDomains.STORE, storeId, action);
    }
  }

  private EmployeeResponse employeeForCurrentTenant(AuthUser user, String id, String action) {
    return employeeRepository.record(user.tenantId(), id).orElseGet(() -> {
      if (employeeRepository.employeeIdBelongsToOtherTenant(user.tenantId(), id)) {
        denyCrossTenantEmployeeAccess(user, id, action);
      }
      throw new BusinessException("NOT_FOUND", "员工不存在", HttpStatus.NOT_FOUND);
    });
  }

  private void denyCrossTenantEmployeeAccess(AuthUser user, String employeeId, String action) {
    if (auditRepository != null && user != null) {
      try {
        auditRepository.writePermissionDenied(
            user,
            action,
            "employee",
            employeeId,
            null,
            "员工档案不属于当前企业"
        );
      } catch (RuntimeException exception) {
        // Preserve the authorization boundary even if a best-effort denial audit is unavailable.
        log.warn("Failed to audit cross-tenant employee denial for user {}: {}", user.id(), exception.getMessage());
      }
    }
    throw new BusinessException("FORBIDDEN", "当前账号没有访问该员工档案的权限", HttpStatus.FORBIDDEN);
  }

  private void auditEmployeeChange(
      AuthUser user,
      String action,
      EmployeeResponse employee,
      String reason,
      String beforeJson,
      String afterJson
  ) {
    if (auditRepository == null) {
      return;
    }
    auditRepository.writeLog(user, new AuditLogRequest(
        action,
        "employee",
        employee.id(),
        employee.storeId(),
        null,
        reason,
        beforeJson,
        afterJson
    ));
  }

  /**
   * Audit snapshots deliberately exclude names, phones, salary, birthdays, health certificates,
   * ID-card values and account credentials. The target id/store id are stored in first-class log
   * columns, while these fields document the affected workflow state only.
   */
  private String employeeAuditState(EmployeeResponse employee) {
    return employeeAuditState(employee, employee.status(), employee.accountEnabled());
  }

  private String employeeAuditState(EmployeeResponse employee, String status, Boolean accountEnabled) {
    return "{\"status\":\"" + jsonString(status)
        + "\",\"employmentType\":\"" + jsonString(employee.employmentType())
        + "\",\"position\":\"" + jsonString(employee.position())
        + "\",\"accountLinked\":" + (employee.authUserId() != null || Boolean.TRUE.equals(accountEnabled))
        + ",\"accountEnabled\":" + (accountEnabled == null ? "null" : accountEnabled)
        + "}";
  }

  private String jsonString(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }

  private EmployeeResponse maskForRole(AuthUser user, EmployeeResponse record) {
    return AccessControlService.isBoss(user) ? record : record.withMaskedIdCard();
  }

  static String employeeId(long tenantId, String storeId, String name) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] bytes = digest.digest((tenantId + "|" + storeId + "|" + name).getBytes(StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder("emp-");
      for (int i = 0; i < 8; i++) {
        value.append(String.format("%02x", bytes[i]));
      }
      return value.toString();
    } catch (NoSuchAlgorithmException ex) {
      return "emp-" + Math.abs((tenantId + "|" + storeId + "|" + name).hashCode());
    }
  }
}
