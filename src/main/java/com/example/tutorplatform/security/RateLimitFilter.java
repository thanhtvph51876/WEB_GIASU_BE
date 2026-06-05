package com.example.tutorplatform.security;

import com.example.tutorplatform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private static final int MAX_BUCKETS = 50_000;
  private static final long CLEANUP_INTERVAL_MS = Duration.ofMinutes(1).toMillis();

  private final ObjectMapper objectMapper;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private volatile long lastCleanupAt;

  public RateLimitFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Rule rule = ruleFor(request);
    if (rule == null) {
      filterChain.doFilter(request, response);
      return;
    }
    long now = System.currentTimeMillis();
    cleanupBuckets(now);
    String key = ip(request) + ":" + request.getMethod() + ":" + normalizedPath(request.getRequestURI());
    Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(now + rule.window().toMillis()));
    if (!bucket.allow(rule)) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(response.getWriter(), ApiResponse.error(
          "RATE_LIMITED",
          "Bạn thao tác quá nhanh. Vui lòng thử lại sau.",
          Map.of("retryAfterSeconds", Math.max(1, (bucket.resetAt - System.currentTimeMillis()) / 1000))
      ));
      return;
    }
    filterChain.doFilter(request, response);
  }

  private Rule ruleFor(HttpServletRequest request) {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return null;
    String path = normalizedPath(request.getRequestURI());
    String method = request.getMethod();
    if ("POST".equals(method) && path.matches("/api/v1/auth/(login|register|refresh|forgot-password|reset-password|verify-email)")) {
      return new Rule(10, Duration.ofMinutes(1));
    }
    if ("POST".equals(method) && path.equals("/api/v1/public/learning-requests")) {
      return new Rule(3, Duration.ofHours(1));
    }
    if ("POST".equals(method) && path.equals("/api/v1/public/trial-booking-requests")) {
      return new Rule(3, Duration.ofHours(1));
    }
    if ("POST".equals(method) && path.equals("/api/v1/contact-requests")) {
      return new Rule(5, Duration.ofHours(1));
    }
    if ("GET".equals(method) && (path.equals("/api/v1/tutors") || path.matches("/api/v1/tutors/[0-9a-fA-F-]{36}"))) {
      return new Rule(120, Duration.ofMinutes(1));
    }
    if ("POST".equals(method) && (path.equals("/api/v1/uploads") || path.contains("/verifications/") && path.endsWith("/upload"))) {
      return new Rule(20, Duration.ofMinutes(10));
    }
    return null;
  }

  private String normalizedPath(String uri) {
    String path = uri == null ? "" : uri;
    int index = path.indexOf('?');
    return index >= 0 ? path.substring(0, index) : path;
  }

  private String ip(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (trustedProxy(remoteAddr)) {
      String forwardedFor = firstHeaderIp(request.getHeader("X-Forwarded-For"));
      if (forwardedFor != null) return forwardedFor;
      String realIp = firstHeaderIp(request.getHeader("X-Real-IP"));
      if (realIp != null) return realIp;
    }
    return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr.trim();
  }

  private String firstHeaderIp(String value) {
    if (value == null || value.isBlank()) return null;
    String candidate = value.split(",")[0].trim();
    if (candidate.isBlank() || candidate.length() > 64) return null;
    return candidate;
  }

  private boolean trustedProxy(String remoteAddr) {
    if (remoteAddr == null || remoteAddr.isBlank()) return false;
    String value = remoteAddr.trim().toLowerCase();
    if (value.equals("127.0.0.1") || value.equals("localhost") || value.equals("::1") || value.equals("0:0:0:0:0:0:0:1")) {
      return true;
    }
    if (value.startsWith("10.") || value.startsWith("192.168.")) return true;
    if (value.startsWith("172.")) {
      String[] parts = value.split("\\.");
      if (parts.length > 1) {
        try {
          int second = Integer.parseInt(parts[1]);
          return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
          return false;
        }
      }
    }
    return value.startsWith("fc") || value.startsWith("fd") || value.startsWith("fe80:");
  }

  private void cleanupBuckets(long now) {
    if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return;
    lastCleanupAt = now;
    buckets.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    if (buckets.size() <= MAX_BUCKETS) return;
    int overflow = buckets.size() - MAX_BUCKETS;
    Iterator<String> keys = buckets.keySet().iterator();
    while (overflow > 0 && keys.hasNext()) {
      keys.next();
      keys.remove();
      overflow--;
    }
  }

  private static final class Bucket {
    private long resetAt;
    private int count;

    private Bucket(long resetAt) {
      this.resetAt = resetAt;
    }

    private synchronized boolean allow(Rule rule) {
      long now = System.currentTimeMillis();
      if (now > resetAt) {
        resetAt = now + rule.window().toMillis();
        count = 0;
      }
      count++;
      return count <= rule.maxRequests();
    }

    private synchronized boolean isExpired(long now) {
      return now > resetAt;
    }
  }

  private record Rule(int maxRequests, Duration window) {}
}
