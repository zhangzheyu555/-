package com.storeprofit.system.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/** Resolves the proxy-supplied client address only for loopback or private Docker peers. */
@Component
public class ClientIpResolver {
  public String resolve(HttpServletRequest request) {
    String remoteAddress = request == null ? "" : normalize(request.getRemoteAddr());
    if (request == null || !isTrustedProxy(remoteAddress)) {
      return remoteAddress;
    }
    String forwarded = normalize(request.getHeader("X-Real-IP"));
    return forwarded.isEmpty() ? remoteAddress : forwarded;
  }

  private boolean isTrustedProxy(String address) {
    try {
      InetAddress candidate = InetAddress.getByName(address);
      return candidate.isLoopbackAddress() || candidate.isSiteLocalAddress() || candidate.isLinkLocalAddress();
    } catch (UnknownHostException ignored) {
      return false;
    }
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
