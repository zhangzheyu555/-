package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AccountBootstrapRemovalContractTest {
  private static final List<String> FORBIDDEN_BOOTSTRAP_KEYS = List.of(
      "app.bootstrap.default-users",
      "app.bootstrap.store-manager",
      "APP_BOOTSTRAP_DEFAULT_USERS_",
      "APP_BOOTSTRAP_STORE_MANAGER_"
  );

  @Test
  void authServiceHasNoAccountBootstrapStateConfigurationOrInitializer() throws IOException {
    assertThat(Arrays.stream(AuthService.class.getDeclaredFields()).map(Field::getName).toList())
        .noneMatch(name -> name.toLowerCase().contains("bootstrap"));
    assertThat(Arrays.stream(AuthService.class.getDeclaredMethods()).map(Method::getName).toList())
        .doesNotContain("ensureDefaultUsers", "ensureDefaultUser");
    assertThat(Arrays.stream(AuthService.class.getDeclaredMethods()).toList())
        .noneMatch(method -> method.isAnnotationPresent(PostConstruct.class));

    List<String> constructorAnnotations = Arrays.stream(AuthService.class.getDeclaredConstructors())
        .flatMap(AccountBootstrapRemovalContractTest::parameters)
        .flatMap(parameter -> Arrays.stream(parameter.getAnnotations()))
        .map(Annotation::toString)
        .toList();
    assertThat(constructorAnnotations)
        .noneMatch(annotation -> annotation.contains("app.bootstrap"));

    assertThat(read(backendPath("src", "main", "java", "com", "storeprofit", "system",
        "platform", "auth", "AuthService.java")))
        .doesNotContain("ensureDefaultUsers", "ensureDefaultUser")
        .doesNotContain("app.bootstrap.default-users")
        .doesNotContain("APP_BOOTSTRAP_DEFAULT_USERS_");
  }

  @Test
  void storeManagerAccountSeedServiceIsAbsentFromSourceAndRuntime() {
    Path source = backendPath("src", "main", "java", "com", "storeprofit", "system",
        "platform", "auth", "StoreManagerAccountSeedService.java");

    assertThat(source).doesNotExist();
    assertThatThrownBy(() -> Class.forName(
        "com.storeprofit.system.platform.auth.StoreManagerAccountSeedService"))
        .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  void productionCodeHasNoAccountCreatingStartupHook() throws IOException {
    Path productionJava = backendPath("src", "main", "java");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(productionJava)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = read(file);
        boolean startupHook = source.contains("@PostConstruct")
            || source.contains("implements ApplicationRunner")
            || source.contains("implements CommandLineRunner");
        boolean createsAccount = source.contains("authRepository.createUser(")
            || source.toLowerCase().contains("insert into auth_user");
        if (startupHook && createsAccount) {
          violations.add(productionJava.relativize(file).toString());
        }
      }
    }

    assertThat(violations).isEmpty();
  }

  @Test
  void activeApplicationConfigurationExposesNoRemovedBootstrapKeys() throws IOException {
    Path resources = backendPath("src", "main", "resources");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.list(resources)) {
      for (Path file : files.filter(AccountBootstrapRemovalContractTest::isApplicationConfig).toList()) {
        String source = read(file);
        for (String key : FORBIDDEN_BOOTSTRAP_KEYS) {
          if (source.contains(key)) {
            violations.add(file.getFileName() + " contains " + key);
          }
        }
      }
    }

    assertThat(violations).isEmpty();
  }

  @Test
  void authControllerExposesOnlyFormalAuthenticationOperations() {
    assertThat(Arrays.stream(AuthController.class.getDeclaredMethods()).map(Method::getName).toList())
        .containsExactlyInAnyOrder("login", "logout", "me");
  }

  private static Stream<Parameter> parameters(Constructor<?> constructor) {
    return Arrays.stream(constructor.getParameters());
  }

  private static boolean isApplicationConfig(Path path) {
    String name = path.getFileName().toString();
    return Files.isRegularFile(path)
        && name.startsWith("application")
        && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
  }

  private static Path backendPath(String... parts) {
    Path workingDirectory = Path.of("").toAbsolutePath().normalize();
    Path backend = Files.isDirectory(workingDirectory.resolve("src/main/java"))
        ? workingDirectory
        : workingDirectory.resolve("backend");
    Path result = backend;
    for (String part : parts) {
      result = result.resolve(part);
    }
    return result.normalize();
  }

  private static String read(Path path) throws IOException {
    return Files.readString(path, StandardCharsets.UTF_8);
  }
}
