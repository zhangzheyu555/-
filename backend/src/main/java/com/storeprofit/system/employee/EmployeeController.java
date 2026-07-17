package com.storeprofit.system.employee;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
  private final AuthService authService;
  private final EmployeeService employeeService;

  public EmployeeController(AuthService authService, EmployeeService employeeService) {
    this.authService = authService;
    this.employeeService = employeeService;
  }

  @GetMapping
  public ApiResponse<List<EmployeeResponse>> records(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.ok(employeeService.records(authService.requireUser(authorization), brandId, storeId, status));
  }

  /** 员工本人档案（员工工作台「我的资料」）。 */
  @GetMapping("/me")
  public ApiResponse<EmployeeResponse> me(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(employeeService.me(authService.requireUser(authorization)));
  }

  @GetMapping("/{id}")
  public ApiResponse<EmployeeResponse> detail(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(employeeService.detail(authService.requireUser(authorization), id));
  }

  @PostMapping
  public ApiResponse<EmployeeResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody EmployeeUpsertRequest request
  ) {
    return ApiResponse.ok(employeeService.create(authService.requireUser(authorization), request));
  }

  @PutMapping("/{id}")
  public ApiResponse<EmployeeResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody EmployeeUpsertRequest request
  ) {
    return ApiResponse.ok(employeeService.update(authService.requireUser(authorization), id, request));
  }

  /** 删除 = 离职留档 + 禁用账号。 */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> remove(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    employeeService.remove(authService.requireUser(authorization), id);
    return ApiResponse.ok(null);
  }

  /** 开号：店长账号-序号（ruguo1-1）；兼职员工不开号。 */
  @PostMapping("/{id}/account")
  public ApiResponse<EmployeeAccountResponse> createAccount(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(employeeService.createAccount(authService.requireUser(authorization), id));
  }

  /** BOSS 上传《门店人员信息.xlsx》导入，可重复执行（按门店+姓名覆盖）。 */
  @PostMapping("/import")
  public ApiResponse<EmployeeImportReport> importWorkbook(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam MultipartFile file
  ) {
    try {
      return ApiResponse.ok(employeeService.importWorkbook(
          authService.requireUser(authorization), file.getBytes()));
    } catch (IOException ex) {
      throw new BusinessException("BAD_FILE", "读取上传文件失败", HttpStatus.BAD_REQUEST);
    }
  }
}
