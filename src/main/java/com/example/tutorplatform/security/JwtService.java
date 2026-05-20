package com.example.tutorplatform.security;

import com.example.tutorplatform.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
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
    if (secret.length() < 32) {
      secret = secret.repeat((32 / Math.max(secret.length(), 1)) + 1);
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
