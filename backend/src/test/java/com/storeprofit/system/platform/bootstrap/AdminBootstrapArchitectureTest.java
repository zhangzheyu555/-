package com.storeprofit.system.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AdminBootstrapArchitectureTest {
  @Test
  void bootstrapPackageIsAnOrdinaryNonSpringNonWebCommand() throws IOException {
    Path bootstrapPackage = backendPath(
        "src", "main", "java", "com", "storeprofit", "system", "platform", "bootstrap");
    List<String> forbidden = List.of(
        "org.springframework",
        "@Component",
        "@Service",
        "ApplicationRunner",
        "CommandLineRunner",
        "@PostConstruct",
        "SpringApplication",
        "SpringApplication.run",
        "org.flywaydb",
        ".migrate(",
        "System.exit",
        " static void main(");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(bootstrapPackage)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = read(file);
        for (String token : forbidden) {
          if (source.contains(token)) {
            violations.add(file.getFileName() + " contains " + token);
          }
        }
      }
    }
    assertThat(violations).isEmpty();
  }

  @Test
  void onlyMainMayExitAndItDispatchesBeforeConstructingSpring() throws IOException {
    Path productionJava = backendPath("src", "main", "java");
    List<Path> exitCallers = new ArrayList<>();
    try (Stream<Path> files = Files.walk(productionJava)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        if (read(file).contains("System.exit")) {
          exitCallers.add(productionJava.relativize(file));
        }
      }
    }
    assertThat(exitCallers).containsExactly(
        Path.of("com", "storeprofit", "system", "StoreProfitApplication.java"));

    String main = read(productionJava.resolve(
        Path.of("com", "storeprofit", "system", "StoreProfitApplication.java")));
    assertThat(main.indexOf("startupMode(args, System.getenv())"))
        .isGreaterThanOrEqualTo(0)
        .isLessThan(main.indexOf("new SpringApplication"));
  }

  @Test
  void noProductionLogCleanupCanDeletePermanentProvisioningAudit() throws IOException {
    Path productionSource = backendPath("src", "main");
    List<Path> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(productionSource)) {
      for (Path file : files.filter(AdminBootstrapArchitectureTest::isAuditableSource).toList()) {
        String normalized = read(file).toLowerCase();
        boolean truncate = normalized.contains("truncate operation_log")
            || normalized.contains("truncate table operation_log");
        boolean delete = normalized.contains("delete from operation_log");
        boolean permanentAuditExcluded = normalized.contains("first_boss_provisioned")
            && (normalized.contains("<>")
                || normalized.contains("!=")
                || normalized.contains(" not "));
        if (truncate || (delete && !permanentAuditExcluded)) {
          violations.add(productionSource.relativize(file));
        }
      }
    }
    assertThat(violations).isEmpty();
  }

  @Test
  void authenticationControllerHasNoBootstrapHttpOperation() throws IOException {
    Path productionJava = backendPath("src", "main", "java");
    List<Path> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(productionJava)) {
      for (Path file : files
          .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
          .toList()) {
        String controller = read(file).toLowerCase();
        if (controller.contains("bootstrap")
            || controller.contains("initialize")
            || controller.contains("create-admin")) {
          violations.add(productionJava.relativize(file));
        }
      }
    }
    assertThat(violations).isEmpty();
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

  private static boolean isAuditableSource(Path path) {
    if (!Files.isRegularFile(path)) {
      return false;
    }
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(".java")
        || name.endsWith(".sql")
        || name.endsWith(".yml")
        || name.endsWith(".yaml")
        || name.endsWith(".properties")
        || name.endsWith(".xml")
        || name.endsWith(".md");
  }
}
