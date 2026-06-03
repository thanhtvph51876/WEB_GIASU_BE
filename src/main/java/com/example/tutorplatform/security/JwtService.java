package com.example.tutorplatform.security;

import com.example.tutorplatform.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final AppProperties properties;
  private final SecretKey key;

  public JwtService(AppProperties properties) {
    this.properties = properties;
    String secret = properties.jwt().secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("JWT_SECRET must be configured.");
    }
    if (isProduction() && (secret.length() < 32 || isPlaceholderSecret(secret))) {
      throw new IllegalStateException("JWT_SECRET must be a non-placeholder secret of at least 32 characters in production.");
    }
    if (secret.length() < 32) {
      secret = secret.repeat((32 / Math.max(secret.length(), 1)) + 1);
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  private boolean isProduction() {
    String env = properties.env() == null ? "" : properties.env().trim().toLowerCase(Locale.ROOT);
    return "prod".equals(env) || "production".equals(env);
  }

  private boolean isPlaceholderSecret(String secret) {
    String normalized = secret.toLowerCase(Locale.ROOT);
    return normalized.contains("change-me") || normalized.contains("changeme") || normalized.contains("placeholder");
  }

  public String accessToken(String userId, String role) {
    return token(userId, role, "access", properties.jwt().accessTtl().toSeconds());
  }

  public String refreshToken(String userId, String role) {
    return token(userId, role, "refresh", properties.jwt().refreshTtl().toSeconds());
  }

  private String token(String userId, String role, String type, long ttlSeconds) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(userId)
        .claims(Map.of("role", role, "type", type))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttlSeconds)))
        .signWith(key)
        .compact();
  }

  public Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
