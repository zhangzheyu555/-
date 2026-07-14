package com.storeprofit.system.platform.authorization;

import java.util.List;

public record WorkspaceAccessProfile(
    List<String> availableWorkspaces,
    String defaultWorkspace,
    String status,
    String message
) {
  public static final String READY = "READY";
  public static final String NO_WORKSPACE = "NO_WORKSPACE";
  public static final String DISABLED = "DISABLED";

  public WorkspaceAccessProfile {
    availableWorkspaces = availableWorkspaces == null ? List.of() : List.copyOf(availableWorkspaces);
    defaultWorkspace = defaultWorkspace == null || defaultWorkspace.isBlank()
        ? "/no-permission"
        : defaultWorkspace;
    status = status == null || status.isBlank() ? NO_WORKSPACE : status;
    message = message == null ? "" : message;
  }
}
