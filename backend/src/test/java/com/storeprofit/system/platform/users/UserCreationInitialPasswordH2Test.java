package com.storeprofit.system.platform.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.LoginRequest;
import com.storeprofit.system.platform.auth.LoginResponse;
import com.storeprofit.system.platform.auth.PasswordService;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class UserCreationInitialPasswordH2Test {
  private static final String INITIAL_PASSWORD = "TestOnly!InitialA9";

  @Test
  void managementCreatedAccountMustChangeItsInitialPasswordBeforeAuthentication() {
    JdbcDataSource dataSource = migratedDataSource();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    AuthRepository authRepository = new AuthRepository(jdbc);
    PasswordService passwordService = new PasswordService();
    AuditRepository auditRepository = new AuditRepository(jdbc);
    UserManagementService userManagement = new UserManagementService(
        authRepository,
        passwordService,
        mock(OrganizationRepository.class),
        mock(AccessControlService.class),
        auditRepository
    );
    AuthService authService = new AuthService(authRepository, passwordService, auditRepository, 12);

    authRepository.createUser(
        1L, "test-boss", passwordService.hash("TestOnly!BossPasswordB8"),
        "TEST 老板", "BOSS", null);
    var boss = authRepository.findByUsername(1L, "test-boss").orElseThrow();

    userManagement.create(boss, new UserCreateRequest(
        "new-finance", "TEST 财务", "FINANCE", null, List.of(), INITIAL_PASSWORD));

    var created = authRepository.findByUsername(1L, "new-finance").orElseThrow();
    LoginResponse login = authService.login(new LoginRequest("new-finance", INITIAL_PASSWORD, 1L));
    assertThat(authRepository.passwordChangeRequired(1L, created.id())).isTrue();
    assertThat(login.status()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    assertThat(login.token()).isNull();
    assertThat(login.user()).isNull();
    assertThat(login.passwordChangeCredential()).isNotBlank();
  }

  private JdbcDataSource migratedDataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:user_creation_password_" + UUID.randomUUID()
        + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .load()
        .migrate();
    return dataSource;
  }
}
