package com.storeprofit.system.operations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TrainingVideoControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final TrainingVideoService service = mock(TrainingVideoService.class);
  private final TrainingVideoPlaybackTicketService ticketService =
      mock(TrainingVideoPlaybackTicketService.class);
  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new TrainingVideoController(authService, service, ticketService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void unauthenticatedListReturns401BeforeService() throws Exception {
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(get("/api/exam-center/videos"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(service, never()).videos(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void authenticatedListReturnsOnlyServiceVisibleRows() throws Exception {
    AuthUser user = new AuthUser(
        7L, 1L, "测试企业", "employee", "", "员工甲", "EMPLOYEE", "STORE_A", true);
    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(service.videos(user)).thenReturn(List.of());

    mockMvc.perform(get("/api/exam-center/videos").header("Authorization", "Bearer token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    verify(service).videos(user);
  }

  @Test
  void unauthenticatedTicketRequestReturns401BeforeIssuingCredential() throws Exception {
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(post("/api/exam-center/videos/7/playback-ticket"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(service, never()).authorizeContent(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong());
    verify(ticketService, never()).issue(
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void authenticatedTicketIsScopedAndDoesNotExposePrimaryBearer() throws Exception {
    AuthUser user = employee();
    when(authService.requireUser("Bearer primary-secret")).thenReturn(user);
    when(ticketService.issue(7L, "Bearer primary-secret")).thenReturn(
        new TrainingVideoPlaybackTicketService.PlaybackTicket(
            "temporary-ticket", Instant.parse("2026-07-15T08:30:00Z")));

    mockMvc.perform(post("/api/exam-center/videos/7/playback-ticket")
            .header(HttpHeaders.AUTHORIZATION, "Bearer primary-secret"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.playbackPath")
            .value("/api/exam-center/videos/7/stream?ticket=temporary-ticket"))
        .andExpect(jsonPath("$.data.playbackPath").value(
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("primary-secret"))))
        .andExpect(jsonPath("$.data.expiresAt").value("2026-07-15T08:30:00Z"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
        .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"));

    verify(service).authorizeContent(user, 7L);
  }

  @Test
  void eachTicketRangeRequestRevalidatesSessionAndReturns206() throws Exception {
    AuthUser user = employee();
    byte[] bytes = new byte[] {1, 2, 3, 4};
    when(ticketService.authorizationFor(7L, "temporary-ticket"))
        .thenReturn("Bearer current-session");
    when(authService.requireUser("Bearer current-session")).thenReturn(user);
    when(service.content(user, 7L, "bytes=0-3")).thenReturn(
        new TrainingVideoService.VideoContentResponse(
            "course.mp4", "video/mp4", 100L, 0L, 3L, true, bytes));

    mockMvc.perform(get("/api/exam-center/videos/7/stream")
            .queryParam("ticket", "temporary-ticket")
            .header(HttpHeaders.RANGE, "bytes=0-3"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
        .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/100"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(content().bytes(bytes));

    verify(authService).requireUser("Bearer current-session");
    verify(service).content(user, 7L, "bytes=0-3");
  }

  private AuthUser employee() {
    return new AuthUser(
        7L, 1L, "测试企业", "employee", "", "员工甲", "EMPLOYEE", "STORE_A", true);
  }
}
