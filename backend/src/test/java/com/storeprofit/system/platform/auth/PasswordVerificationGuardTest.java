package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.RateLimitException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PasswordVerificationGuardTest {
  @Test
  void productionConstructorIsExplicitlySelectedForSpringInjection() {
    assertThatCode(() -> Arrays.stream(PasswordVerificationGuard.class.getDeclaredConstructors())
        .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
        .findFirst()
        .orElseThrow())
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsExcessPasswordVerificationBeforeStartingExpensiveWork() throws Exception {
    PasswordVerificationGuard guard = new PasswordVerificationGuard(1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> first = executor.submit(() -> guard.verify(() -> {
        started.countDown();
        try {
          return release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          return false;
        }
      }));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> guard.verify(() -> true))
          .isInstanceOf(RateLimitException.class);

      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }
}
