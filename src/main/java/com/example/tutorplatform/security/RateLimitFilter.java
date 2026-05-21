package com.example.tutorplatform.security;

import com.example.tutorplatform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final ObjectMapper objectMapper;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

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
    String key = ip(request) + ":" + request.getMethod() + ":" + normalizedPath(request.getRequestURI());
    Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(System.currentTimeMillis() + rule.window().toMillis()));
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
      return new Rule(8, Duration.ofHours(1));
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
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) return forwardedFor.split(",")[0].trim();
    return request.getRemoteAddr();
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
  }

  private record Rule(int maxRequests, Duration window) {}
}
