package com.storeprofit.system;

import com.storeprofit.system.config.DatabaseEnvironmentGuard;
import com.storeprofit.system.platform.bootstrap.AdminBootstrapCommand;
import com.storeprofit.system.platform.bootstrap.AdminBootstrapPasswordSource;
import com.storeprofit.system.platform.bootstrap.AdminBootstrapResult;
import java.util.Map;
import java.util.Arrays;
import java.util.function.Supplier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StoreProfitApplication {
  public static void main(String[] args) {
    StartupMode startupMode = startupMode(args, System.getenv());
    if (startupMode != StartupMode.WEB) {
      AdminBootstrapResult result = bootstrapResult(
          startupMode,
          () -> new AdminBootstrapCommand().execute(
                args,
                System.getenv(),
                AdminBootstrapPasswordSource.system(
                    System.getenv(), System.console(), System.in)));
      if (result.exitCode() == 0) {
        System.out.println(result.machineMessage());
      } else {
        System.err.println(result.machineMessage());
      }
      System.exit(result.exitCode());
      return;
    }
    SpringApplication application = new SpringApplication(StoreProfitApplication.class);
    application.addInitializers(context -> {
      boolean localProfile = Arrays.stream(context.getEnvironment().getActiveProfiles())
          .anyMatch("local"::equalsIgnoreCase);
      if (!localProfile) {
        DatabaseEnvironmentGuard.validate(context.getEnvironment());
      }
    });
    application.run(args);
  }

  static StartupMode startupMode(String[] args, Map<String, String> environment) {
    boolean commandPresent = args != null
        && java.util.Arrays.stream(args)
            .anyMatch(AdminBootstrapCommand.COMMAND_ARGUMENT::equals);
    if (!commandPresent) {
      return StartupMode.WEB;
    }
    return environment != null
        && "true".equals(environment.get(AdminBootstrapCommand.ENABLED_ENVIRONMENT))
        ? StartupMode.BOOTSTRAP
        : StartupMode.DISABLED;
  }

  static AdminBootstrapResult bootstrapResult(
      StartupMode startupMode,
      Supplier<AdminBootstrapResult> command
  ) {
    if (startupMode == StartupMode.DISABLED) {
      return AdminBootstrapResult.safetyRejected();
    }
    try {
      AdminBootstrapResult result = command.get();
      return result == null ? AdminBootstrapResult.unexpectedFailure() : result;
    } catch (RuntimeException exception) {
      return AdminBootstrapResult.unexpectedFailure();
    }
  }

  enum StartupMode {
    WEB,
    DISABLED,
    BOOTSTRAP
  }
}
