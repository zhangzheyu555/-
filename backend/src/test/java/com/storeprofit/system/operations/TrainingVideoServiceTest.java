package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.TrainingVideoModels.ProgressReportRequest;
import com.storeprofit.system.operations.TrainingVideoRepository.ProgressRow;
import com.storeprofit.system.operations.TrainingVideoRepository.VideoRow;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.storage.StorageService.TrainingVideoContent;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class TrainingVideoServiceTest {
  private final AccessControlService access = mock(AccessControlService.class);
  private final TrainingVideoRepository repository = mock(TrainingVideoRepository.class);
  private final ExamLearningRepository learningRepository = mock(ExamLearningRepository.class);
  private final StorageService storage = mock(StorageService.class);
  private final AuditRepository audit = mock(AuditRepository.class);
  private final TrainingVideoService service = new TrainingVideoService(
      access, repository, learningRepository, storage, audit);
  private final AuthUser employee = new AuthUser(
      7L, 1L, "测试企业", "employee", "", "员工甲", "EMPLOYEE", "STORE_A", true);

  @Test
  void rejectsExtensionMimeAndSignatureMismatch() {
    MockMultipartFile disguised = new MockMultipartFile(
        "file", "课程.mp4", "video/mp4", "not-a-video".getBytes());

    assertThatThrownBy(() -> service.upload(employee, disguised, null, null, null, null))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("VIDEO_CONTENT_INVALID"));

    verify(access).requireExamManage(employee);
  }

  @Test
  void rangeReadIsAuthenticatedAndBounded() {
    VideoRow video = video(1L, new BigDecimal("120.00"));
    byte[] content = new byte[1024 * 1024];
    when(repository.video(1L, 1L)).thenReturn(Optional.of(video));
    when(storage.trainingVideoContent(eq(employee), eq(11L), eq(0L), eq(content.length)))
        .thenReturn(Optional.of(new TrainingVideoContent(
            11L, "课程.mp4", "video/mp4", video.fileSize(), 0, content.length - 1, content)));

    var result = service.content(employee, 1L, "bytes=0-9999999");

    assertThat(result.partial()).isTrue();
    assertThat(result.content()).hasSize(1024 * 1024);
    assertThat(result.end()).isEqualTo(1024 * 1024 - 1L);
    verify(access).requireExamRead(employee);
  }

  @Test
  void crossTenantVideoReturnsForbidden() {
    when(repository.video(1L, 99L)).thenReturn(Optional.empty());
    when(repository.tenantForVideo(99L)).thenReturn(Optional.of(2L));

    assertThatThrownBy(() -> service.content(employee, 99L, null))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("FORBIDDEN");
          assertThat(error.getStatus().value()).isEqualTo(403);
        });
  }

  @Test
  void forgedFirstProgressReportCannotCompleteVideo() {
    VideoRow video = video(1L, null);
    when(repository.video(1L, 1L)).thenReturn(Optional.of(video));
    when(repository.progressForUpdate(1L, 1L, 7L)).thenReturn(Optional.of(new ProgressRow(
        1L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null)));

    var result = service.reportProgress(
        employee, 1L, new ProgressReportRequest(new BigDecimal("120"), new BigDecimal("120")));

    assertThat(result.watchedSeconds()).isEqualByComparingTo("3.00");
    assertThat(result.percent()).isEqualByComparingTo("2.50");
    assertThat(result.completed()).isFalse();
    verify(repository).updateProgress(
        eq(1L), eq(1L), eq(7L), decimal("3.00"), decimal("120.00"),
        decimal("2.50"), decimal("4.00"), eq(false));
  }

  private VideoRow video(long id, BigDecimal duration) {
    return new VideoRow(
        id, "VIDEO_1", 11L, null, null, "安全课程", "安全", null,
        "课程.mp4", "video/mp4", 5L * 1024 * 1024, duration, true, 0, "2026-07-14 17:00:00");
  }

  private BigDecimal decimal(String expected) {
    BigDecimal value = new BigDecimal(expected);
    return argThat(actual -> actual != null && actual.compareTo(value) == 0);
  }
}
