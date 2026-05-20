package com.example.tutorplatform.security;

import com.example.tutorplatform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Limit limit = limitFor(request);
    if (limit != null && exceeded(request, limit)) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType("application/json;charset=UTF-8");
      objectMapper.writeValue(response.getWriter(), ApiResponse.error("RATE_LIMITED", "Bạn thao tác quá nhanh, vui lòng thử lại sau.", null));
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean exceeded(HttpServletRequest request, Limit limit) {
    long minute = Instant.now().getEpochSecond() / 60;
    String key = ip(request) + ":" + limit.key() + ":" + minute;
    Window window = windows.computeIfAbsent(key, ignored -> new Window(minute));
    cleanup(minute);
    return window.count().incrementAndGet() > limit.requestsPerMinute();
  }

  private void cleanup(long currentMinute) {
    windows.entrySet().removeIf(entry -> entry.getValue().minute() < currentMinute - 2);
  }

  private Limit limitFor(HttpServletRequest request) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) return null;
    String path = request.getRequestURI();
    if (path.equals("/api/v1/auth/login")) return new Limit("auth-login", 10);
    if (path.equals("/api/v1/auth/refresh")) return new Limit("auth-refresh", 30);
    if (path.equals("/api/v1/auth/forgot-password")) return new Limit("forgot-password", 5);
    if (path.equals("/api/v1/contact-requests")) return new Limit("contact", 10);
    if (path.equals("/api/v1/uploads")) return new Limit("uploads", 20);
    if (path.startsWith("/api/v1/payments/webhooks/")) return new Limit("payment-webhook", 120);
    return null;
  }

  private String ip(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private record Limit(String key, int requestsPerMinute) {}

  private record Window(long minute, AtomicInteger count) {
    Window(long minute) {
      this(minute, new AtomicInteger());
    }
  }
}
